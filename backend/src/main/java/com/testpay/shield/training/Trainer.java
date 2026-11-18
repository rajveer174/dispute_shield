package com.testpay.shield.training;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.*;
import com.testpay.shield.features.FeatureExtractor;
import com.testpay.shield.features.HistoryIndex;
import com.testpay.shield.features.JobContext;
import com.testpay.shield.features.ReleaseCalendar;
import com.testpay.shield.ingest.CsvLoader;
import com.testpay.shield.rules.WinRules;
import com.testpay.shield.model.*;
import com.testpay.shield.whatif.WhatIfEngine;
import com.testpay.shield.whatif.WhatIfPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Trains every model on months 1-22 and assembles the bundle the app serves.
 *
 * <p>Validation scheme: time-based holdout. For each prediction the last N months (by event time) are
 * held out, models are fit on the earlier months and scored on the later ones. This mirrors the real
 * test (train on 1-22, score 23-24) and exposes drift that a random split would hide. The chosen
 * approach is then refit on all eligible months.
 */
@Component
public class Trainer {

    private static final Logger log = LoggerFactory.getLogger(Trainer.class);

    private final ShieldProperties props;
    private final List<WhatIfPolicy> policies;
    private final WhatIfEngine whatIfEngine;
    private final HistoryStatsBuilder historyStatsBuilder;

    public Trainer(ShieldProperties props, List<WhatIfPolicy> policies, WhatIfEngine whatIfEngine,
                   HistoryStatsBuilder historyStatsBuilder) {
        this.props = props;
        this.policies = policies;
        this.whatIfEngine = whatIfEngine;
        this.historyStatsBuilder = historyStatsBuilder;
    }

    /**
     * Experiments mode (train --experiments=DIR): also fits a decision tree as an extra compared approach and
     * writes held-out predictions to DIR (row level, no IDs; DIR should be git-ignored). experiments/ scripts
     * only publish totals from these files. Off by default, so normal training is unchanged.
     */
    private Path experimentsDir;
    private final Map<String, LogisticModel> earlyLogistic = new HashMap<>();
    private final Map<String, Integer> earlyCut = new HashMap<>();

    public void setExperimentsDir(Path dir) {
        this.experimentsDir = dir;
    }

    public ModelBundle train(Path dataDir) throws IOException {
        ShieldProperties.Training cfg = props.getTraining();
        log.info("Loading training data from {}", dataDir.toAbsolutePath());
        Dataset ds = CsvLoader.loadDirectory(dataDir);
        log.info("Loaded {} teams, {} invoices, {} photos, {} payments, {} disputes",
                ds.teams.size(), ds.invoices.size(), ds.photoCount, ds.payments.size(), ds.disputes.size());

        ModelBundle bundle = new ModelBundle();
        bundle.trainedAt = Instant.now().toString();
        HistoryIndex history = HistoryIndex.build(ds);
        bundle.historyIndex = history;
        bundle.teams = new ArrayList<>(ds.teams.values());

        // ---------------------------------------------------------------- jobs as of payment time
        List<Job> jobs = new ArrayList<>();
        Instant start = Instant.MAX, end = Instant.MIN;
        for (Payment p : ds.payments) {
            if (p.collectedAt == null) continue;
            if (p.collectedAt.isBefore(start)) start = p.collectedAt;
            if (p.collectedAt.isAfter(end)) end = p.collectedAt;
            jobs.add(new Job(JobContext.of(p, ds, null, history), ds.firstDispute(p.paymentId),
                    FeatureExtractor.monthIndex(p.collectedAt)));
        }
        for (Dispute d : ds.disputes) if (d.openedAt != null && d.openedAt.isAfter(end)) end = d.openedAt;
        bundle.dataStart = start.toString();
        bundle.dataEnd = end.toString();

        dataFindings(ds, jobs, bundle);
        assumptions(bundle);

        // ---------------------------------------------------------------- dispute risk (payment moment)
        int censorDays = censorDays(jobs, bundle);
        bundle.assumptions.put("censor_days", censorDays);
        Instant censorCut = end.minus(Duration.ofDays(censorDays));
        List<Map<String, Double>> riskX = new ArrayList<>();
        List<Integer> riskY = new ArrayList<>(), riskM = new ArrayList<>();
        for (Job j : jobs) {
            if (j.ctx().payment.collectedAt.isAfter(censorCut)) continue;
            riskX.add(FeatureExtractor.paymentStage(j.ctx()));
            riskY.add(j.dispute() != null ? 1 : 0);
            riskM.add(j.month());
        }
        bundle.disputeRisk = compare(bundle, ModelBundle.DISPUTE_RISK, riskX, ints(riskY), ints(riskM),
                "cat:method=", 30, "Payment method");
        bundle.notes.put(ModelBundle.DISPUTE_RISK, String.format(
                "Trained on payments collected up to %d days before the end of the data (%d rows); later payments "
                        + "have not had time to be disputed.", censorDays, riskX.size()));

        double[] riskScores = riskX.stream().mapToDouble(bundle.disputeRisk::predict).toArray();
        bundle.tierMedium = Metrics.quantile(riskScores, cfg.getMediumTierQuantile());
        bundle.tierHigh = Metrics.quantile(riskScores, cfg.getHighTierQuantile());

        // ---------------------------------------------------------------- win models
        List<Job> resolved = new ArrayList<>();
        int excludedLeak = 0, eligible = 0;
        String leakMode = cfg.getLeakageMode();
        List<Job> leakFree = new ArrayList<>();
        for (Job j : jobs) {
            Dispute d = j.dispute();
            if (d == null || label(d) < 0) continue;
            eligible++;
            resolved.add(j);
            if (isLeaky(j, leakMode)) excludedLeak++;
            else leakFree.add(j);
        }
        boolean leakFilterApplied = !"NONE".equals(leakMode) && eligible > 0 && leakFree.size() >= 0.5 * eligible;
        List<Job> winJobs = leakFilterApplied ? leakFree : resolved;
        bundle.dataFindings.put("win_models_leakage_filter", "NONE".equals(leakMode)
                ? String.format("Not applied (leakage-mode NONE): updated_at was rewritten by the June 2026 invoice-status "
                + "backfill, so it cannot show late evidence edits. Photo counts are rebuilt from photo timestamps; "
                + "%d of %d resolved disputes have an invoice updated after the dispute opened.", excludedLeak, eligible)
                : leakFilterApplied
                ? String.format("Excluded %d of %d resolved disputes whose invoice was edited after %s.", excludedLeak, eligible,
                leakMode.equals("PAYMENT") ? "payment" : "the dispute opened")
                : String.format("Leakage filter (%s) would have removed %d of %d resolved disputes, so updated_at most "
                + "likely moves with routine status changes; it was not applied. Photo counts are still rebuilt "
                + "from photo timestamps.", leakMode, excludedLeak, eligible));

        List<Map<String, Double>> invX = new ArrayList<>(), payX = new ArrayList<>(), dispX = new ArrayList<>();
        List<Integer> winY = new ArrayList<>(), winM = new ArrayList<>();
        for (Job j : winJobs) {
            invX.add(FeatureExtractor.invoiceStage(j.ctx()));
            payX.add(FeatureExtractor.paymentStage(j.ctx()));
            dispX.add(FeatureExtractor.disputeStage(j.ctx(), j.dispute()));
            winY.add(label(j.dispute()));
            winM.add(FeatureExtractor.monthIndex(j.dispute().openedAt));
        }
        int[] wy = ints(winY), wm = ints(winM);
        bundle.invoiceWin = compare(bundle, ModelBundle.INVOICE_WIN, invX, wy, wm, "cat:profession=", 10, "Profession");
        bundle.paymentWin = compare(bundle, ModelBundle.PAYMENT_WIN, payX, wy, wm, "cat:method=", 10, "Payment method");
        bundle.disputeWin = compare(bundle, ModelBundle.DISPUTE_WIN, dispX, wy, wm, "cat:reason=", 10, "Dispute reason");
        if (experimentsDir != null) writeDisputeBacktest(winJobs, dispX, wy, wm);
        String winNote = String.format("Trained on %d resolved disputes (%s counted as %s). The dispute reason is not "
                        + "known before a dispute, so this averages over the historical mix of reasons.",
                winJobs.size(), "withdrawn", cfg.isWithdrawnCountsAsWon() ? "won" : "lost");
        bundle.notes.put(ModelBundle.INVOICE_WIN, winNote);
        bundle.notes.put(ModelBundle.PAYMENT_WIN, winNote);
        bundle.notes.put(ModelBundle.DISPUTE_WIN, String.format(
                "Trained on %d resolved disputes using reason, response and evidence submitted.", winJobs.size()));

        // ---------------------------------------------------------------- three-rule win tables
        for (String moment : WinRules.MOMENTS) {
            List<boolean[]> keys = new ArrayList<>();
            for (Job j : winJobs) keys.add(WinRules.evaluate(moment, j.ctx(), j.dispute()));
            WinRules.Table t = WinRules.fit(moment, keys, wy, wm, cfg.getValidationMonths());
            bundle.winRules.put(moment, t);
            log.info("[rules:{}] test AUC={} on {} disputes, probabilities {}", moment, fmt(t.testAuc), t.testRows, t.probability);
        }

        // ---------------------------------------------------------------- history + what-if
        bundle.history = historyStatsBuilder.build(ds, jobs, bundle.paymentWin);
        for (WhatIfPolicy p : policies) {
            log.info("Evaluating policy {}", p.id());
            bundle.whatIf.add(whatIfEngine.evaluate(p, jobs, bundle.disputeRisk, bundle.paymentWin));
        }
        log.info("Training finished: {} models, {} policies", 4, bundle.whatIf.size());
        return bundle;
    }

    /**
     * Disputes are recorded when the card network notifies TestPay, weeks after the payment (release notes).
     * Payments closer to the end of the data than this lag look undisputed only because their dispute has
     * not arrived yet. With censor-days = 0 the window is the configured quantile of the observed lag.
     */
    private int censorDays(List<Job> jobs, ModelBundle bundle) {
        ShieldProperties.Training cfg = props.getTraining();
        List<Double> lags = new ArrayList<>();
        for (Job j : jobs) {
            if (j.dispute() == null || j.dispute().openedAt == null) continue;
            lags.add(Duration.between(j.ctx().payment.collectedAt, j.dispute().openedAt).toHours() / 24.0);
        }
        double[] a = lags.stream().mapToDouble(Double::doubleValue).toArray();
        Map<String, Object> lag = new LinkedHashMap<>();
        for (double q : new double[]{0.5, 0.75, 0.9, 0.95, 0.99}) {
            lag.put("p" + Math.round(q * 100), Math.round(Metrics.quantile(a, q) * 10) / 10.0);
        }
        bundle.dataFindings.put("payment_to_dispute_lag_days", lag);
        int days;
        if (cfg.getCensorDays() > 0) days = cfg.getCensorDays();
        else days = a.length == 0 ? 60 : Math.max(14, (int) Math.ceil(Metrics.quantile(a, cfg.getCensorQuantile())));
        log.info("Censoring payments collected in the last {} days (lag percentiles {})", days, lag);
        return days;
    }

    /** 1 = merchant won, 0 = lost, -1 = not usable (open / unknown). */
    private int label(Dispute d) {
        if (d.outcome == null) return -1;
        return switch (d.outcome) {
            case "won" -> 1;
            case "lost" -> 0;
            case "withdrawn" -> props.getTraining().isWithdrawnCountsAsWon() ? 1 : 0;
            default -> -1;
        };
    }

    private static boolean isLeaky(Job j, String mode) {
        Instant upd = j.ctx().invoice.updatedAt;
        if (upd == null) return false;
        return switch (mode) {
            case "PAYMENT" -> upd.isAfter(j.ctx().payment.collectedAt);
            // NONE still counts edits after the dispute for the report, but nothing is excluded.
            case "DISPUTE_OPENED", "NONE" -> j.dispute() != null && j.dispute().openedAt != null && upd.isAfter(j.dispute().openedAt);
            default -> false;
        };
    }

    /** Fits three approaches on early months, scores the held-out late months, then refits logistic on all rows. */
    private LogisticModel compare(ModelBundle bundle, String key, List<Map<String, Double>> X, int[] y, int[] month,
                                  String baselinePrefix, int minCount, String baselineLabel) throws IOException {
        ShieldProperties.Training cfg = props.getTraining();
        int maxMonth = Arrays.stream(month).max().orElse(0);
        int cut = maxMonth - cfg.getValidationMonths();
        List<Map<String, Double>> trX = new ArrayList<>(), vaX = new ArrayList<>();
        List<Integer> trY = new ArrayList<>(), vaY = new ArrayList<>();
        for (int i = 0; i < X.size(); i++) {
            if (month[i] <= cut) { trX.add(X.get(i)); trY.add(y[i]); }
            else { vaX.add(X.get(i)); vaY.add(y[i]); }
        }
        List<Metrics.Report> reports = new ArrayList<>();
        if (!trX.isEmpty() && !vaX.isEmpty()) {
            int[] ty = ints(trY), vy = ints(vaY);
            Map<String, ProbModel> candidates = new LinkedHashMap<>();
            candidates.put("baseline: rate by " + baselineLabel.toLowerCase(Locale.ROOT),
                    SegmentRateModel.fit(baselinePrefix, trX, ty, 20));
            candidates.put("naive_bayes", NaiveBayesModel.fit(trX, ty, minCount));
            candidates.put("logistic_l2", LogisticModel.fit(key, trX, ty, minCount, cfg.getL2(), cfg.getEpochs()));
            if (experimentsDir != null) {
                candidates.put("decision_tree", DecisionTreeModel.fit(trX, ty, 5, Math.max(30, trX.size() / 200)));
                earlyLogistic.put(key, (LogisticModel) candidates.get("logistic_l2"));
                earlyCut.put(key, cut);
            }
            Map<String, double[]> preds = new LinkedHashMap<>();
            for (Map.Entry<String, ProbModel> c : candidates.entrySet()) {
                double[] p = predictAll(c.getValue()::predict, vaX);
                preds.put(c.getKey(), p);
                reports.add(Metrics.evaluate(c.getKey(), p, vy));
            }
            if (experimentsDir != null) {
                writeHoldout(key, cut, vy, preds, predictAll(candidates.get("logistic_l2")::predict, trX));
            }
            log.info("[{}] train months <= {} ({} rows), validate months {}-{} ({} rows)",
                    key, cut, trX.size(), cut + 1, maxMonth, vaX.size());
            for (Metrics.Report r : reports) {
                log.info("[{}]   {}: AUC={} Brier={} LogLoss={} ECE={}", key, r.approach(),
                        fmt(r.auc()), fmt(r.brier()), fmt(r.logLoss()), fmt(r.ece()));
            }
        } else {
            log.warn("[{}] not enough months for a time split; no validation scores", key);
        }
        bundle.validation.put(key, reports);
        bundle.chosenApproach.put(key, "logistic_l2");
        return LogisticModel.fit(key, X, y, minCount, cfg.getL2(), cfg.getEpochs());
    }

    // ---------------------------------------------------------------- experiments output

    /** month-free held-out file: label and each approach's probability; plus train-score quantiles for tiers. */
    private void writeHoldout(String key, int cut, int[] vy, Map<String, double[]> preds, double[] trainLogistic) throws IOException {
        Files.createDirectories(experimentsDir);
        List<String> lines = new ArrayList<>();
        lines.add("y," + String.join(",", preds.keySet().stream().map(k -> k.startsWith("baseline") ? "baseline" : k).toList()));
        for (int i = 0; i < vy.length; i++) {
            StringBuilder b = new StringBuilder().append(vy[i]);
            for (double[] p : preds.values()) b.append(',').append(String.format(Locale.ROOT, "%.6f", p[i]));
            lines.add(b.toString());
        }
        Files.write(experimentsDir.resolve("holdout_" + key + ".csv"), lines);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("key", key);
        meta.put("train_months_up_to", cut);
        meta.put("train_rows", trainLogistic.length);
        meta.put("holdout_rows", vy.length);
        meta.put("train_logistic_q70", Metrics.quantile(trainLogistic, 0.70));
        meta.put("train_logistic_q90", Metrics.quantile(trainLogistic, 0.90));
        BundleIO.MAPPER.writeValue(experimentsDir.resolve("holdout_" + key + ".json").toFile(), meta);
    }

    /**
     * One row per resolved dispute (no IDs): whether it is in the held-out months, reason, amount, responded,
     * outcome, the dispute-rule combination, and the early logistic model's win chance as submitted and with
     * "responded" set. Used to back-test fight/accept policies.
     */
    private void writeDisputeBacktest(List<Job> winJobs, List<Map<String, Double>> dispX, int[] wy, int[] wm) throws IOException {
        LogisticModel early = earlyLogistic.get(ModelBundle.DISPUTE_WIN);
        Integer cut = earlyCut.get(ModelBundle.DISPUTE_WIN);
        if (early == null || cut == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("test,month,reason,amount_usd,responded,y,rule_key,p_model,p_model_if_responded");
        for (int i = 0; i < winJobs.size(); i++) {
            Job j = winJobs.get(i);
            Dispute d = j.dispute();
            Map<String, Double> resp = new HashMap<>(dispX.get(i));
            resp.put("disp:responded", 1.0);
            lines.add(String.format(Locale.ROOT, "%d,%d,%s,%.2f,%d,%d,%s,%.6f,%.6f",
                    wm[i] > cut ? 1 : 0, wm[i], d.reason, d.amountUsd, d.merchantResponded ? 1 : 0, wy[i],
                    WinRules.key(WinRules.evaluate(WinRules.DISPUTE, j.ctx(), d)),
                    early.predict(dispX.get(i)), early.predict(resp)));
        }
        Files.write(experimentsDir.resolve("dispute_backtest.csv"), lines);
    }

    private static double[] predictAll(Function<Map<String, Double>, Double> f, List<Map<String, Double>> X) {
        double[] p = new double[X.size()];
        for (int i = 0; i < p.length; i++) p[i] = f.apply(X.get(i));
        return p;
    }

    private void dataFindings(Dataset ds, List<Job> jobs, ModelBundle bundle) {
        Map<String, Object> f = bundle.dataFindings;
        f.put("teams", ds.teams.size());
        f.put("invoices", ds.invoices.size());
        f.put("photos", ds.photoCount);
        f.put("payments", ds.payments.size());
        f.put("disputes", ds.disputes.size());
        f.put("dispute_rate", ds.payments.isEmpty() ? 0 : (double) ds.disputes.size() / ds.payments.size());
        Map<String, Integer> outcomes = new TreeMap<>(), reasons = new TreeMap<>(), evidence = new HashMap<>();
        for (Dispute d : ds.disputes) {
            outcomes.merge(String.valueOf(d.outcome), 1, Integer::sum);
            reasons.merge(String.valueOf(d.reason), 1, Integer::sum);
            for (String e : d.evidenceSubmitted) evidence.merge(e, 1, Integer::sum);
        }
        f.put("dispute_outcomes", outcomes);
        f.put("dispute_reasons", reasons);
        f.put("evidence_tokens", evidence.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(30)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));

        int missingInvoice = 0, updatedAfterPayment = 0, disputedUpdatedAfterOpen = 0, disputed = 0;
        for (Job j : jobs) {
            if (j.ctx().invoiceMissing) missingInvoice++;
            Instant upd = j.ctx().invoice.updatedAt;
            if (upd != null && upd.isAfter(j.ctx().payment.collectedAt)) updatedAfterPayment++;
            if (j.dispute() != null) {
                disputed++;
                if (upd != null && j.dispute().openedAt != null && upd.isAfter(j.dispute().openedAt)) disputedUpdatedAfterOpen++;
            }
        }
        // Release notes: completion signature from Dec 2025, adopted team by team.
        int signedBeforeLaunch = 0, unavailableJobs = 0;
        for (Job j : jobs) {
            Invoice inv = j.ctx().invoice;
            Instant t = inv.jobCompletedAt != null ? inv.jobCompletedAt : inv.createdAt;
            if (inv.completionSignature && t != null && t.isBefore(ReleaseCalendar.COMPLETION_SIGNATURE_LAUNCH)) signedBeforeLaunch++;
            if (!j.ctx().signatureAvailable) unavailableJobs++;
        }
        f.put("signed_jobs_before_signature_launch", signedBeforeLaunch);
        f.put("jobs_where_signature_not_available_share", jobs.isEmpty() ? 0 : (double) unavailableJobs / jobs.size());
        f.put("teams_using_completion_signature", bundle.historyIndex.teamFirstSignature.size());
        // Release notes: invoice_status added June 2026 and backfilled. Never a model input; check it agrees.
        Map<String, Integer> statuses = new TreeMap<>();
        int disputedNoRecord = 0, recordNotDisputed = 0;
        for (Job j : jobs) {
            String s = String.valueOf(j.ctx().invoice.invoiceStatus);
            statuses.merge(s, 1, Integer::sum);
            boolean hasDispute = j.dispute() != null;
            if ("disputed".equals(s) && !hasDispute) disputedNoRecord++;
            if (hasDispute && !"disputed".equals(s)) recordNotDisputed++;
        }
        f.put("invoice_status_counts", statuses);
        f.put("status_disputed_without_dispute_record", disputedNoRecord);
        f.put("dispute_record_without_status_disputed", recordNotDisputed);
        f.put("payments_without_invoice", missingInvoice);
        f.put("invoices_updated_after_payment_share", jobs.isEmpty() ? 0 : (double) updatedAfterPayment / jobs.size());
        f.put("disputed_invoices_updated_after_dispute_share", disputed == 0 ? 0 : (double) disputedUpdatedAfterOpen / disputed);

        // Photos uploaded after the payment (and after a dispute) are evidence added late: excluded from features.
        Map<String, Instant> collectedByInvoice = new HashMap<>(), disputeByInvoice = new HashMap<>();
        for (Job j : jobs) {
            collectedByInvoice.put(j.ctx().invoice.invoiceId, j.ctx().payment.collectedAt);
            if (j.dispute() != null) disputeByInvoice.put(j.ctx().invoice.invoiceId, j.dispute().openedAt);
        }
        int late = 0, afterDispute = 0, total = 0, countMismatch = 0;
        for (Map.Entry<String, List<Photo>> e : ds.photosByInvoice.entrySet()) {
            Instant col = collectedByInvoice.get(e.getKey());
            Instant op = disputeByInvoice.get(e.getKey());
            for (Photo p : e.getValue()) {
                total++;
                if (col != null && p.uploadedAt != null && p.uploadedAt.isAfter(col)) late++;
                if (op != null && p.uploadedAt != null && p.uploadedAt.isAfter(op)) afterDispute++;
            }
        }
        for (Invoice inv : ds.invoices.values()) {
            List<Photo> l = ds.photosByInvoice.getOrDefault(inv.invoiceId, List.of());
            if (l.size() != inv.photosBeforeCount + inv.photosAfterCount) countMismatch++;
        }
        f.put("photos_uploaded_after_payment_share", total == 0 ? 0 : (double) late / total);
        f.put("photos_uploaded_after_dispute_opened", afterDispute);
        f.put("invoices_where_photo_counts_disagree_with_photos_file", countMismatch);
    }

    private void assumptions(ModelBundle bundle) {
        ShieldProperties.Economics e = props.getEconomics();
        ShieldProperties.Training t = props.getTraining();
        bundle.assumptions.put("dispute_fee_usd", e.getDisputeFeeUsd());
        bundle.assumptions.put("fight_cost_usd", e.getFightCostUsd());
        bundle.assumptions.put("min_expected_gain_usd", e.getMinExpectedGainUsd());
        bundle.assumptions.put("fee_recovered_on_win", e.isFeeRecoveredOnWin());
        bundle.assumptions.put("withdrawn_counts_as_won", t.isWithdrawnCountsAsWon());
        bundle.assumptions.put("censor_quantile", t.getCensorQuantile());
        bundle.assumptions.put("validation_months", t.getValidationMonths());
        bundle.assumptions.put("leakage_mode", t.getLeakageMode());
        bundle.assumptions.put("tier_quantiles", Map.of("medium", t.getMediumTierQuantile(), "high", t.getHighTierQuantile()));
    }

    private static int[] ints(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
