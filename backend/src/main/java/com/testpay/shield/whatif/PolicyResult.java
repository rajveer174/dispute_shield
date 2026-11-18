package com.testpay.shield.whatif;

import java.util.ArrayList;
import java.util.List;

/** Estimated effect of one policy over months 1-22. */
public class PolicyResult {

    public static class MetricChange {
        /** disputes, disputes_lost or usd_lost */
        public String metric;
        public double baseline;
        public double estimatedChange;
        public double low;
        public double high;
        /** Part of the change that comes from jobs leaving TestPay (side effect), at the point estimate. */
        public double fromLostJobs;
        /** Part that comes from better evidence / lower risk on the jobs that stay. */
        public double fromBehaviourChange;

        public MetricChange() {}

        public MetricChange(String metric, double baseline, double est, double low, double high,
                            double fromLostJobs, double fromBehaviourChange) {
            this.metric = metric;
            this.baseline = baseline;
            this.estimatedChange = est;
            this.low = low;
            this.high = high;
            this.fromLostJobs = fromLostJobs;
            this.fromBehaviourChange = fromBehaviourChange;
        }
    }

    public String policy;
    public String label;
    public String description;
    public List<String> assumptions = new ArrayList<>();
    public List<String> method = new ArrayList<>();
    public int affectedPayments;
    public int affectedDisputes;
    public double affectedVolumeUsd;
    public double jobsLost;
    public List<MetricChange> metrics = new ArrayList<>();
    /** high, medium or low */
    public String confidence;
    public String confidenceReason;
}
