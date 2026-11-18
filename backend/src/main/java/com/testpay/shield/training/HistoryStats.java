package com.testpay.shield.training;

import java.util.*;

/** Historical (months 1-22) aggregates for the portfolio and team views. */
public class HistoryStats {

    public static class ReasonStats {
        public String reason;
        public String label;
        public int disputes;
        public int won;
        public int lost;
        public int withdrawn;
        public int open;
        /** won (incl. withdrawn if configured) / resolved. */
        public double winRate;
        public double disputedUsd;
        /** Amount + fee on lost disputes. */
        public double usdLost;
    }

    public static class MonthStats {
        public int month;
        public String label;
        public int payments;
        public double volumeUsd;
        public int disputes;
        public int lost;
        public double usdLost;
    }

    public static class Gap {
        public String ruleId;
        public String label;
        public String action;
        /** Missing share among jobs where the evidence was available to the team. */
        public double teamMissingRate;
        /** Jobs where this evidence could have been collected (e.g. signature after the team adopted it). */
        public int eligibleJobs;
        public double platformMissingRate;
        /** Lost disputes at this team whose invoice lacked this evidence. */
        public int lostDisputesMissing;
        /** Average change in win probability if present, from the payment-win model. */
        public double winUplift;
        /** teamMissingRate x winUplift: used to rank gaps. */
        public double impact;
    }

    public static class TeamStats {
        public String teamId;
        public String profession;
        public String state;
        public int teamSize;
        public int payments;
        public double volumeUsd;
        public int disputes;
        public int won;
        public int lost;
        public int withdrawn;
        public int open;
        public double disputeRate;
        public double winRate;
        public double usdLost;
        public Map<String, Integer> disputesByReason = new LinkedHashMap<>();
        public List<Gap> gaps = new ArrayList<>();
    }

    public int payments;
    public double volumeUsd;
    public int disputes;
    public int won;
    public int lost;
    public double winRate;
    public double usdLost;
    public List<ReasonStats> byReason = new ArrayList<>();
    public List<MonthStats> byMonth = new ArrayList<>();
    public Map<String, Double> platformMissingRates = new LinkedHashMap<>();
    public Map<String, TeamStats> teams = new LinkedHashMap<>();
}
