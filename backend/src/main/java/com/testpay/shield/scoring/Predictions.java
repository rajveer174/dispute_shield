package com.testpay.shield.scoring;

import java.util.ArrayList;
import java.util.List;

/** Output DTOs for the per-payment and per-dispute views. Probabilities are null when not published. */
public final class Predictions {
    private Predictions() {}

    public static class Driver {
        public String feature;
        public String label;
        /** Contribution to the log-odds; positive raises the risk. */
        public double effect;

        public Driver(String feature, String label, double effect) {
            this.feature = feature;
            this.label = label;
            this.effect = effect;
        }
    }

    public static class Action {
        public String ruleId;
        public String label;
        public String action;
        /** "now" if it can still be fixed for this payment, "next_time" otherwise. */
        public String when;
        /** Change in win probability if this evidence were present. */
        public double winUplift;
        /** Expected dollars saved on this payment = P(dispute) x uplift x (amount + fee). */
        public double valueUsd;
    }

    /** One rule and whether this record meets it. */
    public static class RuleCheck {
        public String id;
        public String label;
        public boolean met;
        /** What to do when not met; null when there is nothing to do. */
        public String action;
    }

    /** The three-rule result for one moment. */
    public static class RuleResult {
        public String moment;
        public List<RuleCheck> rules = new ArrayList<>();
        public int rulesMet;
        /** Dispute win probability from the rule table; null if the bundle has no rule tables. */
        public Double winProb;
    }

    public static class PaymentPrediction {
        public String paymentId;
        public String invoiceId;
        public String teamId;
        public String customerId;
        public String collectedAt;
        public double amountUsd;
        public String paymentMethod;
        public String jobType;
        public boolean invoiceMissing;
        public Double invoiceWinProb;
        public Double paymentWinProb;
        public Double disputeRiskScore;
        public String disputeRiskTier;
        public Double expectedLossUsd;
        public List<String> missingEvidence = new ArrayList<>();
        public List<Driver> riskDrivers = new ArrayList<>();
        public List<Action> actions = new ArrayList<>();
        public String headline;
        public boolean disputed;
        public RuleResult invoiceRules;
        public RuleResult paymentRules;
    }

    public static class EvidenceSuggestion {
        public String token;
        public String label;
        public double winUplift;

        public EvidenceSuggestion(String token, String label, double winUplift) {
            this.token = token;
            this.label = label;
            this.winUplift = winUplift;
        }
    }

    public static class DisputePrediction {
        public String disputeId;
        public String paymentId;
        public String invoiceId;
        public String teamId;
        public String reason;
        public String reasonLabel;
        public String reasonGuidance;
        public String openedAt;
        public double amountUsd;
        public boolean merchantResponded;
        public List<String> evidenceSubmitted = new ArrayList<>();
        public boolean paymentFound;
        /** Win probability with the evidence as submitted. */
        public Double winProb;
        /** Win probability if the merchant responds with this evidence (equals winProb if already responded). */
        public Double winProbIfResponded;
        /** "model" (full dispute-win model) or "rules" (fallback when the model is not published). */
        public String winSource;
        public String recommendation;
        /** p x (amount + fee) - fight cost: what fighting is expected to gain over accepting. */
        public double expectedGainUsd;
        public double expectedValueFight;
        public double expectedValueAccept;
        public String rationale;
        public List<EvidenceSuggestion> suggestedEvidence = new ArrayList<>();
        public List<Driver> drivers = new ArrayList<>();
        public RuleResult disputeRules;
    }
}
