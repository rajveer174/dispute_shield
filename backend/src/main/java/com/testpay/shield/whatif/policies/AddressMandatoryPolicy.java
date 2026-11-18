package com.testpay.shield.whatif.policies;

import com.testpay.shield.features.JobContext;
import com.testpay.shield.whatif.WhatIfPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Required by the brief: customer address mandatory on every invoice. */
@Component
@Order(1)
public class AddressMandatoryPolicy implements WhatIfPolicy {

    private final double smallJobThreshold;
    private final double smallJobLossFraction;

    public AddressMandatoryPolicy(
            @Value("${shield.policies.address-mandatory.small-job-threshold-usd:300}") double smallJobThreshold,
            @Value("${shield.policies.address-mandatory.small-job-loss-fraction:0.03}") double smallJobLossFraction) {
        this.smallJobThreshold = smallJobThreshold;
        this.smallJobLossFraction = smallJobLossFraction;
    }

    @Override public String id() { return "address_mandatory"; }

    @Override public String label() { return "Customer address mandatory on every invoice"; }

    @Override public String description() {
        return "Invoices cannot be sent without the customer's service address.";
    }

    @Override public List<String> assumptions() {
        return List.of(
                String.format("About %.0f%% of jobs under $%.0f would no longer be invoiced through TestPay (brief). "
                        + "Range uses half to 1.5 times that.", smallJobLossFraction * 100, smallJobThreshold),
                "The lost jobs are a random sample of jobs under the threshold, so they take their share of disputes with them.",
                "Jobs that stay and lacked an address get one; everything else about the job is unchanged.");
    }

    @Override public boolean affects(JobContext job) {
        return !job.invoice.customerAddressPresent;
    }

    @Override public void apply(JobContext job) {
        job.invoice.customerAddressPresent = true;
    }

    @Override public double jobLossFraction(JobContext job) {
        return job.invoice.amountUsd < smallJobThreshold ? smallJobLossFraction : 0;
    }
}
