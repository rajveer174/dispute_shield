package com.testpay.shield.whatif.policies;

import com.testpay.shield.features.JobContext;
import com.testpay.shield.whatif.WhatIfPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Block keyed-in (card-not-present) payments above a dollar amount. */
@Component
@Order(2)
public class NoKeyedAbovePolicy implements WhatIfPolicy {

    private final double threshold;
    private final double jobLossFraction;

    public NoKeyedAbovePolicy(
            @Value("${shield.policies.no-keyed-above.threshold-usd:1000}") double threshold,
            @Value("${shield.policies.no-keyed-above.job-loss-fraction:0.08}") double jobLossFraction) {
        this.threshold = threshold;
        this.jobLossFraction = jobLossFraction;
    }

    @Override public String id() { return "no_keyed_above_" + (long) threshold; }

    @Override public String label() { return String.format("No keyed-in cards above $%,.0f", threshold); }

    @Override public String description() {
        return String.format("Keyed-in card payments over $%,.0f are blocked; the customer must tap or insert the card.", threshold);
    }

    @Override public List<String> assumptions() {
        return List.of(
                String.format("About %.0f%% of keyed jobs over $%,.0f would be lost (brief). Range uses half to 1.5 times that.",
                        jobLossFraction * 100, threshold),
                "The remaining jobs are paid by chip instead; card, customer and invoice stay the same.",
                "AVS/CVV results are left as recorded (we cannot know what a chip payment would have returned).");
    }

    @Override public boolean affects(JobContext job) {
        return "keyed".equals(job.payment.paymentMethod) && job.payment.amountUsd > threshold;
    }

    @Override public void apply(JobContext job) {
        job.payment.paymentMethod = "chip";
    }

    @Override public double jobLossFraction(JobContext job) {
        return affects(job) ? jobLossFraction : 0;
    }
}
