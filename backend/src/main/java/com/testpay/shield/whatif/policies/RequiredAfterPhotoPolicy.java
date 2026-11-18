package com.testpay.shield.whatif.policies;

import com.testpay.shield.features.JobContext;
import com.testpay.shield.whatif.WhatIfPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Require at least one after photo before the payment can be collected. */
@Component
@Order(4)
@ConditionalOnProperty(name = "shield.policies.required-after-photo.enabled", havingValue = "true", matchIfMissing = true)
public class RequiredAfterPhotoPolicy implements WhatIfPolicy {

    @Override public String id() { return "after_photo_required"; }

    @Override public String label() { return "After photo required before collecting payment"; }

    @Override public String description() {
        return "The technician must upload at least one photo of the finished work before taking payment.";
    }

    @Override public List<String> assumptions() {
        return List.of("No jobs are lost (brief: other policies have no side effects).",
                "Jobs without an after photo at payment time get one; nothing else changes.");
    }

    @Override public boolean affects(JobContext job) {
        return job.photosAfter == 0;
    }

    @Override public void apply(JobContext job) {
        job.photosAfter = Math.max(1, job.photosAfter);
    }
}
