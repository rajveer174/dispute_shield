package com.testpay.shield.ingest;

import java.util.List;

/** The CSV files we accept and the columns each must contain. */
public enum FileKind {
    TEAMS("teams.csv", List.of("team_id", "profession", "state", "onboarded_on", "team_size"), List.of()),
    INVOICES("invoices.csv", List.of("invoice_id", "team_id", "customer_id", "created_at", "job_completed_at",
            "job_type", "amount_usd", "line_item_count", "job_description_chars", "customer_address_present",
            "customer_email_present", "customer_phone_present", "signed_estimate", "terms_accepted",
            "tech_gps_checkin", "completion_signature", "photos_before_count", "photos_after_count",
            "invoice_status", "updated_at"), List.of()),
    PHOTOS("photos.csv", List.of("photo_id", "invoice_id", "kind", "uploaded_at"), List.of()),
    PAYMENTS("payments.csv", List.of("payment_id", "invoice_id", "team_id", "customer_id", "collected_at",
            "amount_usd", "tip_usd", "payment_method", "card_fingerprint", "card_country", "card_type",
            "avs_result", "cvv_result", "receipt_sent"), List.of()),
    /** outcome / closed_at are optional: uploaded months 23-24 disputes do not have them. */
    DISPUTES("disputes.csv", List.of("dispute_id", "payment_id", "invoice_id", "team_id", "opened_at", "reason",
            "amount_usd", "merchant_responded", "evidence_submitted"), List.of("outcome", "closed_at"));

    public final String fileName;
    public final List<String> requiredColumns;
    public final List<String> optionalColumns;

    FileKind(String fileName, List<String> required, List<String> optional) {
        this.fileName = fileName;
        this.requiredColumns = required;
        this.optionalColumns = optional;
    }
}
