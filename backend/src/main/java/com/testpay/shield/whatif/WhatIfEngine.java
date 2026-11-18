package com.testpay.shield.whatif;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.features.FeatureExtractor;
import com.testpay.shield.features.JobContext;
import com.testpay.shield.model.LogisticModel;
import com.testpay.shield.model.Metrics;
import com.testpay.shield.training.Job;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Counterfactual estimate of a policy over months 1-22.
 *
 * <p>For every historical payment we start from what actually happened (disputed or not, lost or not)
 * and adjust it:
 * <ol>
 *   <li><b>Lost jobs</b>: a fraction f of the jobs in the policy's side-effect segment disappear with
 *       their disputes and losses.</li>
 *   <li><b>Behaviour change</b> on affected jobs that stay: the dispute count is scaled by
 *       r = P(dispute | changed job) / P(dispute | actual job) from the risk model, and the loss
 *       indicator is shifted by the change in win probability from the payment-win model.</li>
 * </ol>
 * Anchoring on observed outcomes keeps the baseline exactly equal to history; the models only
 * contribute relative changes.
 *
 * <p>Range: team-level (cluster) bootstrap for sampling noise, times a causal discount k ~ U[low, high]
 * on the behaviour part (missing evidence is partly a marker of a careless team, not only a cause),
 * times the uncertainty on the job-loss fraction. Point estimate uses the middle k.
 */
@Component
public class WhatIfEngine {

    private static final String[] METRICS = {"disputes", "disputes_lost", "usd_lost"};

    private final ShieldProperties props;

    public WhatIfEngine(ShieldProperties props) {
        this.props = props;
    }

    public PolicyResult evaluate(WhatIfPolicy policy, List<Job> jobs, LogisticModel risk, LogisticModel win) {
        double fee = props.getEconomics().getDisputeFeeUsd();
        boolean withdrawnWon = props.getTraining().isWithdrawnCountsAsWon();
        int n = jobs.size();
        double[][] side = new double[3][n];
        double[][] model = new double[3][n];
        double[] base = new double[3];
        Map<String, Integer> teamIndex = new HashMap<>();
        int[] team = new int[n];

        PolicyResult res = new PolicyResult();
        res.policy = policy.id();
        res.label = policy.label();
        res.description = policy.description();
        res.assumptions = new ArrayList<>(policy.assumptions());

        for (int i = 0; i < n; i++) {
            Job job = jobs.get(i);
            JobContext c = job.ctx();
            team[i] = teamIndex.computeIfAbsent(String.valueOf(c.payment.teamId), k -> teamIndex.size());
            boolean disputed = job.dispute() != null;
            double amount = disputed ? job.dispute().amountUsd : c.payment.amountUsd;
            double w0 = Double.NaN;

            double baseD = disputed ? 1 : 0;
            double baseL = 0;
            if (disputed) {
                String o = job.dispute().outcome;
                if ("lost".equals(o)) baseL = 1;
                else if ("won".equals(o)) baseL = 0;
                else if ("withdrawn".equals(o)) baseL = withdrawnWon ? 0 : 1;
                else { // open: expected loss from the model
                    w0 = win.predict(FeatureExtractor.paymentStage(c));
                    baseL = 1 - w0;
                }
            }
            double baseU = baseL * (amount + fee);
            base[0] += baseD; base[1] += baseL; base[2] += baseU;

            double f = policy.jobLossFraction(c);
            res.jobsLost += f;
            side[0][i] = -f * baseD;
            side[1][i] = -f * baseL;
            side[2][i] = -f * baseU;

            if (policy.affects(c)) {
                res.affectedPayments++;
                res.affectedVolumeUsd += c.payment.amountUsd;
                if (disputed) res.affectedDisputes++;
                if (!disputed) continue; // anchored estimator: E[d * r] = P(dispute | changed job)
                JobContext cf = c.copy();
                policy.apply(cf);
                Map<String, Double> x0 = FeatureExtractor.paymentStage(c);
                Map<String, Double> x1 = FeatureExtractor.paymentStage(cf);
                double p0 = Math.max(risk.predict(x0), 1e-6);
                double r = Math.min(Math.max(risk.predict(x1) / p0, 0), 5);
                if (Double.isNaN(w0)) w0 = win.predict(x0);
                double w1 = win.predict(x1);
                double lossCf = Math.min(Math.max(baseL - (w1 - w0), 0), 1);
                double keep = 1 - f;
                model[0][i] = keep * (r - 1);
                model[1][i] = keep * (r * lossCf - baseL);
                model[2][i] = keep * (r * lossCf * (amount + fee) - baseU);
            }
        }

        double kLow = props.getTraining().getCausalDiscountLow();
        double kHigh = props.getTraining().getCausalDiscountHigh();
        double kMid = (kLow + kHigh) / 2;
        double[] lossMult = policy.jobLossUncertainty();

        // Per-team sums for the cluster bootstrap.
        int t = teamIndex.size();
        double[][] teamSide = new double[3][t], teamModel = new double[3][t];
        for (int i = 0; i < n; i++) {
            for (int m = 0; m < 3; m++) {
                teamSide[m][team[i]] += side[m][i];
                teamModel[m][team[i]] += model[m][i];
            }
        }
        double[] sideTotal = new double[3], modelTotal = new double[3];
        for (int m = 0; m < 3; m++) {
            for (int j = 0; j < t; j++) { sideTotal[m] += teamSide[m][j]; modelTotal[m] += teamModel[m][j]; }
        }

        int bootstraps = props.getTraining().getBootstrapSamples();
        Random rnd = new Random(props.getTraining().getSeed() + policy.id().hashCode());
        double[][] draws = new double[3][bootstraps];
        for (int b = 0; b < bootstraps; b++) {
            double[] s = new double[3], md = new double[3];
            for (int j = 0; j < t; j++) {
                int pick = rnd.nextInt(t);
                for (int m = 0; m < 3; m++) { s[m] += teamSide[m][pick]; md[m] += teamModel[m][pick]; }
            }
            double k = kLow + rnd.nextDouble() * (kHigh - kLow);
            double lm = lossMult[0] + rnd.nextDouble() * (lossMult[1] - lossMult[0]);
            for (int m = 0; m < 3; m++) draws[m][b] = lm * s[m] + k * md[m];
        }

        for (int m = 0; m < 3; m++) {
            double est = sideTotal[m] + kMid * modelTotal[m];
            double lo = Metrics.quantile(draws[m], 0.05);
            double hi = Metrics.quantile(draws[m], 0.95);
            res.metrics.add(new PolicyResult.MetricChange(METRICS[m], round(base[m]), round(est),
                    round(Math.min(lo, est)), round(Math.max(hi, est)), round(sideTotal[m]), round(kMid * modelTotal[m])));
        }

        res.method = List.of(
                "Start from what actually happened in months 1-22 on every payment.",
                "Jobs lost to the policy take their disputes and losses with them.",
                "On affected jobs that stay, disputes scale by the risk model's ratio P(dispute | changed) / P(dispute | actual), "
                        + "and the loss indicator moves by the payment-win model's change in win probability.",
                String.format("Model-based effects are discounted by k between %.1f and %.1f (point %.2f) because missing "
                        + "evidence partly marks careless teams rather than causing losses.", kLow, kHigh, kMid),
                String.format("Range: 5th-95th percentile of %d team-level bootstrap draws with k and the job-loss "
                        + "fraction drawn at random.", bootstraps));
        confidence(res);
        return res;
    }

    private static void confidence(PolicyResult res) {
        PolicyResult.MetricChange usd = res.metrics.get(2);
        double width = usd.high - usd.low;
        double rel = width / Math.max(Math.abs(usd.estimatedChange), 1e-9);
        boolean crossesZero = usd.low < 0 && usd.high > 0;
        if (res.affectedDisputes < 20) {
            res.confidence = "low";
            res.confidenceReason = "Only " + res.affectedDisputes + " historical disputes were on affected jobs.";
        } else if (crossesZero) {
            res.confidence = "low";
            res.confidenceReason = "The range includes zero: the data cannot tell whether dollars lost go up or down.";
        } else if (rel < 0.75) {
            res.confidence = "high";
            res.confidenceReason = String.format("Narrow range relative to the effect (%d affected disputes).", res.affectedDisputes);
        } else if (rel < 1.75) {
            res.confidence = "medium";
            res.confidenceReason = "Direction is clear but the size is uncertain, mainly from the confounding discount.";
        } else {
            res.confidence = "low";
            res.confidenceReason = "Range is wide relative to the effect.";
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
