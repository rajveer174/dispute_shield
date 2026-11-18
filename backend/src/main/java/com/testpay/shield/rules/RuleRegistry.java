package com.testpay.shield.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * Loads evidence rules and dispute reasons from JSON. Adding a new evidence rule or dispute reason
 * means adding one entry to the JSON (and retraining so the model learns its effect); no code changes.
 * A reason not in the registry still works: it gets a generic label and the model treats it as unseen.
 */
@Component
public class RuleRegistry {

    /**
     * @param availabilityFeature optional 0/1 feature that is 1 when this evidence could not have been collected
     *                            (e.g. completion signature before the team adopted it). Such jobs are not counted
     *                            as "missing" and get {@code unavailableAction} instead of {@code action}.
     */
    public record EvidenceRule(String id, String label, String feature, String stage,
                               boolean fixableAfterPayment, String action, List<String> helpsReasons,
                               String availabilityFeature, String unavailableAction) {

        public boolean unavailable(Map<String, Double> x) {
            return availabilityFeature != null && x.getOrDefault(availabilityFeature, 0.0) >= 1;
        }

        /** Copy of {@code x} with this evidence present (and made available). */
        public Map<String, Double> withEvidence(Map<String, Double> x) {
            Map<String, Double> fixed = new HashMap<>(x);
            fixed.put(feature, 1.0);
            if (availabilityFeature != null) fixed.put(availabilityFeature, 0.0);
            if (feature.equals("inv:has_after_photo") || feature.equals("inv:has_before_photo")) {
                fixed.merge("num:photos_total", 1.0, Double::sum);
            }
            return fixed;
        }
    }

    public record DisputeReason(String id, String label, String description, String guidance) {}

    private final List<EvidenceRule> evidenceRules;
    private final Map<String, DisputeReason> reasons;

    public RuleRegistry() {
        ObjectMapper om = new ObjectMapper();
        this.evidenceRules = List.copyOf(read(om, "rules/evidence-rules.json", new TypeReference<List<EvidenceRule>>() {}));
        Map<String, DisputeReason> m = new LinkedHashMap<>();
        for (DisputeReason r : read(om, "rules/dispute-reasons.json", new TypeReference<List<DisputeReason>>() {})) {
            m.put(r.id(), r);
        }
        this.reasons = Collections.unmodifiableMap(m);
    }

    private static <T> T read(ObjectMapper om, String path, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return om.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public List<EvidenceRule> evidenceRules() {
        return evidenceRules;
    }

    public Collection<DisputeReason> reasons() {
        return reasons.values();
    }

    public DisputeReason reason(String id) {
        if (id == null) return new DisputeReason("unknown", "Unknown", "No reason given.", "");
        DisputeReason r = reasons.get(id);
        if (r != null) return r;
        String label = Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' ');
        return new DisputeReason(id, label, "Reason not in the registry.", "Submit all evidence you have.");
    }
}
