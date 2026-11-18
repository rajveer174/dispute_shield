package com.testpay.shield.features;

import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.Dispute;
import com.testpay.shield.domain.Records.Invoice;
import com.testpay.shield.domain.Records.Payment;

import java.time.Instant;
import java.util.*;

/**
 * Cross-team history of cards and customers, queried "as of" a moment so features never see the future.
 * The months 1-22 index is stored in the model bundle; at scoring time it is extended with the uploaded
 * files (strictly earlier events only, so it is not retraining, just history lookup).
 * Public fields so Jackson can serialise it.
 */
public class HistoryIndex {

    /** card -> team -> first payment time (epoch seconds). */
    public Map<String, Map<String, Long>> cardFirstUseByTeam = new HashMap<>();
    public Map<String, long[]> cardDisputes = new HashMap<>();
    public Map<String, long[]> cardFraudDisputes = new HashMap<>();
    public Map<String, long[]> customerDisputes = new HashMap<>();
    /** team -> time of its first signed job (epoch seconds): when it adopted completion signatures. */
    public Map<String, Long> teamFirstSignature = new HashMap<>();

    public static HistoryIndex build(Dataset... datasets) {
        return new HistoryIndex().extend(datasets);
    }

    /** Returns a new index with the events of the given datasets added. This index is not modified. */
    public HistoryIndex extend(Dataset... datasets) {
        HistoryIndex h = new HistoryIndex();
        cardFirstUseByTeam.forEach((k, v) -> h.cardFirstUseByTeam.put(k, new HashMap<>(v)));
        Map<String, List<Long>> cd = toLists(cardDisputes), cf = toLists(cardFraudDisputes), cu = toLists(customerDisputes);

        h.teamFirstSignature = new HashMap<>(teamFirstSignature);
        Map<String, Payment> paymentById = new HashMap<>();
        for (Dataset ds : datasets) {
            if (ds == null) continue;
            for (Invoice inv : ds.invoices.values()) {
                if (!inv.completionSignature || inv.teamId == null) continue;
                Instant t = inv.jobCompletedAt != null ? inv.jobCompletedAt : inv.createdAt;
                if (t != null) h.teamFirstSignature.merge(inv.teamId, t.getEpochSecond(), Math::min);
            }
            for (Payment p : ds.payments) {
                paymentById.put(p.paymentId, p);
                if (p.cardFingerprint != null && p.collectedAt != null && p.teamId != null) {
                    h.cardFirstUseByTeam.computeIfAbsent(p.cardFingerprint, k -> new HashMap<>())
                            .merge(p.teamId, p.collectedAt.getEpochSecond(), Math::min);
                }
            }
        }
        for (Dataset ds : datasets) {
            if (ds == null) continue;
            for (Dispute d : ds.disputes) {
                if (d.openedAt == null) continue;
                Payment p = paymentById.get(d.paymentId);
                if (p == null) continue;
                long t = d.openedAt.getEpochSecond();
                if (p.cardFingerprint != null) {
                    cd.computeIfAbsent(p.cardFingerprint, k -> new ArrayList<>()).add(t);
                    if ("fraud".equals(d.reason)) cf.computeIfAbsent(p.cardFingerprint, k -> new ArrayList<>()).add(t);
                }
                if (p.customerId != null) cu.computeIfAbsent(p.customerId, k -> new ArrayList<>()).add(t);
            }
        }
        h.cardDisputes = toSorted(cd);
        h.cardFraudDisputes = toSorted(cf);
        h.customerDisputes = toSorted(cu);
        return h;
    }

    private static Map<String, List<Long>> toLists(Map<String, long[]> in) {
        Map<String, List<Long>> out = new HashMap<>();
        in.forEach((k, v) -> {
            List<Long> l = new ArrayList<>(v.length);
            for (long x : v) l.add(x);
            out.put(k, l);
        });
        return out;
    }

    private static Map<String, long[]> toSorted(Map<String, List<Long>> in) {
        Map<String, long[]> out = new HashMap<>();
        in.forEach((k, v) -> {
            long[] a = v.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(a);
            out.put(k, a);
        });
        return out;
    }

    private static int countBefore(long[] arr, long t) {
        if (arr == null) return 0;
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < t) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    public int cardDisputesBefore(String card, Instant t) {
        return card == null || t == null ? 0 : countBefore(cardDisputes.get(card), t.getEpochSecond());
    }

    public int cardFraudDisputesBefore(String card, Instant t) {
        return card == null || t == null ? 0 : countBefore(cardFraudDisputes.get(card), t.getEpochSecond());
    }

    public int customerDisputesBefore(String customer, Instant t) {
        return customer == null || t == null ? 0 : countBefore(customerDisputes.get(customer), t.getEpochSecond());
    }

    /** Had this team signed any job strictly before {@code t}? */
    public boolean teamUsedSignatureBefore(String teamId, Instant t) {
        if (teamId == null || t == null) return false;
        Long first = teamFirstSignature.get(teamId);
        return first != null && first < t.getEpochSecond();
    }

    /** Number of other teams this physical card had paid before {@code t}. */
    public int cardOtherTeamsBefore(String card, String teamId, Instant t) {
        if (card == null || t == null) return 0;
        Map<String, Long> m = cardFirstUseByTeam.get(card);
        if (m == null) return 0;
        long ts = t.getEpochSecond();
        int n = 0;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!Objects.equals(e.getKey(), teamId) && e.getValue() < ts) n++;
        }
        return n;
    }
}
