package com.testpay.shield.rules;

import com.testpay.shield.domain.Records.Dispute;
import com.testpay.shield.domain.Records.Invoice;
import com.testpay.shield.domain.Records.Payment;
import com.testpay.shield.features.FeatureExtractor;
import com.testpay.shield.features.JobContext;
import com.testpay.shield.model.Metrics;

import java.util.*;

/**
 * Yes/no rules per moment (three for invoice and payment, four for a received dispute) that answer
 * "will we win if this is disputed?". Each yes/no combination gets the win rate of past disputes with that combination, pulled toward the overall rate
 * when the combination is rare, and meeting one more rule never lowers the probability.
 * Rules were chosen with tools/rules/search.mjs; the tables are learned here, at training time.
 */
public final class WinRules {
    private WinRules() {}

    public static final String INVOICE = "invoice_ready";
    public static final String PAYMENT = "payment_collected";
    public static final String DISPUTE = "dispute_received";
    public static final List<String> MOMENTS = List.of(INVOICE, PAYMENT, DISPUTE);

    /** Shrinkage toward the overall rate, in pseudo-disputes. */
    private static final int SHRINK = 20;
    private static final int PAPERWORK_MIN = 3;
    private static final int EVIDENCE_MIN = 4;
    private static final String LATE_PHOTOS = "photos_added_after_dispute";
    private static final Set<String> NOT_EVIDENCE = Set.of("none", LATE_PHOTOS);

    /** id, short column name, plain label, and what to do when it is not met (null = nothing to do). */
    public record Rule(String id, String column, String label, String action) {}

    public static final Map<String, List<Rule>> RULES = Map.of(
            INVOICE, List.of(
                    new Rule("signed_estimate", "rule1_signed_estimate", "Signed estimate",
                            "Get the customer to sign the estimate"),
                    new Rule("customer_address", "rule2_customer_address", "Customer address on the invoice",
                            "Add the customer's address to the invoice"),
                    new Rule("gps_or_photo", "rule3_gps_or_photo", "GPS check-in or at least one job photo",
                            "Check in with GPS at the job or add a job photo")),
            PAYMENT, List.of(
                    new Rule("paperwork_3_of_6", "rule1_paperwork_3_of_6",
                            "At least 3 of 6 invoice items (estimate, terms, address, GPS, photo, 80+ character description)",
                            "Complete the invoice: signed estimate, terms, address, GPS check-in, a photo, a full description"),
                    new Rule("signed_estimate", "rule2_signed_estimate", "Signed estimate",
                            "Get the customer to sign the estimate"),
                    new Rule("card_verified", "rule3_card_verified",
                            "Card verified: tapped or chip, or address and security code both matched",
                            "Take card payments by tap or chip; if keyed in, check address and security code")),
            DISPUTE, List.of(
                    new Rule("responded", "rule1_responded", "Team responded to the dispute",
                            "Respond to the dispute before the deadline"),
                    new Rule("evidence_4_plus", "rule2_evidence_4_plus", "At least 4 pieces of evidence submitted",
                            "Submit at least 4 pieces of evidence"),
                    new Rule("not_fraud", "rule3_not_fraud", "Reason is not fraud", null),
                    new Rule("no_late_photos", "rule4_no_late_photos", "No photos added after the dispute opened",
                            "Take job photos at the job; photos added after a dispute opens lower the win chance")));

    // ------------------------------------------------------------------ evaluating the rules

    public static boolean[] evaluate(String moment, JobContext c, Dispute d) {
        Invoice i = c.invoice;
        Payment p = c.payment;
        return switch (moment) {
            case INVOICE -> new boolean[]{i.signedEstimate, i.customerAddressPresent, gpsOrPhoto(c)};
            case PAYMENT -> new boolean[]{paperwork(c) >= PAPERWORK_MIN, i.signedEstimate, cardVerified(p)};
            case DISPUTE -> new boolean[]{d.merchantResponded, evidenceCount(d) >= EVIDENCE_MIN,
                    !"fraud".equalsIgnoreCase(d.reason), d.evidenceSubmitted == null || !d.evidenceSubmitted.contains(LATE_PHOTOS)};
            default -> throw new IllegalArgumentException(moment);
        };
    }

    private static boolean gpsOrPhoto(JobContext c) {
        return c.invoice.techGpsCheckin || c.photosBefore + c.photosAfter > 0;
    }

    /** Six things a team controls on the invoice. */
    public static int paperwork(JobContext c) {
        Invoice i = c.invoice;
        int n = 0;
        if (i.signedEstimate) n++;
        if (i.termsAccepted) n++;
        if (i.customerAddressPresent) n++;
        if (i.techGpsCheckin) n++;
        if (c.photosBefore + c.photosAfter > 0) n++;
        if (i.jobDescriptionChars >= FeatureExtractor.DETAILED_DESCRIPTION_CHARS) n++;
        return n;
    }

    private static boolean cardVerified(Payment p) {
        String m = p.paymentMethod == null ? "" : p.paymentMethod;
        return m.equals("tap") || m.equals("chip") || ("match".equals(p.avsResult) && "match".equals(p.cvvResult));
    }

    private static int evidenceCount(Dispute d) {
        if (d.evidenceSubmitted == null) return 0;
        return (int) d.evidenceSubmitted.stream().filter(e -> !NOT_EVIDENCE.contains(e)).distinct().count();
    }

    public static String key(boolean[] met) {
        StringBuilder s = new StringBuilder();
        for (boolean b : met) s.append(b ? '1' : '0');
        return s.toString();
    }

    // ------------------------------------------------------------------ learning the tables

    /** Win rate if a rule is met / not met, over the training disputes. */
    public static class RuleStat {
        public String label;
        public double share;
        public double winIfYes;
        public double winIfNo;
    }

    /** The learned probability table for one moment. Public fields so it ships inside the model bundle. */
    public static class Table {
        public String moment;
        public double baseRate;
        public int trainRows;
        /** AUC on the last months after learning on the earlier ones; NaN if there was no time split. */
        public double testAuc = Double.NaN;
        public int testRows;
        /** "101" -> past disputes with that combination. */
        public Map<String, Integer> counts = new TreeMap<>();
        /** "101" -> win probability. */
        public Map<String, Double> probability = new TreeMap<>();
        public List<RuleStat> stats = new ArrayList<>();

        public double probability(boolean[] met) {
            return probability.getOrDefault(key(met), baseRate);
        }
    }

    /**
     * @param keys  rule combination per training dispute
     * @param y     1 = won, 0 = lost
     * @param month dispute month, for the honest test on the last {@code testMonths}
     */
    public static Table fit(String moment, List<boolean[]> keys, int[] y, int[] month, int testMonths) {
        Table t = build(moment, keys, y);
        int max = Arrays.stream(month).max().orElse(0), cut = max - testMonths;
        List<boolean[]> trK = new ArrayList<>();
        List<Integer> trY = new ArrayList<>(), teIdx = new ArrayList<>();
        for (int i = 0; i < y.length; i++) {
            if (month[i] <= cut) { trK.add(keys.get(i)); trY.add(y[i]); }
            else teIdx.add(i);
        }
        if (!trK.isEmpty() && !teIdx.isEmpty()) {
            Table early = build(moment, trK, trY.stream().mapToInt(Integer::intValue).toArray());
            double[] p = new double[teIdx.size()];
            int[] yy = new int[teIdx.size()];
            for (int k = 0; k < p.length; k++) {
                p[k] = early.probability(keys.get(teIdx.get(k)));
                yy[k] = y[teIdx.get(k)];
            }
            t.testAuc = Metrics.auc(p, yy);
            t.testRows = p.length;
        }
        return t;
    }

    private static Table build(String moment, List<boolean[]> keys, int[] y) {
        Table t = new Table();
        t.moment = moment;
        t.trainRows = y.length;
        int n = RULES.get(moment).size();
        double wins = Arrays.stream(y).sum();
        t.baseRate = y.length == 0 ? 0 : wins / y.length;
        Map<String, double[]> cells = new HashMap<>();
        for (int i = 0; i < y.length; i++) {
            double[] c = cells.computeIfAbsent(key(keys.get(i)), k -> new double[2]);
            c[0]++;
            c[1] += y[i];
        }
        // Raw shrunk rate per seen combination, then "more rules met never lowers it".
        Map<String, Double> raw = new HashMap<>();
        cells.forEach((k, c) -> raw.put(k, (c[1] + SHRINK * t.baseRate) / (c[0] + SHRINK)));
        // A combination takes the best rate among the seen combinations it contains. One that contains no seen
        // combination takes the lowest of the combinations one rule above it (and never more than the overall
        // rate), filled from the most rules met down, so the table stays monotone with empty cells too.
        List<String> all = new ArrayList<>();
        for (int m = 0; m < (1 << n); m++) all.add(String.format("%" + n + "s", Integer.toBinaryString(m)).replace(' ', '0'));
        all.sort(Comparator.comparingLong((String k) -> k.chars().filter(ch -> ch == '1').count()).reversed());
        for (String k : all) {
            double best = -1;
            for (Map.Entry<String, Double> e : raw.entrySet()) if (subsetOf(e.getKey(), k)) best = Math.max(best, e.getValue());
            if (best < 0) {
                best = t.baseRate;
                for (int r = 0; r < n; r++) {
                    if (k.charAt(r) == '1') continue;
                    String up = k.substring(0, r) + '1' + k.substring(r + 1);
                    best = Math.min(best, t.probability.get(up));
                }
            }
            t.counts.put(k, cells.containsKey(k) ? (int) cells.get(k)[0] : 0);
            t.probability.put(k, best);
        }
        for (int r = 0; r < n; r++) {
            double ny = 0, wy = 0, nn = 0, wn = 0;
            for (int i = 0; i < y.length; i++) {
                if (keys.get(i)[r]) { ny++; wy += y[i]; } else { nn++; wn += y[i]; }
            }
            RuleStat s = new RuleStat();
            s.label = RULES.get(moment).get(r).label();
            s.share = y.length == 0 ? 0 : ny / y.length;
            s.winIfYes = ny == 0 ? Double.NaN : wy / ny;
            s.winIfNo = nn == 0 ? Double.NaN : wn / nn;
            t.stats.add(s);
        }
        return t;
    }

    private static boolean subsetOf(String a, String b) {
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) == '1' && b.charAt(i) != '1') return false;
        return true;
    }
}
