package com.testpay.shield.scoring;

import java.util.Map;

/** Plain-language names for model features, so drivers can be shown to a team. */
public final class FeatureLabels {
    private FeatureLabels() {}

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("num:log_amount", "Invoice amount"),
            Map.entry("num:log_payment_amount", "Payment amount"),
            Map.entry("num:line_items", "Number of line items"),
            Map.entry("num:log_desc_chars", "Length of job description"),
            Map.entry("num:photos_total", "Number of job photos"),
            Map.entry("num:log_team_size", "Team size"),
            Map.entry("num:log_tenure_days", "Time since team onboarded"),
            Map.entry("num:signed_log_hours_created_to_done", "Time from invoice to job completion"),
            Map.entry("num:signed_log_hours_done_to_paid", "Time from job completion to payment"),
            Map.entry("num:tip_ratio", "Tip size"),
            Map.entry("num:amount_ratio", "Payment vs invoice amount"),
            Map.entry("num:log_card_prior_disputes", "Card has earlier disputes"),
            Map.entry("num:log_card_other_teams", "Card used at other teams"),
            Map.entry("num:log_customer_prior_disputes", "Customer has earlier disputes"),
            Map.entry("num:evidence_count", "Amount of evidence submitted"),
            Map.entry("num:log_days_to_dispute", "Days from payment to dispute"),
            Map.entry("num:dispute_amount_ratio", "Disputed share of the payment"),
            Map.entry("inv:address", "Customer address on invoice"),
            Map.entry("inv:email", "Customer email on invoice"),
            Map.entry("inv:phone", "Customer phone on invoice"),
            Map.entry("inv:signed_estimate", "Signed estimate"),
            Map.entry("inv:terms", "Terms accepted"),
            Map.entry("inv:gps", "Technician GPS check-in"),
            Map.entry("inv:signature", "Completion signature"),
            Map.entry("inv:signature_unavailable", "Team not using completion signatures yet"),
            Map.entry("inv:has_before_photo", "Before photos"),
            Map.entry("inv:has_after_photo", "After photos"),
            Map.entry("inv:detailed_description", "Detailed job description"),
            Map.entry("inv:missing", "Invoice not found"),
            Map.entry("pay:foreign_card", "Card issued outside the US"),
            Map.entry("pay:receipt", "Receipt sent"),
            Map.entry("pay:has_tip", "Customer tipped"),
            Map.entry("pay:paid_before_completion", "Paid before the job was completed"),
            Map.entry("pay:card_prior_fraud", "Card has an earlier fraud dispute"),
            Map.entry("pay:night_utc", "Paid late at night"),
            Map.entry("disp:responded", "Merchant responded")
    );

    /** How a number feature reads when it goes up, e.g. "More earlier disputes on this card". */
    private static final Map<String, String> HIGHER = Map.ofEntries(
            Map.entry("num:log_amount", "Larger invoice amount"),
            Map.entry("num:log_payment_amount", "Larger payment amount"),
            Map.entry("num:line_items", "More line items on the invoice"),
            Map.entry("num:log_desc_chars", "Longer job description"),
            Map.entry("num:photos_total", "More job photos"),
            Map.entry("num:log_team_size", "Larger team"),
            Map.entry("num:log_tenure_days", "Team has been on TestPay longer"),
            Map.entry("num:signed_log_hours_created_to_done", "Longer gap from invoice to job completion"),
            Map.entry("num:signed_log_hours_done_to_paid", "Longer gap from job completion to payment"),
            Map.entry("num:tip_ratio", "Bigger tip"),
            Map.entry("num:amount_ratio", "Payment larger than the invoice"),
            Map.entry("num:log_card_prior_disputes", "More earlier disputes on this card"),
            Map.entry("num:log_card_other_teams", "Card used at more other teams"),
            Map.entry("num:log_customer_prior_disputes", "More earlier disputes from this customer"),
            Map.entry("num:evidence_count", "More evidence submitted"),
            Map.entry("num:log_days_to_dispute", "Longer wait between payment and dispute"),
            Map.entry("num:dispute_amount_ratio", "Larger share of the payment disputed")
    );

    private static final Map<String, String> FIELDS = Map.of(
            "method", "Payment method",
            "avs", "Address check (AVS)",
            "cvv", "Security code check (CVV)",
            "card_type", "Card type",
            "job_type", "Job type",
            "profession", "Profession",
            "state", "Team's state",
            "reason", "Dispute reason"
    );

    private static final Map<String, String> VALUES = Map.of(
            "keyed", "keyed in by hand",
            "ach", "bank transfer (ACH)",
            "no_match", "did not match",
            "match", "matched",
            "partial", "partly matched",
            "not_applicable", "not used",
            "missing", "not recorded"
    );

    /** Plain phrase for a factor as it shows on the Models page ("what raises / lowers this"). */
    public static String phrase(String feature) {
        String h = HIGHER.get(feature);
        if (h != null) return h;
        String l = label(feature);
        if (feature.startsWith("num:")) return "Higher: " + Character.toLowerCase(l.charAt(0)) + l.substring(1);
        return l;
    }

    public static String label(String feature) {
        String n = NAMES.get(feature);
        if (n != null) return n;
        if (feature.startsWith("cat:")) {
            String[] kv = feature.substring(4).split("=", 2);
            String field = FIELDS.get(kv[0]);
            if (field == null) {
                field = kv[0].replace('_', ' ');
                field = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            }
            String value = kv.length > 1 ? kv[1] : "";
            if (kv[0].equals("state")) value = value.toUpperCase(java.util.Locale.ROOT);
            else value = VALUES.getOrDefault(value, value.replace('_', ' '));
            return field + ": " + value;
        }
        if (feature.startsWith("x:")) {
            // x:reason=<reason>|ev=<token>  ->  "Photos submitted on a fraud dispute"
            int i = feature.indexOf("|ev=");
            if (i < 0) return feature;
            String reason = feature.substring("x:reason=".length(), i).replace('_', ' ');
            return submitted(feature.substring(i + 4)) + " on a " + reason + " dispute";
        }
        if (feature.startsWith("ev:")) return submitted(feature.substring(3));
        return feature;
    }

    private static String submitted(String token) {
        return token.equalsIgnoreCase("none") ? "No evidence submitted" : evidenceLabel(token) + " submitted";
    }

    public static String evidenceLabel(String token) {
        String s = token.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
