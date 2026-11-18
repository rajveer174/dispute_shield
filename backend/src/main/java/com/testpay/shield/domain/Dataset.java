package com.testpay.shield.domain;

import com.testpay.shield.domain.Records.*;

import java.time.Instant;
import java.util.*;

/** In-memory joined view of the CSV files. */
public class Dataset {
    public final Map<String, Team> teams = new LinkedHashMap<>();
    public final Map<String, Invoice> invoices = new LinkedHashMap<>();
    public final Map<String, List<Photo>> photosByInvoice = new HashMap<>();
    public final List<Payment> payments = new ArrayList<>();
    public final List<Dispute> disputes = new ArrayList<>();
    public final Map<String, List<Dispute>> disputesByPayment = new HashMap<>();
    public int photoCount = 0;

    public void addPhoto(Photo p) {
        photosByInvoice.computeIfAbsent(p.invoiceId, k -> new ArrayList<>()).add(p);
        photoCount++;
    }

    public void addDispute(Dispute d) {
        disputes.add(d);
        disputesByPayment.computeIfAbsent(d.paymentId, k -> new ArrayList<>()).add(d);
    }

    /** First dispute on a payment (payments are very rarely disputed twice). */
    public Dispute firstDispute(String paymentId) {
        List<Dispute> list = disputesByPayment.get(paymentId);
        if (list == null || list.isEmpty()) return null;
        return list.stream().min(Comparator.comparing(d -> d.openedAt == null ? Instant.MAX : d.openedAt)).orElse(null);
    }

    /** Photo counts as they were at {@code asOf}: rebuilds the invoice snapshot at payment time. */
    public int[] photoCountsAsOf(Invoice inv, Instant asOf) {
        List<Photo> list = photosByInvoice.get(inv.invoiceId);
        if (photoCount == 0 || list == null) {
            // No photos file content at all: fall back to the counts on the invoice row.
            if (photoCount == 0) return new int[]{inv.photosBeforeCount, inv.photosAfterCount};
            return new int[]{0, 0};
        }
        int before = 0, after = 0;
        for (Photo p : list) {
            if (asOf != null && p.uploadedAt != null && p.uploadedAt.isAfter(asOf)) continue;
            if ("before".equalsIgnoreCase(p.kind)) before++;
            else if ("after".equalsIgnoreCase(p.kind)) after++;
        }
        return new int[]{before, after};
    }
}
