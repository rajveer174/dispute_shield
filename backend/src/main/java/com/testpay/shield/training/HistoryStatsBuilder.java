package com.testpay.shield.training;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.Dispute;
import com.testpay.shield.domain.Records.Team;
import com.testpay.shield.features.FeatureExtractor;
import com.testpay.shield.model.LogisticModel;
import com.testpay.shield.rules.RuleRegistry;
import com.testpay.shield.rules.RuleRegistry.EvidenceRule;
import com.testpay.shield.training.HistoryStats.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Builds the months 1-22 aggregates shown in the portfolio and team views. */
@Component
public class HistoryStatsBuilder {

    private static final int UPLIFT_SAMPLE = 4000;

    private final RuleRegistry rules;
    private final ShieldProperties props;

    public HistoryStatsBuilder(RuleRegistry rules, ShieldProperties props) {
        this.rules = rules;
        this.props = props;
    }

    public HistoryStats build(Dataset ds, List<Job> jobs, LogisticModel paymentWin) {
        double fee = props.getEconomics().getDisputeFeeUsd();
        boolean withdrawnWon = props.getTraining().isWithdrawnCountsAsWon();
        HistoryStats h = new HistoryStats();

        Map<String, ReasonStats> byReason = new LinkedHashMap<>();
        TreeMap<Integer, MonthStats> byMonth = new TreeMap<>();
        Map<String, TeamStats> teams = new TreeMap<>();
        for (Team t : ds.teams.values()) teams.put(t.teamId, newTeam(t));

        int nRules = rules.evidenceRules().size();
        Map<String, int[]> teamMissing = new HashMap<>();   // team -> missing count per rule
        Map<String, int[]> teamLostMissing = new HashMap<>();
        // Jobs where the evidence could have been collected (e.g. signature only after the team adopted it).
        Map<String, int[]> teamEligible = new HashMap<>();
        int[] platformMissing = new int[nRules];
        int[] platformEligible = new int[nRules];
        double[] upliftSum = new double[nRules];
        int[] upliftN = new int[nRules];
        Random rnd = new Random(props.getTraining().getSeed());
        double sampleProb = Math.min(1.0, (double) UPLIFT_SAMPLE / Math.max(1, jobs.size()));

        for (Job j : jobs) {
            String teamId = j.ctx().payment.teamId;
            TeamStats ts = teams.computeIfAbsent(teamId, k -> { TeamStats x = new TeamStats(); x.teamId = k; return x; });
            double amt = j.ctx().payment.amountUsd;
            ts.payments++;
            ts.volumeUsd += amt;
            h.payments++;
            h.volumeUsd += amt;
            MonthStats ms = byMonth.computeIfAbsent(j.month(), this::newMonth);
            ms.payments++;
            ms.volumeUsd += amt;

            Map<String, Double> x = FeatureExtractor.paymentStage(j.ctx());
            int[] tm = teamMissing.computeIfAbsent(teamId, k -> new int[nRules]);
            int[] tl = teamLostMissing.computeIfAbsent(teamId, k -> new int[nRules]);
            int[] te = teamEligible.computeIfAbsent(teamId, k -> new int[nRules]);
            boolean lost = j.dispute() != null && isLost(j.dispute().outcome, withdrawnWon);
            boolean sample = rnd.nextDouble() < sampleProb;
            double base = sample ? paymentWin.predict(x) : 0;
            for (int r = 0; r < nRules; r++) {
                EvidenceRule rule = rules.evidenceRules().get(r);
                if (rule.unavailable(x)) continue; // not a gap: the team could not have collected it yet
                te[r]++;
                platformEligible[r]++;
                if (x.getOrDefault(rule.feature(), 0.0) >= 1) continue;
                tm[r]++;
                platformMissing[r]++;
                if (lost) tl[r]++;
                if (sample) {
                    upliftSum[r] += paymentWin.predict(rule.withEvidence(x)) - base;
                    upliftN[r]++;
                }
            }

            Dispute d = j.dispute();
            if (d == null) continue;
            ReasonStats rs = byReason.computeIfAbsent(String.valueOf(d.reason), this::newReason);
            rs.disputes++;
            rs.disputedUsd += d.amountUsd;
            ts.disputes++;
            ts.disputesByReason.merge(String.valueOf(d.reason), 1, Integer::sum);
            ms.disputes++;
            h.disputes++;
            switch (String.valueOf(d.outcome)) {
                case "won" -> { rs.won++; ts.won++; h.won++; }
                case "lost" -> { rs.lost++; ts.lost++; h.lost++; }
                case "withdrawn" -> { rs.withdrawn++; ts.withdrawn++; if (withdrawnWon) h.won++; else h.lost++; }
                default -> { rs.open++; ts.open++; }
            }
            if (lost) {
                double loss = d.amountUsd + fee;
                rs.usdLost += loss;
                ts.usdLost += loss;
                ms.usdLost += loss;
                ms.lost++;
                h.usdLost += loss;
            }
        }

        for (ReasonStats rs : byReason.values()) {
            int wins = rs.won + (withdrawnWon ? rs.withdrawn : 0);
            int resolved = rs.won + rs.lost + rs.withdrawn;
            rs.winRate = resolved == 0 ? 0 : (double) wins / resolved;
        }
        h.winRate = h.won + h.lost == 0 ? 0 : (double) h.won / (h.won + h.lost);

        double[] upliftAvg = new double[nRules];
        for (int r = 0; r < nRules; r++) {
            upliftAvg[r] = upliftN[r] == 0 ? 0 : upliftSum[r] / upliftN[r];
            h.platformMissingRates.put(rules.evidenceRules().get(r).id(),
                    platformEligible[r] == 0 ? 0 : (double) platformMissing[r] / platformEligible[r]);
        }

        for (TeamStats ts : teams.values()) {
            int wins = ts.won + (withdrawnWon ? ts.withdrawn : 0);
            int resolved = ts.won + ts.lost + ts.withdrawn;
            ts.winRate = resolved == 0 ? 0 : (double) wins / resolved;
            ts.disputeRate = ts.payments == 0 ? 0 : (double) ts.disputes / ts.payments;
            int[] tm = teamMissing.getOrDefault(ts.teamId, new int[nRules]);
            int[] tl = teamLostMissing.getOrDefault(ts.teamId, new int[nRules]);
            int[] te = teamEligible.getOrDefault(ts.teamId, new int[nRules]);
            for (int r = 0; r < nRules; r++) {
                EvidenceRule rule = rules.evidenceRules().get(r);
                Gap g = new Gap();
                g.ruleId = rule.id();
                g.label = rule.label();
                g.action = rule.action();
                g.teamMissingRate = te[r] == 0 ? 0 : (double) tm[r] / te[r];
                g.eligibleJobs = te[r];
                g.platformMissingRate = h.platformMissingRates.get(rule.id());
                g.lostDisputesMissing = tl[r];
                g.winUplift = upliftAvg[r];
                g.impact = g.teamMissingRate * Math.max(upliftAvg[r], 0);
                ts.gaps.add(g);
            }
            ts.gaps.sort(Comparator.comparingDouble((Gap g) -> g.impact).reversed());
        }

        h.byReason = new ArrayList<>(byReason.values());
        h.byReason.sort(Comparator.comparingDouble((ReasonStats r) -> r.usdLost).reversed());
        h.byMonth = new ArrayList<>(byMonth.values());
        h.teams = teams;
        return h;
    }

    static boolean isLost(String outcome, boolean withdrawnWon) {
        return "lost".equals(outcome) || (!withdrawnWon && "withdrawn".equals(outcome));
    }

    private TeamStats newTeam(Team t) {
        TeamStats ts = new TeamStats();
        ts.teamId = t.teamId;
        ts.profession = t.profession;
        ts.state = t.state;
        ts.teamSize = t.teamSize;
        return ts;
    }

    private ReasonStats newReason(String reason) {
        ReasonStats rs = new ReasonStats();
        rs.reason = reason;
        rs.label = rules.reason(reason).label();
        return rs;
    }

    private MonthStats newMonth(int month) {
        MonthStats ms = new MonthStats();
        ms.month = month;
        YearMonth ym = YearMonth.from(LocalDate.of(2024, 11, 1)).plusMonths(month - 1L);
        ms.label = ym.toString();
        return ms;
    }
}
