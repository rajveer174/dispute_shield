package com.testpay.shield.features;

import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.*;

import java.time.Instant;

/**
 * Everything known about one job at the moment its payment was collected. Invoice photo counts are
 * rebuilt from photos uploaded up to {@code collected_at}, so training sees the invoice the way the
 * uploaded months 23-24 files show it.
 */
public class JobContext {
    public Invoice invoice;
    public Payment payment;
    public Team team;
    public int photosBefore;
    public int photosAfter;
    /** True when the payment references an invoice we do not have. */
    public boolean invoiceMissing;
    /**
     * Could this job have had a completion signature? False before the Dec 2025 launch and for teams that had
     * not started using it yet (rollout was team by team and optional). A signed job is always "available".
     */
    public boolean signatureAvailable = true;

    // History counts, all strictly before collected_at.
    public int cardPriorDisputes;
    public int cardPriorFraudDisputes;
    public int cardPriorOtherTeams;
    public int customerPriorDisputes;

    public static JobContext of(Payment p, Dataset ds, Dataset fallbackTeams, HistoryIndex history) {
        JobContext c = new JobContext();
        c.payment = p;
        Invoice inv = ds.invoices.get(p.invoiceId);
        if (inv == null) {
            c.invoiceMissing = true;
            inv = new Invoice();
            inv.invoiceId = p.invoiceId;
            inv.teamId = p.teamId;
            inv.customerId = p.customerId;
            inv.amountUsd = p.amountUsd;
        }
        c.invoice = inv;
        Team t = ds.teams.get(p.teamId);
        if (t == null && fallbackTeams != null) t = fallbackTeams.teams.get(p.teamId);
        c.team = t;
        Instant asOf = p.collectedAt;
        int[] photos = c.invoiceMissing ? new int[]{0, 0} : ds.photoCountsAsOf(inv, asOf);
        c.photosBefore = photos[0];
        c.photosAfter = photos[1];
        c.signatureAvailable = signatureAvailable(inv, p, history);
        if (history != null) {
            c.cardPriorDisputes = history.cardDisputesBefore(p.cardFingerprint, asOf);
            c.cardPriorFraudDisputes = history.cardFraudDisputesBefore(p.cardFingerprint, asOf);
            c.cardPriorOtherTeams = history.cardOtherTeamsBefore(p.cardFingerprint, p.teamId, asOf);
            c.customerPriorDisputes = history.customerDisputesBefore(p.customerId, asOf);
        }
        return c;
    }

    private static boolean signatureAvailable(Invoice inv, Payment p, HistoryIndex history) {
        if (inv.completionSignature) return true;
        Instant jobTime = inv.jobCompletedAt != null ? inv.jobCompletedAt : inv.createdAt != null ? inv.createdAt : p.collectedAt;
        if (jobTime == null) return history == null;
        if (jobTime.isBefore(ReleaseCalendar.COMPLETION_SIGNATURE_LAUNCH)) return false;
        if (history == null) return true;
        // Adopted = the team had signed an earlier job (as of this job, no look-ahead).
        return history.teamUsedSignatureBefore(inv.teamId != null ? inv.teamId : p.teamId, jobTime);
    }

    /** Shallow copy with copied invoice and payment, for what-if policies to modify. */
    public JobContext copy() {
        JobContext c = new JobContext();
        c.invoice = invoice.copy();
        c.payment = payment.copy();
        c.team = team;
        c.photosBefore = photosBefore;
        c.photosAfter = photosAfter;
        c.invoiceMissing = invoiceMissing;
        c.signatureAvailable = signatureAvailable;
        c.cardPriorDisputes = cardPriorDisputes;
        c.cardPriorFraudDisputes = cardPriorFraudDisputes;
        c.cardPriorOtherTeams = cardPriorOtherTeams;
        c.customerPriorDisputes = customerPriorDisputes;
        return c;
    }
}
