package com.testpay.shield.scoring;

import com.testpay.shield.model.LogisticModel;

import java.util.*;

/** Turns model weights into plain-language "what raises / lowers this prediction" lists. */
public final class ModelInsights {
    private ModelInsights() {}

    private static final double MIN_WEIGHT = 0.1;

    /**
     * Top factors by weight. For yes/no features the weight is the change when the item is present;
     * for numeric features it is the change for one typical step (one standard deviation) higher.
     */
    public static Map<String, List<Map<String, Object>>> drivers(LogisticModel m, int perSide) {
        List<Map<String, Object>> up = new ArrayList<>(), down = new ArrayList<>();
        if (m != null) {
            Integer[] order = new Integer[m.features.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingDouble(i -> -Math.abs(m.weights[i])));
            for (int i : order) {
                double w = m.weights[i];
                if (Math.abs(w) < MIN_WEIGHT) break;
                String f = m.features.get(i);
                List<Map<String, Object>> side = w > 0 ? up : down;
                if (side.size() >= perSide) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("feature", f);
                d.put("label", describe(f));
                d.put("strength", Math.abs(w) >= 0.8 ? "strong" : Math.abs(w) >= 0.35 ? "moderate" : "slight");
                d.put("weight", Math.round(w * 1000) / 1000.0);
                side.add(d);
            }
        }
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("raises", up);
        out.put("lowers", down);
        return out;
    }

    private static String describe(String f) {
        return FeatureLabels.phrase(f);
    }
}
