package com.testpay.shield.web;

import com.testpay.shield.model.ModelBundle;
import com.testpay.shield.rules.RuleRegistry;
import com.testpay.shield.scoring.ModelStore;
import com.testpay.shield.scoring.Predictions.*;
import com.testpay.shield.session.UploadSession;
import com.testpay.shield.training.HistoryStats;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Builds the team and portfolio views from history (months 1-22) and the current upload session. */
@Service
@Profile("!train")
public class ViewService {

    private final ModelStore store;
    private final RuleRegistry rules;

    public ViewService(ModelStore store, RuleRegistry rules) {
        this.store = store;
        this.rules = rules;
    }

    public List<Map<String, Object>> teams(Optional<UploadSession> session) {
        ModelBundle b = store.bundle();
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        for (HistoryStats.TeamStats t : b.history.teams.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("teamId", t.teamId);
            r.put("profession", t.profession);
            r.put("state", t.state);
            r.put("historyDisputes", t.disputes);
            r.put("historyUsdLost", t.usdLost);
            r.put("uploadedPayments", 0);
            r.put("uploadedHighRisk", 0);
            r.put("uploadedExpectedLossUsd", 0.0);
            rows.put(t.teamId, r);
        }
        session.ifPresent(s -> {
            for (PaymentPrediction p : s.payments) {
                Map<String, Object> r = rows.computeIfAbsent(p.teamId, k -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("teamId", k); n.put("profession", null); n.put("state", null);
                    n.put("historyDisputes", 0); n.put("historyUsdLost", 0.0);
                    n.put("uploadedPayments", 0); n.put("uploadedHighRisk", 0); n.put("uploadedExpectedLossUsd", 0.0);
                    return n;
                });
                r.put("uploadedPayments", (Integer) r.get("uploadedPayments") + 1);
                if ("high".equals(p.disputeRiskTier)) r.put("uploadedHighRisk", (Integer) r.get("uploadedHighRisk") + 1);
                r.put("uploadedExpectedLossUsd", round2((Double) r.get("uploadedExpectedLossUsd") + nz(p.expectedLossUsd)));
            }
        });
        return new ArrayList<>(rows.values());
    }

    public Map<String, Object> team(String teamId, Optional<UploadSession> session) {
        ModelBundle b = store.bundle();
        HistoryStats.TeamStats hist = b.history.teams.get(teamId);
        List<PaymentPrediction> pays = session.map(s -> s.payments.stream().filter(p -> teamId.equals(p.teamId)).toList()).orElse(List.of());
        List<DisputePrediction> disps = session.map(s -> s.disputes.stream().filter(d -> teamId.equals(d.teamId)).toList()).orElse(List.of());
        if (hist == null && pays.isEmpty() && disps.isEmpty()) throw new NoSuchElementException("Team " + teamId + " not found");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("history", hist);

        Map<String, Object> up = new LinkedHashMap<>();
        up.put("payments", pays.size());
        up.put("volumeUsd", round2(pays.stream().mapToDouble(p -> p.amountUsd).sum()));
        up.put("expectedLossUsd", round2(pays.stream().mapToDouble(p -> nz(p.expectedLossUsd)).sum()));
        Map<String, Long> tiers = new LinkedHashMap<>();
        for (String t : List.of("high", "medium", "low")) tiers.put(t, pays.stream().filter(p -> t.equals(p.disputeRiskTier)).count());
        up.put("tiers", tiers);
        up.put("disputes", disps.size());
        up.put("disputedUsd", round2(disps.stream().mapToDouble(d -> d.amountUsd).sum()));
        up.put("fight", disps.stream().filter(d -> "fight".equals(d.recommendation)).count());
        up.put("accept", disps.stream().filter(d -> "accept".equals(d.recommendation)).count());
        up.put("expectedWins", round2(disps.stream().mapToDouble(d -> nz(d.winProbIfResponded)).sum()));
        out.put("upload", up);

        // Gaps in the uploaded invoices, ranked by dollars they would protect.
        Map<String, double[]> gapAgg = new LinkedHashMap<>(); // label -> [missing count, valueUsd]
        Map<String, String> gapAction = new HashMap<>();
        for (PaymentPrediction p : pays) {
            for (String m : p.missingEvidence) gapAgg.computeIfAbsent(m, k -> new double[2])[0]++;
            for (Action a : p.actions) {
                gapAgg.computeIfAbsent(a.label, k -> new double[2])[1] += a.valueUsd;
                gapAction.put(a.label, a.action);
            }
        }
        List<Map<String, Object>> uploadGaps = new ArrayList<>();
        gapAgg.forEach((label, v) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("label", label);
            g.put("action", gapAction.getOrDefault(label, ""));
            g.put("missingShare", pays.isEmpty() ? 0 : v[0] / pays.size());
            g.put("valueUsd", round2(v[1]));
            uploadGaps.add(g);
        });
        uploadGaps.sort(Comparator.comparingDouble((Map<String, Object> g) -> (Double) g.get("valueUsd")).reversed());
        out.put("uploadGaps", uploadGaps);

        out.put("payments", pays.stream().sorted(Comparator.comparingDouble((PaymentPrediction p) -> -nz(p.expectedLossUsd))).toList());
        out.put("disputes", disps);
        return out;
    }

    public Map<String, Object> portfolio(Optional<UploadSession> session) {
        ModelBundle b = store.bundle();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("payments", b.history.payments);
        hist.put("volumeUsd", round2(b.history.volumeUsd));
        hist.put("disputes", b.history.disputes);
        hist.put("winRate", b.history.winRate);
        hist.put("usdLost", round2(b.history.usdLost));
        hist.put("byReason", b.history.byReason);
        hist.put("byMonth", b.history.byMonth);
        out.put("history", hist);

        session.ifPresent(s -> {
            double fee = (Double) b.assumptions.getOrDefault("dispute_fee_usd", 25.0);
            Map<String, Object> up = new LinkedHashMap<>();
            Map<String, List<DisputePrediction>> byReason = s.disputes.stream()
                    .collect(Collectors.groupingBy(d -> String.valueOf(d.reason), LinkedHashMap::new, Collectors.toList()));
            List<Map<String, Object>> reasons = new ArrayList<>();
            byReason.forEach((reason, list) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("reason", reason);
                r.put("label", rules.reason(reason).label());
                r.put("disputes", list.size());
                r.put("disputedUsd", round2(list.stream().mapToDouble(d -> d.amountUsd).sum()));
                r.put("predictedWinRate", list.stream().mapToDouble(d -> nz(d.winProb)).average().orElse(0));
                r.put("expectedUsdLost", round2(list.stream().mapToDouble(d -> (1 - nz(d.winProbIfResponded)) * (d.amountUsd + fee)).sum()));
                r.put("fight", list.stream().filter(d -> "fight".equals(d.recommendation)).count());
                r.put("accept", list.stream().filter(d -> "accept".equals(d.recommendation)).count());
                reasons.add(r);
            });
            reasons.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("expectedUsdLost")).reversed());
            up.put("byReason", reasons);
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (String t : List.of("high", "medium", "low")) {
                List<PaymentPrediction> l = s.payments.stream().filter(p -> t.equals(p.disputeRiskTier)).toList();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("tier", t);
                r.put("payments", l.size());
                r.put("volumeUsd", round2(l.stream().mapToDouble(p -> p.amountUsd).sum()));
                r.put("expectedLossUsd", round2(l.stream().mapToDouble(p -> nz(p.expectedLossUsd)).sum()));
                r.put("disputed", l.stream().filter(p -> p.disputed).count());
                tiers.add(r);
            }
            up.put("byTier", tiers);
            up.put("payments", s.payments.size());
            up.put("disputes", s.disputes.size());
            out.put("upload", up);
        });
        return out;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
