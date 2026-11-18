package com.testpay.shield.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline: historical rate within one categorical segment (e.g. payment method or dispute reason),
 * shrunk toward the overall rate. Used only for comparison in the training report.
 */
public class SegmentRateModel implements ProbModel {
    public String prefix;
    public double globalRate;
    public Map<String, Double> rates = new HashMap<>();

    public static SegmentRateModel fit(String prefix, List<Map<String, Double>> rows, int[] y, double alpha) {
        SegmentRateModel m = new SegmentRateModel();
        m.prefix = prefix;
        double pos = 0;
        for (int v : y) pos += v;
        m.globalRate = rows.isEmpty() ? 0 : pos / rows.size();
        Map<String, double[]> agg = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String seg = segment(prefix, rows.get(i));
            double[] a = agg.computeIfAbsent(seg, k -> new double[2]);
            a[0] += y[i];
            a[1]++;
        }
        agg.forEach((k, a) -> m.rates.put(k, (a[0] + alpha * m.globalRate) / (a[1] + alpha)));
        return m;
    }

    static String segment(String prefix, Map<String, Double> row) {
        if (prefix == null) return "all";
        for (Map.Entry<String, Double> e : row.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() != 0) return e.getKey();
        }
        return "none";
    }

    @Override
    public double predict(Map<String, Double> features) {
        return rates.getOrDefault(segment(prefix, features), globalRate);
    }
}
