package com.testpay.shield.model;

import java.util.*;

/** Validation metrics: discrimination (AUC), accuracy of probabilities (Brier, log loss) and calibration (ECE). */
public final class Metrics {
    private Metrics() {}

    public record Bin(double meanPredicted, double observedRate, int count) {}

    public record Report(String approach, int rows, double positiveRate, double auc, double brier,
                         double logLoss, double ece, List<Bin> calibration) {}

    public static Report evaluate(String approach, double[] p, int[] y) {
        int n = p.length;
        double pos = 0, brier = 0, ll = 0;
        for (int i = 0; i < n; i++) {
            pos += y[i];
            brier += (p[i] - y[i]) * (p[i] - y[i]);
            double q = Math.min(Math.max(p[i], 1e-6), 1 - 1e-6);
            ll += -(y[i] * Math.log(q) + (1 - y[i]) * Math.log(1 - q));
        }
        List<Bin> bins = calibration(p, y, 10);
        double ece = 0;
        for (Bin b : bins) ece += (double) b.count() / Math.max(n, 1) * Math.abs(b.meanPredicted() - b.observedRate());
        return new Report(approach, n, n == 0 ? 0 : pos / n, auc(p, y),
                n == 0 ? 0 : brier / n, n == 0 ? 0 : ll / n, ece, bins);
    }

    public static double auc(double[] p, int[] y) {
        int n = p.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        double rankSumPos = 0;
        long nPos = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && p[order[j + 1]] == p[order[i]]) j++;
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) if (y[order[k]] == 1) { rankSumPos += avgRank; nPos++; }
            i = j + 1;
        }
        long nNeg = n - nPos;
        if (nPos == 0 || nNeg == 0) return Double.NaN;
        return (rankSumPos - nPos * (nPos + 1) / 2.0) / ((double) nPos * nNeg);
    }

    /** Equal-count bins by predicted probability. */
    public static List<Bin> calibration(double[] p, int[] y, int nBins) {
        int n = p.length;
        List<Bin> out = new ArrayList<>();
        if (n == 0) return out;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        int per = Math.max(1, (int) Math.ceil((double) n / nBins));
        for (int start = 0; start < n; start += per) {
            int end = Math.min(n, start + per);
            double sp = 0, sy = 0;
            for (int k = start; k < end; k++) { sp += p[order[k]]; sy += y[order[k]]; }
            int c = end - start;
            out.add(new Bin(sp / c, sy / c, c));
        }
        return out;
    }

    public static double quantile(double[] values, double q) {
        if (values.length == 0) return 0;
        double[] s = values.clone();
        Arrays.sort(s);
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }
}
