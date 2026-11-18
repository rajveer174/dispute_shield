package com.testpay.shield.model;

import com.testpay.shield.domain.Records.Team;
import com.testpay.shield.features.HistoryIndex;
import com.testpay.shield.training.HistoryStats;
import com.testpay.shield.whatif.PolicyResult;

import java.util.*;

/** Everything the web app needs, produced once by the trainer from months 1-22. Never updated by uploads. */
public class ModelBundle {

    public static final String INVOICE_WIN = "invoice_win";
    public static final String PAYMENT_WIN = "payment_win";
    public static final String DISPUTE_RISK = "dispute_risk";
    public static final String DISPUTE_WIN = "dispute_win";

    public String version = "1";
    public String trainedAt;
    public String dataStart;
    public String dataEnd;
    public Map<String, Object> assumptions = new LinkedHashMap<>();
    public Map<String, Object> dataFindings = new LinkedHashMap<>();

    public LogisticModel invoiceWin;
    public LogisticModel paymentWin;
    public LogisticModel disputeRisk;
    public LogisticModel disputeWin;

    /** Risk score thresholds (probability) for the medium and high tiers. */
    public double tierMedium;
    public double tierHigh;

    /** prediction key -> comparison of approaches on the time-based validation split. */
    public Map<String, List<Metrics.Report>> validation = new LinkedHashMap<>();
    public Map<String, String> chosenApproach = new LinkedHashMap<>();
    public Map<String, String> notes = new LinkedHashMap<>();

    /** Three-rule win tables per moment (invoice_ready, payment_collected, dispute_received). */
    public Map<String, com.testpay.shield.rules.WinRules.Table> winRules = new LinkedHashMap<>();

    public List<PolicyResult> whatIf = new ArrayList<>();
    public HistoryStats history;
    public HistoryIndex historyIndex;
    public List<Team> teams = new ArrayList<>();

    public LogisticModel model(String key) {
        return switch (key) {
            case INVOICE_WIN -> invoiceWin;
            case PAYMENT_WIN -> paymentWin;
            case DISPUTE_RISK -> disputeRisk;
            case DISPUTE_WIN -> disputeWin;
            default -> null;
        };
    }

    /** Validation AUC of the chosen (logistic) approach. */
    public double validationAuc(String key) {
        List<Metrics.Report> reps = validation.get(key);
        if (reps == null) return Double.NaN;
        String chosen = chosenApproach.get(key);
        for (Metrics.Report r : reps) if (r.approach().equals(chosen)) return r.auc();
        return Double.NaN;
    }
}
