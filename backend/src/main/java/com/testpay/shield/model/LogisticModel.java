package com.testpay.shield.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * L2-regularised logistic regression over sparse named features, trained with full-batch Adam.
 * Chosen as the production model because it is calibrated by construction, explainable per feature
 * (each payment's score splits into per-feature contributions), and small enough to ship as JSON.
 */
public class LogisticModel implements ProbModel {

    public String name;
    public List<String> features = new ArrayList<>();
    public double[] weights = new double[0];
    public double bias;
    /** For num:* features: [mean, std] used to standardise. */
    public Map<String, double[]> scaling = new HashMap<>();
    public int trainRows;
    public double trainPositiveRate;

    @JsonIgnore
    private transient Map<String, Integer> index;

    private Map<String, Integer> index() {
        if (index == null) {
            Map<String, Integer> m = new HashMap<>();
            for (int i = 0; i < features.size(); i++) m.put(features.get(i), i);
            index = m;
        }
        return index;
    }

    private double transform(String name, double v) {
        double[] s = scaling.get(name);
        return s == null ? v : (v - s[0]) / s[1];
    }

    public double logit(Map<String, Double> x) {
        double z = bias;
        Map<String, Integer> idx = index();
        for (Map.Entry<String, Double> e : x.entrySet()) {
            Integer j = idx.get(e.getKey());
            if (j == null) continue;
            z += weights[j] * transform(e.getKey(), e.getValue());
        }
        return z;
    }

    @Override
    public double predict(Map<String, Double> x) {
        return sigmoid(logit(x));
    }

    /** Per-feature contribution to the logit, relative to an average job (num features centred). */
    public Map<String, Double> contributions(Map<String, Double> x) {
        Map<String, Double> out = new HashMap<>();
        Map<String, Integer> idx = index();
        for (Map.Entry<String, Double> e : x.entrySet()) {
            Integer j = idx.get(e.getKey());
            if (j == null) continue;
            double c = weights[j] * transform(e.getKey(), e.getValue());
            if (c != 0) out.put(e.getKey(), c);
        }
        return out;
    }

    public double weight(String feature) {
        Integer j = index().get(feature);
        return j == null ? 0 : weights[j];
    }

    public static double sigmoid(double z) {
        if (z >= 0) return 1 / (1 + Math.exp(-z));
        double e = Math.exp(z);
        return e / (1 + e);
    }

    // ------------------------------------------------------------------ training

    public static LogisticModel fit(String name, List<Map<String, Double>> rows, int[] y,
                                    int minCount, double l2, int epochs) {
        int n = rows.size();
        LogisticModel m = new LogisticModel();
        m.name = name;
        m.trainRows = n;
        if (n == 0) return m;

        // Vocabulary: keep features that are non-zero in at least minCount rows.
        Map<String, Integer> counts = new HashMap<>();
        Map<String, double[]> sums = new HashMap<>(); // num: [sum, sumSq, count]
        for (Map<String, Double> r : rows) {
            for (Map.Entry<String, Double> e : r.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("num:")) {
                    double[] s = sums.computeIfAbsent(k, kk -> new double[3]);
                    s[0] += e.getValue(); s[1] += e.getValue() * e.getValue(); s[2]++;
                    counts.merge(k, 1, Integer::sum);
                } else if (e.getValue() != 0) {
                    counts.merge(k, 1, Integer::sum);
                }
            }
        }
        List<String> vocab = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= minCount) vocab.add(e.getKey());
        }
        Collections.sort(vocab);
        for (String k : vocab) {
            if (!k.startsWith("num:")) continue;
            double[] s = sums.get(k);
            double mean = s[0] / s[2];
            double var = Math.max(s[1] / s[2] - mean * mean, 0);
            double std = Math.sqrt(var);
            m.scaling.put(k, new double[]{mean, std < 1e-9 ? 1.0 : std});
        }
        // Drop constant numeric features.
        vocab.removeIf(k -> k.startsWith("num:") && sums.get(k) != null
                && Math.sqrt(Math.max(sums.get(k)[1] / sums.get(k)[2] - Math.pow(sums.get(k)[0] / sums.get(k)[2], 2), 0)) < 1e-9);
        m.features = vocab;
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < vocab.size(); i++) idx.put(vocab.get(i), i);

        // Sparse design matrix.
        int[][] xi = new int[n][];
        double[][] xv = new double[n][];
        for (int r = 0; r < n; r++) {
            Map<String, Double> row = rows.get(r);
            int[] ii = new int[row.size()];
            double[] vv = new double[row.size()];
            int k = 0;
            for (Map.Entry<String, Double> e : row.entrySet()) {
                Integer j = idx.get(e.getKey());
                if (j == null) continue;
                double v = m.transform(e.getKey(), e.getValue());
                if (v == 0) continue;
                ii[k] = j; vv[k] = v; k++;
            }
            xi[r] = Arrays.copyOf(ii, k);
            xv[r] = Arrays.copyOf(vv, k);
        }

        int p = vocab.size();
        double pos = 0;
        for (int v : y) pos += v;
        double rate = Math.min(Math.max(pos / n, 1e-4), 1 - 1e-4);
        m.trainPositiveRate = pos / n;
        double[] w = new double[p];
        double b = Math.log(rate / (1 - rate));

        // Adam.
        double lr = 0.05, b1 = 0.9, b2 = 0.999, eps = 1e-8;
        double[] mw = new double[p], vw = new double[p];
        double mb = 0, vb = 0;
        double[] grad = new double[p];
        for (int epoch = 1; epoch <= epochs; epoch++) {
            Arrays.fill(grad, 0);
            double gb = 0;
            for (int r = 0; r < n; r++) {
                double z = b;
                int[] ii = xi[r]; double[] vv = xv[r];
                for (int k = 0; k < ii.length; k++) z += w[ii[k]] * vv[k];
                double err = sigmoid(z) - y[r];
                gb += err;
                for (int k = 0; k < ii.length; k++) grad[ii[k]] += err * vv[k];
            }
            gb /= n;
            double c1 = 1 - Math.pow(b1, epoch), c2 = 1 - Math.pow(b2, epoch);
            for (int j = 0; j < p; j++) {
                double g = grad[j] / n + l2 * w[j] / n;
                mw[j] = b1 * mw[j] + (1 - b1) * g;
                vw[j] = b2 * vw[j] + (1 - b2) * g * g;
                w[j] -= lr * (mw[j] / c1) / (Math.sqrt(vw[j] / c2) + eps);
            }
            mb = b1 * mb + (1 - b1) * gb;
            vb = b2 * vb + (1 - b2) * gb * gb;
            b -= lr * (mb / c1) / (Math.sqrt(vb / c2) + eps);
        }
        m.weights = w;
        m.bias = b;
        return m;
    }
}
