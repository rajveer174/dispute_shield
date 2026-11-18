package com.testpay.shield.whatif;

import com.testpay.shield.features.JobContext;

import java.util.List;

/**
 * A product policy to evaluate. To add a policy, implement this interface as a Spring {@code @Component}
 * and retrain; it then appears in the what-if page and in whatif_results.csv automatically.
 */
public interface WhatIfPolicy {

    /** Short name used in exports, e.g. address_mandatory. */
    String id();

    String label();

    String description();

    /** Business assumptions shown next to the result. */
    List<String> assumptions();

    /** Would this policy change how this job is documented or paid? */
    boolean affects(JobContext job);

    /** Change the (copied) job as if the policy had applied. */
    void apply(JobContext job);

    /** Fraction of jobs like this one that would no longer go through TestPay (0 if none). */
    default double jobLossFraction(JobContext job) {
        return 0;
    }

    /** Multipliers on the job-loss fraction for the range, e.g. {0.5, 1.5}. */
    default double[] jobLossUncertainty() {
        return new double[]{0.5, 1.5};
    }
}
