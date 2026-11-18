package com.testpay.shield.session;

import com.testpay.shield.domain.Dataset;
import com.testpay.shield.features.HistoryIndex;
import com.testpay.shield.scoring.Predictions.DisputePrediction;
import com.testpay.shield.scoring.Predictions.PaymentPrediction;

import java.time.Instant;
import java.util.*;

/** One evaluation run: step 1 (invoices, photos, payments) then step 2 (disputes). Kept in memory only. */
public class UploadSession {
    public final String id;
    public final Instant createdAt = Instant.now();
    public volatile Instant lastAccess = Instant.now();

    public Dataset data;
    public HistoryIndex history;
    public List<PaymentPrediction> payments = List.of();
    public Map<String, PaymentPrediction> paymentById = Map.of();
    public List<DisputePrediction> disputes = List.of();
    public final List<String> warnings = new ArrayList<>();
    public final Map<String, Integer> fileRows = new LinkedHashMap<>();

    public UploadSession(String id) {
        this.id = id;
    }

    public boolean hasDisputes() {
        return !disputes.isEmpty();
    }
}
