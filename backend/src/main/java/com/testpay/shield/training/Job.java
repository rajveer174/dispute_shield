package com.testpay.shield.training;

import com.testpay.shield.domain.Records.Dispute;
import com.testpay.shield.features.JobContext;

/** One historical payment with its (first) dispute, if any. */
public record Job(JobContext ctx, Dispute dispute, int month) {}
