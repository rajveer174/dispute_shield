package com.testpay.shield.web;

import com.testpay.shield.rules.WinRules;
import com.testpay.shield.scoring.ModelStore;
import com.testpay.shield.scoring.Predictions.RuleCheck;
import com.testpay.shield.scoring.Predictions.RuleResult;
import com.testpay.shield.scoring.Predictions.DisputePrediction;
import com.testpay.shield.scoring.Predictions.PaymentPrediction;
import com.testpay.shield.session.SessionService;
import com.testpay.shield.session.UploadSession;
import com.testpay.shield.whatif.PolicyResult;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CSV exports. payment_predictions, dispute_predictions and whatif_results are exactly the brief's formats
 * (unpublished predictions left blank); the *_win_rules files are extra and carry the three-rule results.
 */
@RestController
@RequestMapping("/api")
@Profile("!train")
public class ExportController {

    private final SessionService sessions;
    private final ModelStore store;

    public ExportController(SessionService sessions, ModelStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    @GetMapping("/sessions/{id}/export/payment_predictions.csv")
    public void payments(@PathVariable String id, HttpServletResponse resp) throws IOException {
        UploadSession s = sessions.get(id);
        try (CSVPrinter out = printer(resp, "payment_predictions.csv",
                "payment_id", "invoice_win_prob", "payment_win_prob", "dispute_risk_tier", "dispute_risk_score", "expected_loss_usd")) {
            for (PaymentPrediction p : s.payments) {
                out.printRecord(p.paymentId, num(p.invoiceWinProb), num(p.paymentWinProb), p.disputeRiskTier,
                        num(p.disputeRiskScore), num(p.expectedLossUsd));
            }
        }
    }

    @GetMapping("/sessions/{id}/export/dispute_predictions.csv")
    public void disputes(@PathVariable String id, HttpServletResponse resp) throws IOException {
        UploadSession s = sessions.get(id);
        try (CSVPrinter out = printer(resp, "dispute_predictions.csv", "dispute_id", "win_prob", "recommendation")) {
            for (DisputePrediction d : s.disputes) out.printRecord(d.disputeId, num(d.winProb), d.recommendation);
        }
    }

    // ---------------------------------------------------------------- three-rule exports (extra to the brief's formats)

    @GetMapping("/sessions/{id}/export/invoice_win_rules.csv")
    public void invoiceRules(@PathVariable String id, HttpServletResponse resp) throws IOException {
        UploadSession s = sessions.get(id);
        try (CSVPrinter out = printer(resp, "invoice_win_rules.csv", ruleHeader(WinRules.INVOICE, "invoice_id", "team_id"))) {
            Set<String> seen = new HashSet<>();
            for (PaymentPrediction p : s.payments) {
                if (p.invoiceMissing || !seen.add(p.invoiceId)) continue;
                out.printRecord(ruleRow(p.invoiceRules, p.invoiceId, p.teamId));
            }
        }
    }

    @GetMapping("/sessions/{id}/export/payment_win_rules.csv")
    public void paymentRules(@PathVariable String id, HttpServletResponse resp) throws IOException {
        UploadSession s = sessions.get(id);
        try (CSVPrinter out = printer(resp, "payment_win_rules.csv",
                ruleHeader(WinRules.PAYMENT, "payment_id", "invoice_id", "team_id"))) {
            for (PaymentPrediction p : s.payments) out.printRecord(ruleRow(p.paymentRules, p.paymentId, p.invoiceId, p.teamId));
        }
    }

    @GetMapping("/sessions/{id}/export/dispute_win_rules.csv")
    public void disputeRules(@PathVariable String id, HttpServletResponse resp) throws IOException {
        UploadSession s = sessions.get(id);
        try (CSVPrinter out = printer(resp, "dispute_win_rules.csv",
                ruleHeader(WinRules.DISPUTE, "dispute_id", "payment_id", "team_id", "reason"))) {
            for (DisputePrediction d : s.disputes) out.printRecord(ruleRow(d.disputeRules, d.disputeId, d.paymentId, d.teamId, d.reason));
        }
    }

    private static String[] ruleHeader(String moment, String... ids) {
        List<String> h = new ArrayList<>(List.of(ids));
        for (WinRules.Rule r : WinRules.RULES.get(moment)) h.add(r.column());
        h.add("rules_met");
        h.add("Dispute win probability");
        return h.toArray(String[]::new);
    }

    private static List<Object> ruleRow(RuleResult r, Object... ids) {
        List<Object> row = new ArrayList<>(Arrays.asList(ids));
        for (RuleCheck c : r.rules) row.add(c.met ? "yes" : "no");
        row.add(r.rulesMet);
        row.add(num(r.winProb));
        return row;
    }

    @GetMapping("/export/whatif_results.csv")
    public void whatIf(HttpServletResponse resp) throws IOException {
        try (CSVPrinter out = printer(resp, "whatif_results.csv", "policy", "metric", "estimated_change", "low", "high")) {
            for (PolicyResult p : store.bundle().whatIf) {
                for (PolicyResult.MetricChange m : p.metrics) {
                    out.printRecord(p.policy, m.metric, num(m.estimatedChange), num(m.low), num(m.high));
                }
            }
        }
    }

    private static CSVPrinter printer(HttpServletResponse resp, String filename, String... header) throws IOException {
        resp.setContentType("text/csv; charset=utf-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        Writer w = new OutputStreamWriter(resp.getOutputStream(), StandardCharsets.UTF_8);
        return new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(header).setRecordSeparator("\n").build());
    }

    private static String num(Double v) {
        if (v == null || v.isNaN()) return "";
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
