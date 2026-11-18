package com.testpay.shield.ingest;

import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.function.Consumer;

/**
 * Parses the CSV files into {@link Dataset}. Lenient about value formats (booleans as true/1/yes,
 * timestamps with or without zone, all treated as UTC) but strict about columns.
 */
public final class CsvLoader {
    private CsvLoader() {}

    private static final int MAX_ROW_ERRORS_REPORTED = 10;

    /** Loads every known file that exists in a directory (training data). */
    public static Dataset loadDirectory(Path dir) throws IOException {
        Dataset ds = new Dataset();
        for (FileKind kind : FileKind.values()) {
            Path f = dir.resolve(kind.fileName);
            if (!Files.exists(f)) {
                if (kind == FileKind.PHOTOS || kind == FileKind.DISPUTES) continue;
                throw new SchemaException("Missing required file " + f);
            }
            try (InputStream in = Files.newInputStream(f)) {
                load(kind, in, ds);
            }
        }
        return ds;
    }

    public static void load(FileKind kind, InputStream in, Dataset ds) throws IOException {
        Reader reader = new BufferedReader(new InputStreamReader(stripBom(in), StandardCharsets.UTF_8));
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = fmt.parse(reader)) {
            Set<String> header = new HashSet<>();
            for (String h : parser.getHeaderNames()) header.add(h.trim().toLowerCase(Locale.ROOT));
            List<String> missing = new ArrayList<>();
            for (String c : kind.requiredColumns) if (!header.contains(c)) missing.add(c);
            if (!missing.isEmpty()) {
                throw new SchemaException(kind.fileName + ": missing columns " + missing
                        + ". Is this the right file? Found columns " + parser.getHeaderNames());
            }
            Map<String, String> canonical = new HashMap<>();
            for (String h : parser.getHeaderNames()) canonical.put(h.trim().toLowerCase(Locale.ROOT), h);

            List<String> errors = new ArrayList<>();
            int errorCount = 0;
            Consumer<CSVRecord> handler = switch (kind) {
                case TEAMS -> r -> { Team t = team(new Row(r, canonical)); ds.teams.put(t.teamId, t); };
                case INVOICES -> r -> { Invoice i = invoice(new Row(r, canonical)); ds.invoices.put(i.invoiceId, i); };
                case PHOTOS -> r -> ds.addPhoto(photo(new Row(r, canonical)));
                case PAYMENTS -> r -> ds.payments.add(payment(new Row(r, canonical)));
                case DISPUTES -> r -> ds.addDispute(dispute(new Row(r, canonical)));
            };
            for (CSVRecord r : parser) {
                try {
                    handler.accept(r);
                } catch (RuntimeException e) {
                    errorCount++;
                    if (errors.size() < MAX_ROW_ERRORS_REPORTED) {
                        errors.add(kind.fileName + " line " + (r.getRecordNumber() + 1) + ": " + e.getMessage());
                    }
                }
            }
            if (errorCount > 0) {
                if (errorCount > MAX_ROW_ERRORS_REPORTED) errors.add("... " + errorCount + " bad rows in total");
                throw new SchemaException(errors);
            }
        }
    }

    // ---------------------------------------------------------------- row mappers

    private static Team team(Row r) {
        Team t = new Team();
        t.teamId = r.req("team_id");
        t.profession = r.str("profession");
        t.state = r.str("state");
        Instant on = r.instant("onboarded_on");
        t.onboardedOn = on == null ? null : LocalDate.ofInstant(on, ZoneOffset.UTC);
        t.teamSize = r.integer("team_size");
        return t;
    }

    private static Invoice invoice(Row r) {
        Invoice i = new Invoice();
        i.invoiceId = r.req("invoice_id");
        i.teamId = r.req("team_id");
        i.customerId = r.str("customer_id");
        i.createdAt = r.instant("created_at");
        i.jobCompletedAt = r.instant("job_completed_at");
        i.jobType = r.str("job_type");
        i.amountUsd = r.dbl("amount_usd");
        i.lineItemCount = r.integer("line_item_count");
        i.jobDescriptionChars = r.integer("job_description_chars");
        i.customerAddressPresent = r.bool("customer_address_present");
        i.customerEmailPresent = r.bool("customer_email_present");
        i.customerPhonePresent = r.bool("customer_phone_present");
        i.signedEstimate = r.bool("signed_estimate");
        i.termsAccepted = r.bool("terms_accepted");
        i.techGpsCheckin = r.bool("tech_gps_checkin");
        i.completionSignature = r.bool("completion_signature");
        i.photosBeforeCount = r.integer("photos_before_count");
        i.photosAfterCount = r.integer("photos_after_count");
        i.invoiceStatus = r.str("invoice_status");
        i.updatedAt = r.instant("updated_at");
        return i;
    }

    private static Photo photo(Row r) {
        Photo p = new Photo();
        p.photoId = r.req("photo_id");
        p.invoiceId = r.req("invoice_id");
        p.kind = r.str("kind");
        p.uploadedAt = r.instant("uploaded_at");
        return p;
    }

    private static Payment payment(Row r) {
        Payment p = new Payment();
        p.paymentId = r.req("payment_id");
        p.invoiceId = r.req("invoice_id");
        p.teamId = r.req("team_id");
        p.customerId = r.str("customer_id");
        p.collectedAt = r.instant("collected_at");
        p.amountUsd = r.dbl("amount_usd");
        p.tipUsd = r.dbl("tip_usd");
        p.paymentMethod = r.lower("payment_method");
        p.cardFingerprint = r.str("card_fingerprint");
        p.cardCountry = r.str("card_country");
        p.cardType = r.lower("card_type");
        p.avsResult = r.lower("avs_result");
        p.cvvResult = r.lower("cvv_result");
        p.receiptSent = r.bool("receipt_sent");
        return p;
    }

    private static Dispute dispute(Row r) {
        Dispute d = new Dispute();
        d.disputeId = r.req("dispute_id");
        d.paymentId = r.req("payment_id");
        d.invoiceId = r.str("invoice_id");
        d.teamId = r.str("team_id");
        d.openedAt = r.instant("opened_at");
        d.reason = r.lower("reason");
        d.amountUsd = r.dbl("amount_usd");
        d.merchantResponded = r.bool("merchant_responded");
        d.evidenceSubmitted = new ArrayList<>();
        String ev = r.str("evidence_submitted");
        if (ev != null) {
            for (String token : ev.split(";")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) d.evidenceSubmitted.add(t);
            }
        }
        d.outcome = r.lower("outcome");
        d.closedAt = r.instant("closed_at");
        return d;
    }

    // ---------------------------------------------------------------- value parsing

    private static final DateTimeFormatter LOCAL_DT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try { return Instant.parse(v); } catch (DateTimeException ignored) { }
        try { return OffsetDateTime.parse(v).toInstant(); } catch (DateTimeException ignored) { }
        try { return OffsetDateTime.parse(v.replace(' ', 'T')).toInstant(); } catch (DateTimeException ignored) { }
        String noZone = v.endsWith("Z") ? v.substring(0, v.length() - 1) : v;
        noZone = noZone.replaceAll("[+-]00:?00$", "");
        try { return LocalDateTime.parse(noZone, LOCAL_DT).toInstant(ZoneOffset.UTC); } catch (DateTimeException ignored) { }
        throw new IllegalArgumentException("unreadable timestamp '" + s + "'");
    }

    static boolean parseBool(String s) {
        if (s == null || s.isBlank()) return false;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "1.0", "yes", "y" -> true;
            case "false", "f", "0", "0.0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("unreadable boolean '" + s + "'");
        };
    }

    private static InputStream stripBom(InputStream in) throws IOException {
        PushbackInputStream p = new PushbackInputStream(in, 3);
        byte[] b = new byte[3];
        int n = p.read(b, 0, 3);
        if (n == 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) return p;
        if (n > 0) p.unread(b, 0, n);
        return p;
    }

    /** Column access by canonical (lower-case) name. */
    private static final class Row {
        private final CSVRecord rec;
        private final Map<String, String> canonical;

        Row(CSVRecord rec, Map<String, String> canonical) {
            this.rec = rec;
            this.canonical = canonical;
        }

        String str(String col) {
            String actual = canonical.get(col);
            if (actual == null || !rec.isMapped(actual) || !rec.isSet(actual)) return null;
            String v = rec.get(actual);
            return v == null || v.isBlank() ? null : v.trim();
        }

        String req(String col) {
            String v = str(col);
            if (v == null) throw new IllegalArgumentException(col + " is empty");
            return v;
        }

        String lower(String col) {
            String v = str(col);
            return v == null ? null : v.toLowerCase(Locale.ROOT);
        }

        double dbl(String col) {
            String v = str(col);
            if (v == null) return 0;
            try { return Double.parseDouble(v.replace("$", "").replace(",", "")); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(col + " is not a number: '" + v + "'"); }
        }

        int integer(String col) {
            return (int) Math.round(dbl(col));
        }

        boolean bool(String col) {
            try { return parseBool(str(col)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(col + ": " + e.getMessage()); }
        }

        Instant instant(String col) {
            try { return parseInstant(str(col)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(col + ": " + e.getMessage()); }
        }
    }
}
