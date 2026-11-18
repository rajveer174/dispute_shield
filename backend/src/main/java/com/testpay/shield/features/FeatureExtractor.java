package com.testpay.shield.features;

import com.testpay.shield.domain.Records.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Turns a job into named features for each moment. Names:
 * <ul>
 *   <li>{@code num:*} continuous (standardised by the model)</li>
 *   <li>{@code cat:<field>=<value>} one-hot categorical</li>
 *   <li>{@code inv:*}, {@code pay:*}, {@code disp:*}, {@code ev:*} 0/1 flags</li>
 *   <li>{@code x:reason=..|ev=..} reason by evidence interaction</li>
 * </ul>
 * Each stage only uses data that exists at that moment.
 */
public final class FeatureExtractor {
    private FeatureExtractor() {}

    public enum Stage { INVOICE, PAYMENT, DISPUTE }

    public static final int DETAILED_DESCRIPTION_CHARS = 80;
    public static final String SIGNATURE_UNAVAILABLE = "inv:signature_unavailable";

    /** Invoice ready, before payment: invoice + team only. */
    public static Map<String, Double> invoiceStage(JobContext c) {
        Map<String, Double> f = new HashMap<>();
        Invoice i = c.invoice;
        num(f, "num:log_amount", Math.log1p(Math.max(0, i.amountUsd)));
        num(f, "num:line_items", Math.min(i.lineItemCount, 20));
        num(f, "num:log_desc_chars", Math.log1p(Math.max(0, i.jobDescriptionChars)));
        flag(f, "inv:address", i.customerAddressPresent);
        flag(f, "inv:email", i.customerEmailPresent);
        flag(f, "inv:phone", i.customerPhonePresent);
        flag(f, "inv:signed_estimate", i.signedEstimate);
        flag(f, "inv:terms", i.termsAccepted);
        flag(f, "inv:gps", i.techGpsCheckin);
        flag(f, "inv:signature", i.completionSignature);
        // Release notes: signatures exist from Dec 2025 and only for teams that adopted them. Without this flag
        // the model would read "before Dec 2025" as "careless paperwork".
        flag(f, SIGNATURE_UNAVAILABLE, !c.signatureAvailable);
        flag(f, "inv:has_before_photo", c.photosBefore > 0);
        flag(f, "inv:has_after_photo", c.photosAfter > 0);
        flag(f, "inv:detailed_description", i.jobDescriptionChars >= DETAILED_DESCRIPTION_CHARS);
        num(f, "num:photos_total", Math.min(c.photosBefore + c.photosAfter, 20));
        flag(f, "inv:missing", c.invoiceMissing);
        cat(f, "job_type", i.jobType);
        if (c.team != null) {
            cat(f, "profession", c.team.profession);
            cat(f, "state", c.team.state);
            num(f, "num:log_team_size", Math.log1p(Math.max(0, c.team.teamSize)));
            Instant ref = c.payment != null && c.payment.collectedAt != null ? c.payment.collectedAt : i.createdAt;
            if (c.team.onboardedOn != null && ref != null) {
                long days = Duration.between(c.team.onboardedOn.atStartOfDay().toInstant(ZoneOffset.UTC), ref).toDays();
                num(f, "num:log_tenure_days", Math.log1p(Math.max(0, days)));
            }
        }
        if (i.createdAt != null && i.jobCompletedAt != null) {
            double hours = Duration.between(i.createdAt, i.jobCompletedAt).toMinutes() / 60.0;
            num(f, "num:signed_log_hours_created_to_done", signedLog(hours));
        }
        return f;
    }

    /** Payment collected: invoice + payment + card/customer history. */
    public static Map<String, Double> paymentStage(JobContext c) {
        Map<String, Double> f = invoiceStage(c);
        Payment p = c.payment;
        cat(f, "method", p.paymentMethod);
        cat(f, "avs", p.avsResult);
        cat(f, "cvv", p.cvvResult);
        cat(f, "card_type", p.cardType);
        String country = p.cardCountry == null ? null : p.cardCountry.trim().toUpperCase(Locale.ROOT);
        flag(f, "pay:foreign_card", country != null && !country.equals("US") && !country.equals("USA"));
        flag(f, "pay:receipt", p.receiptSent);
        flag(f, "pay:has_tip", p.tipUsd > 0);
        num(f, "num:tip_ratio", p.amountUsd > 0 ? Math.min(p.tipUsd / p.amountUsd, 1.0) : 0);
        double invAmt = c.invoice.amountUsd;
        num(f, "num:amount_ratio", invAmt > 0 ? Math.min(p.amountUsd / invAmt, 3.0) : 1.0);
        num(f, "num:log_payment_amount", Math.log1p(Math.max(0, p.amountUsd)));
        if (c.invoice.jobCompletedAt != null && p.collectedAt != null) {
            double hours = Duration.between(c.invoice.jobCompletedAt, p.collectedAt).toMinutes() / 60.0;
            num(f, "num:signed_log_hours_done_to_paid", signedLog(hours));
            flag(f, "pay:paid_before_completion", hours < 0);
        }
        num(f, "num:log_card_prior_disputes", Math.log1p(c.cardPriorDisputes));
        flag(f, "pay:card_prior_fraud", c.cardPriorFraudDisputes > 0);
        num(f, "num:log_card_other_teams", Math.log1p(c.cardPriorOtherTeams));
        num(f, "num:log_customer_prior_disputes", Math.log1p(c.customerPriorDisputes));
        if (p.collectedAt != null) {
            int hour = p.collectedAt.atZone(ZoneOffset.UTC).getHour();
            flag(f, "pay:night_utc", hour < 6);
        }
        return f;
    }

    /** Dispute received: everything above + reason, response and evidence submitted. */
    public static Map<String, Double> disputeStage(JobContext c, Dispute d) {
        Map<String, Double> f = paymentStage(c);
        cat(f, "reason", d.reason);
        flag(f, "disp:responded", d.merchantResponded);
        List<String> ev = d.evidenceSubmitted == null ? List.of() : d.evidenceSubmitted;
        num(f, "num:evidence_count", Math.min(ev.size(), 15));
        for (String e : new LinkedHashSet<>(ev)) {
            f.put("ev:" + e, 1.0);
            if (d.reason != null) f.put("x:reason=" + d.reason + "|ev=" + e, 1.0);
        }
        if (c.payment.collectedAt != null && d.openedAt != null) {
            double days = Duration.between(c.payment.collectedAt, d.openedAt).toHours() / 24.0;
            num(f, "num:log_days_to_dispute", Math.log1p(Math.max(0, days)));
        }
        num(f, "num:dispute_amount_ratio", c.payment.amountUsd > 0 ? Math.min(d.amountUsd / c.payment.amountUsd, 3.0) : 1.0);
        return f;
    }

    public static Map<String, Double> forStage(Stage s, JobContext c, Dispute d) {
        return switch (s) {
            case INVOICE -> invoiceStage(c);
            case PAYMENT -> paymentStage(c);
            case DISPUTE -> disputeStage(c, d);
        };
    }

    private static double signedLog(double v) {
        return Math.signum(v) * Math.log1p(Math.abs(v));
    }

    private static void num(Map<String, Double> f, String name, double v) {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) f.put(name, v);
    }

    private static void flag(Map<String, Double> f, String name, boolean v) {
        if (v) f.put(name, 1.0);
        else f.put(name, 0.0);
    }

    private static void cat(Map<String, Double> f, String field, String value) {
        String v = value == null || value.isBlank() ? "missing" : value.trim().toLowerCase(Locale.ROOT);
        f.put("cat:" + field + "=" + v, 1.0);
    }

    /** Month index used across the app: month 1 = November 2024, month 22 = August 2026. */
    public static int monthIndex(Instant t) {
        LocalDate d = LocalDate.ofInstant(t, ZoneOffset.UTC);
        return (d.getYear() - 2024) * 12 + d.getMonthValue() - 10;
    }
}
