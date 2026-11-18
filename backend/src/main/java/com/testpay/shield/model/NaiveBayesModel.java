package com.testpay.shield.model;

import java.util.*;

/**
 * Bernoulli naive Bayes over the binary / categorical features (numeric features are ignored).
 * Kept as a compared-and-rejected approach: fast and simple, but its independence assumption makes
 * the probabilities over-confident, so it calibrates badly.
 */
public class NaiveBayesModel implements ProbModel {
    public double prior;
    /** feature -> [log P(x=1|y=1) - log P(x=0|y=1)  -  (same for y=0)] */
    public Map<String, Double> deltaLogOdds = new HashMap<>();
    /** Sum over features of log P(x=0|y=1) - log P(x=0|y=0). */
    public double baseLogOdds;

    public static NaiveBayesModel fit(List<Map<String, Double>> rows, int[] y, int minCount) {
        NaiveBayesModel m = new NaiveBayesModel();
        int n = rows.size();
        double n1 = 0;
        for (int v : y) n1 += v;
        double n0 = n - n1;
        m.prior = Math.log((n1 + 1) / (n0 + 1));
        Map<String, double[]> c = new HashMap<>(); // [count|y=1, count|y=0]
        for (int i = 0; i < n; i++) {
            for (Map.Entry<String, Double> e : rows.get(i).entrySet()) {
                if (e.getKey().startsWith("num:") || e.getValue() == 0) continue;
                double[] a = c.computeIfAbsent(e.getKey(), k -> new double[2]);
                if (y[i] == 1) a[0]++; else a[1]++;
            }
        }
        double base = 0;
        for (Map.Entry<String, double[]> e : c.entrySet()) {
            double[] a = e.getValue();
            if (a[0] + a[1] < minCount) continue;
            double p1 = (a[0] + 1) / (n1 + 2), p0 = (a[1] + 1) / (n0 + 2);
            double absent = Math.log(1 - p1) - Math.log(1 - p0);
            double present = Math.log(p1) - Math.log(p0);
            base += absent;
            m.deltaLogOdds.put(e.getKey(), present - absent);
        }
        m.baseLogOdds = base;
        return m;
    }

    @Override
    public double predict(Map<String, Double> x) {
        double z = prior + baseLogOdds;
        for (Map.Entry<String, Double> e : x.entrySet()) {
            if (e.getValue() == 0) continue;
            Double d = deltaLogOdds.get(e.getKey());
            if (d != null) z += d;
        }
        return LogisticModel.sigmoid(z);
    }
}
