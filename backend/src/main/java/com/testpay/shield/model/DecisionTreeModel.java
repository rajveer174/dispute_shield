package com.testpay.shield.model;

import java.util.*;

/**
 * Depth-limited binary decision tree (log-loss splits, smoothed leaf rates). Experiment only: it is compared
 * against the other approaches when training runs with --experiments, and is not used by the app. Rejected
 * because it ranks no better than logistic regression, its probabilities come in a few coarse steps, and a
 * deep tree is hard to explain per feature.
 */
public class DecisionTreeModel implements ProbModel {

    static final class Node {
        String feature;
        double threshold;
        Node low, high;
        double p;
    }

    private Node root;
    public int leaves;

    public static DecisionTreeModel fit(List<Map<String, Double>> X, int[] y, int maxDepth, int minLeaf) {
        int n = X.size();
        double prior = Arrays.stream(y).sum() / (double) Math.max(1, n);
        // Candidate splits: binary features at 0.5, numeric features at their 20/40/60/80th percentiles.
        Map<String, List<Double>> values = new HashMap<>();
        for (Map<String, Double> row : X) {
            for (Map.Entry<String, Double> e : row.entrySet()) {
                if (e.getValue() != 0) values.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        List<Object[]> splits = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : values.entrySet()) {
            List<Double> v = e.getValue();
            if (v.size() < minLeaf || n - v.size() < 0) continue;
            boolean binary = v.stream().allMatch(d -> d == 1.0);
            if (binary) {
                if (n - v.size() >= minLeaf) splits.add(new Object[]{e.getKey(), 0.5});
                continue;
            }
            double[] all = new double[n];
            int k = 0;
            for (Map<String, Double> row : X) all[k++] = row.getOrDefault(e.getKey(), 0.0);
            Set<Double> thr = new TreeSet<>();
            for (double q : new double[]{0.2, 0.4, 0.6, 0.8}) thr.add(Metrics.quantile(all, q));
            for (double t : thr) splits.add(new Object[]{e.getKey(), t});
        }
        DecisionTreeModel m = new DecisionTreeModel();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        m.root = m.grow(X, y, idx, splits, 0, maxDepth, minLeaf, prior);
        return m;
    }

    private Node grow(List<Map<String, Double>> X, int[] y, int[] idx, List<Object[]> splits, int depth,
                      int maxDepth, int minLeaf, double prior) {
        Node node = new Node();
        double wins = 0;
        for (int i : idx) wins += y[i];
        node.p = (wins + 10 * prior) / (idx.length + 10);
        if (depth >= maxDepth || idx.length < 2 * minLeaf || wins == 0 || wins == idx.length) { leaves++; return node; }
        double parent = entropy(wins, idx.length);
        double bestGain = 1e-4;
        Object[] best = null;
        for (Object[] s : splits) {
            String f = (String) s[0];
            double t = (Double) s[1];
            double hn = 0, hw = 0;
            for (int i : idx) if (X.get(i).getOrDefault(f, 0.0) > t) { hn++; hw += y[i]; }
            double ln = idx.length - hn, lw = wins - hw;
            if (hn < minLeaf || ln < minLeaf) continue;
            double gain = parent - (hn * entropy(hw, hn) + ln * entropy(lw, ln)) / idx.length;
            if (gain > bestGain) { bestGain = gain; best = s; }
        }
        if (best == null) { leaves++; return node; }
        node.feature = (String) best[0];
        node.threshold = (Double) best[1];
        int[] hi = Arrays.stream(idx).filter(i -> X.get(i).getOrDefault(node.feature, 0.0) > node.threshold).toArray();
        int[] lo = Arrays.stream(idx).filter(i -> X.get(i).getOrDefault(node.feature, 0.0) <= node.threshold).toArray();
        node.high = grow(X, y, hi, splits, depth + 1, maxDepth, minLeaf, prior);
        node.low = grow(X, y, lo, splits, depth + 1, maxDepth, minLeaf, prior);
        return node;
    }

    private static double entropy(double w, double n) {
        if (n == 0 || w == 0 || w == n) return 0;
        double p = w / n;
        return -(p * Math.log(p) + (1 - p) * Math.log(1 - p));
    }

    @Override
    public double predict(Map<String, Double> x) {
        Node n = root;
        while (n.feature != null) n = x.getOrDefault(n.feature, 0.0) > n.threshold ? n.high : n.low;
        return n.p;
    }
}
