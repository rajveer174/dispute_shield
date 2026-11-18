package com.testpay.shield.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunable business and modelling assumptions live here (application.yml, prefix "shield"). */
@ConfigurationProperties(prefix = "shield")
public class ShieldProperties {

    /** Path of the trained model bundle that the web app loads at startup. */
    private String modelPath = "models/model_bundle.json.gz";
    private Economics economics = new Economics();
    private Training training = new Training();
    private Export export = new Export();
    private Session session = new Session();

    public static class Economics {
        /** Fee the team pays on a lost dispute. */
        private double disputeFeeUsd = 25;
        /** Team time cost of fighting a dispute. */
        private double fightCostUsd = 15;
        /** Fight only if p x (amount + fee) - fight cost is at least this many dollars. */
        private double minExpectedGainUsd = 25;
        /** Assumption: winning returns the fee as well as the amount. */
        private boolean feeRecoveredOnWin = true;

        public double getDisputeFeeUsd() { return disputeFeeUsd; }
        public void setDisputeFeeUsd(double v) { disputeFeeUsd = v; }
        public double getFightCostUsd() { return fightCostUsd; }
        public void setFightCostUsd(double v) { fightCostUsd = v; }
        public double getMinExpectedGainUsd() { return minExpectedGainUsd; }
        public void setMinExpectedGainUsd(double v) { minExpectedGainUsd = v; }
        public boolean isFeeRecoveredOnWin() { return feeRecoveredOnWin; }
        public void setFeeRecoveredOnWin(boolean v) { feeRecoveredOnWin = v; }
    }

    public static class Training {
        private String dataDir = "data";
        /** A withdrawn dispute means the merchant keeps the money, so it counts as a win. */
        private boolean withdrawnCountsAsWon = true;
        /**
         * Payments collected in the last N days of the training window have not had time to be disputed.
         * 0 = derive N from the observed payment-to-dispute lag at {@link #censorQuantile}.
         */
        private int censorDays = 0;
        private double censorQuantile = 0.95;
        /**
         * NONE, PAYMENT or DISPUTE_OPENED: drop invoices edited after this point from the win models.
         * Default NONE because the June 2026 status backfill rewrote updated_at (release notes).
         */
        private String leakageMode = "NONE";
        /** Last N months of each set are held out for time-based validation. */
        private int validationMonths = 4;
        private double l2 = 1.0;
        private int epochs = 400;
        private double highTierQuantile = 0.90;
        private double mediumTierQuantile = 0.70;
        private int bootstrapSamples = 300;
        /** Range of the causal discount applied to model-based policy effects (confounding). */
        private double causalDiscountLow = 0.5;
        private double causalDiscountHigh = 1.0;
        private long seed = 42;

        public String getDataDir() { return dataDir; }
        public void setDataDir(String v) { dataDir = v; }
        public boolean isWithdrawnCountsAsWon() { return withdrawnCountsAsWon; }
        public void setWithdrawnCountsAsWon(boolean v) { withdrawnCountsAsWon = v; }
        public int getCensorDays() { return censorDays; }
        public void setCensorDays(int v) { censorDays = v; }
        public double getCensorQuantile() { return censorQuantile; }
        public void setCensorQuantile(double v) { censorQuantile = v; }
        public String getLeakageMode() { return leakageMode; }
        public void setLeakageMode(String v) { leakageMode = v; }
        public int getValidationMonths() { return validationMonths; }
        public void setValidationMonths(int v) { validationMonths = v; }
        public double getL2() { return l2; }
        public void setL2(double v) { l2 = v; }
        public int getEpochs() { return epochs; }
        public void setEpochs(int v) { epochs = v; }
        public double getHighTierQuantile() { return highTierQuantile; }
        public void setHighTierQuantile(double v) { highTierQuantile = v; }
        public double getMediumTierQuantile() { return mediumTierQuantile; }
        public void setMediumTierQuantile(double v) { mediumTierQuantile = v; }
        public int getBootstrapSamples() { return bootstrapSamples; }
        public void setBootstrapSamples(int v) { bootstrapSamples = v; }
        public double getCausalDiscountLow() { return causalDiscountLow; }
        public void setCausalDiscountLow(double v) { causalDiscountLow = v; }
        public double getCausalDiscountHigh() { return causalDiscountHigh; }
        public void setCausalDiscountHigh(double v) { causalDiscountHigh = v; }
        public long getSeed() { return seed; }
        public void setSeed(long v) { seed = v; }
    }

    public static class Export {
        /** A model whose validation AUC is below this is treated as "cannot be predicted" and exported blank. */
        private double minAucToPublish = 0.55;

        public double getMinAucToPublish() { return minAucToPublish; }
        public void setMinAucToPublish(double v) { minAucToPublish = v; }
    }

    public static class Session {
        private int maxSessions = 50;
        private int ttlHours = 72;

        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int v) { maxSessions = v; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int v) { ttlHours = v; }
    }

    public String getModelPath() { return modelPath; }
    public void setModelPath(String v) { modelPath = v; }
    public Economics getEconomics() { return economics; }
    public void setEconomics(Economics v) { economics = v; }
    public Training getTraining() { return training; }
    public void setTraining(Training v) { training = v; }
    public Export getExport() { return export; }
    public void setExport(Export v) { export = v; }
    public Session getSession() { return session; }
    public void setSession(Session v) { session = v; }
}
