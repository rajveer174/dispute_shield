package com.testpay.shield.whatif.policies;

import com.testpay.shield.features.JobContext;
import com.testpay.shield.whatif.WhatIfPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Require a signed estimate on invoices above a dollar amount. */
@Component
@Order(3)
public class SignedEstimateAbovePolicy implements WhatIfPolicy {

    private final double threshold;

    public SignedEstimateAbovePolicy(@Value("${shield.policies.signed-estimate-above.threshold-usd:500}") double threshold) {
        this.threshold = threshold;
    }

    @Override public String id() { return "signed_estimate_above_" + (long) threshold; }

    @Override public String label() { return String.format("Signed estimate required above $%,.0f", threshold); }

    @Override public String description() {
        return String.format("Invoices over $%,.0f need a signed estimate before work starts.", threshold);
    }

    @Override public List<String> assumptions() {
        return List.of("No jobs are lost (brief: other policies have no side effects).",
                "Jobs over the threshold without a signed estimate get one; nothing else changes.");
    }

    @Override public boolean affects(JobContext job) {
        return job.invoice.amountUsd > threshold && !job.invoice.signedEstimate;
    }

    @Override public void apply(JobContext job) {
        job.invoice.signedEstimate = true;
    }
}
