package com.testpay.shield.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Plain data holders, one per CSV file. Fields are public and mutable on purpose: what-if policies
 * copy a record and change a field ("what if the address had been present") before re-scoring it.
 */
public final class Records {
    private Records() {}

    public static class Team {
        public String teamId;
        public String profession;
        public String state;
        public LocalDate onboardedOn;
        public int teamSize;
    }

    public static class Invoice {
        public String invoiceId;
        public String teamId;
        public String customerId;
        public Instant createdAt;
        public Instant jobCompletedAt;
        public String jobType;
        public double amountUsd;
        public int lineItemCount;
        public int jobDescriptionChars;
        public boolean customerAddressPresent;
        public boolean customerEmailPresent;
        public boolean customerPhonePresent;
        public boolean signedEstimate;
        public boolean termsAccepted;
        public boolean techGpsCheckin;
        public boolean completionSignature;
        public int photosBeforeCount;
        public int photosAfterCount;
        public String invoiceStatus;
        public Instant updatedAt;

        public Invoice copy() {
            Invoice c = new Invoice();
            c.invoiceId = invoiceId; c.teamId = teamId; c.customerId = customerId;
            c.createdAt = createdAt; c.jobCompletedAt = jobCompletedAt; c.jobType = jobType;
            c.amountUsd = amountUsd; c.lineItemCount = lineItemCount; c.jobDescriptionChars = jobDescriptionChars;
            c.customerAddressPresent = customerAddressPresent; c.customerEmailPresent = customerEmailPresent;
            c.customerPhonePresent = customerPhonePresent; c.signedEstimate = signedEstimate;
            c.termsAccepted = termsAccepted; c.techGpsCheckin = techGpsCheckin;
            c.completionSignature = completionSignature; c.photosBeforeCount = photosBeforeCount;
            c.photosAfterCount = photosAfterCount; c.invoiceStatus = invoiceStatus; c.updatedAt = updatedAt;
            return c;
        }
    }

    public static class Photo {
        public String photoId;
        public String invoiceId;
        public String kind;
        public Instant uploadedAt;
    }

    public static class Payment {
        public String paymentId;
        public String invoiceId;
        public String teamId;
        public String customerId;
        public Instant collectedAt;
        public double amountUsd;
        public double tipUsd;
        public String paymentMethod;
        public String cardFingerprint;
        public String cardCountry;
        public String cardType;
        public String avsResult;
        public String cvvResult;
        public boolean receiptSent;

        public Payment copy() {
            Payment c = new Payment();
            c.paymentId = paymentId; c.invoiceId = invoiceId; c.teamId = teamId; c.customerId = customerId;
            c.collectedAt = collectedAt; c.amountUsd = amountUsd; c.tipUsd = tipUsd;
            c.paymentMethod = paymentMethod; c.cardFingerprint = cardFingerprint; c.cardCountry = cardCountry;
            c.cardType = cardType; c.avsResult = avsResult; c.cvvResult = cvvResult; c.receiptSent = receiptSent;
            return c;
        }
    }

    public static class Dispute {
        public String disputeId;
        public String paymentId;
        public String invoiceId;
        public String teamId;
        public Instant openedAt;
        public String reason;
        public double amountUsd;
        public boolean merchantResponded;
        public List<String> evidenceSubmitted;
        /** won, lost, withdrawn, open; null for uploaded disputes. */
        public String outcome;
        public Instant closedAt;
    }
}
