package com.testpay.shield.scoring;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.*;
import com.testpay.shield.features.FeatureExtractor;
import com.testpay.shield.features.HistoryIndex;
import com.testpay.shield.features.JobContext;
import com.testpay.shield.model.LogisticModel;
import com.testpay.shield.model.ModelBundle;
import com.testpay.shield.rules.RuleRegistry;
import com.testpay.shield.rules.WinRules;
import com.testpay.shield.rules.RuleRegistry.EvidenceRule;
import com.testpay.shield.scoring.Predictions.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;

/** Scores uploaded payments and disputes with the frozen months 1-22 models. */
@Service
@Profile("!train")
public class ScoringService {

    private static final double MIN_ACTION_UPLIFT = 0.005;
    private static final int MAX_DRIVERS = 4;
    private static final int MAX_SUGGESTIONS = 5;

    private final ModelStore store;
    private final RuleRegistry rules;
    private final ShieldProperties props;

    public ScoringService(ModelStore store, RuleRegistry rules, ShieldProperties props) {
        this.store = store;
        this.rules = rules;
        this.props = props;
    }

    /** Builds the team fallback (teams.csv from training) as a dataset. */
    public Dataset trainingTeams() {
        Dataset d = new Dataset();
        for (Team t : store.bundle().teams) d.teams.put(t.teamId, t);
        return d;
    }

    // ------------------------------------------------------------------ payments

    public List<PaymentPrediction> scorePayments(Dataset upload, HistoryIndex history) {
        ModelBundle b = store.bundle();
        boolean pubInv = store.publishable(ModelBundle.INVOICE_WIN);
        boolean pubPay = store.publishable(ModelBundle.PAYMENT_WIN);
        boolean pubRisk = store.publishable(ModelBundle.DISPUTE_RISK);
        Dataset teams = trainingTeams();
        double fee = props.getEconomics().getDisputeFeeUsd();

        List<PaymentPrediction> out = new ArrayList<>(upload.payments.size());
        for (Payment p : upload.payments) {
            JobContext c = JobContext.of(p, upload, teams, history);
            Map<String, Double> xi = FeatureExtractor.invoiceStage(c);
            Map<String, Double> xp = FeatureExtractor.paymentStage(c);

            PaymentPrediction pp = new PaymentPrediction();
            pp.paymentId = p.paymentId;
            pp.invoiceId = p.invoiceId;
            pp.teamId = p.teamId;
            pp.customerId = p.customerId;
            pp.collectedAt = p.collectedAt == null ? null : p.collectedAt.toString();
            pp.amountUsd = p.amountUsd;
            pp.paymentMethod = p.paymentMethod;
            pp.jobType = c.invoice.jobType;
            pp.invoiceMissing = c.invoiceMissing;

            // Win chances come from the three-rule tables for each moment; the models are the fallback for
            // bundles trained before the rules existed. Dispute risk is not a win question, so it stays a model.
            pp.invoiceRules = ruleResult(b, WinRules.INVOICE, c, null);
            pp.paymentRules = ruleResult(b, WinRules.PAYMENT, c, null);
            boolean invRules = store.hasRules(WinRules.INVOICE), payRules = store.hasRules(WinRules.PAYMENT);
            double invWin = invRules ? pp.invoiceRules.winProb : b.invoiceWin.predict(xi);
            double payWin = payRules ? pp.paymentRules.winProb : b.paymentWin.predict(xp);
            double risk = b.disputeRisk.predict(xp);
            pp.invoiceWinProb = (invRules ? store.rulesPublishable(WinRules.INVOICE) : pubInv) ? round4(invWin) : null;
            pp.paymentWinProb = (payRules ? store.rulesPublishable(WinRules.PAYMENT) : pubPay) ? round4(payWin) : null;
            pp.disputeRiskScore = pubRisk ? round4(risk) : null;
            pp.disputeRiskTier = risk >= b.tierHigh ? "high" : risk >= b.tierMedium ? "medium" : "low";
            // Dollars at risk = P(dispute) x P(lose | dispute) x (amount + fee).
            pp.expectedLossUsd = round2(risk * (1 - payWin) * (p.amountUsd + fee));

            for (Map.Entry<String, Double> e : top(b.disputeRisk.contributions(xp), MAX_DRIVERS)) {
                pp.riskDrivers.add(new Driver(e.getKey(), FeatureLabels.label(e.getKey()), round4(e.getValue())));
            }
            pp.actions = actions(b.paymentWin, xp, risk, p.amountUsd + fee, pp.missingEvidence);
            pp.headline = headline(pp);
            out.add(pp);
        }
        return out;
    }

    private List<Action> actions(LogisticModel win, Map<String, Double> x, double risk, double atStake, List<String> missingOut) {
        double base = win.predict(x);
        List<Action> list = new ArrayList<>();
        for (EvidenceRule rule : rules.evidenceRules()) {
            if (x.getOrDefault(rule.feature(), 0.0) >= 1) continue;
            boolean unavailable = rule.unavailable(x);
            missingOut.add(unavailable ? rule.label() + " (not in use by team yet)" : rule.label());
            double uplift = win.predict(rule.withEvidence(x)) - base;
            if (uplift < MIN_ACTION_UPLIFT) continue;
            Action a = new Action();
            a.ruleId = rule.id();
            a.label = rule.label();
            a.action = unavailable && rule.unavailableAction() != null ? rule.unavailableAction() : rule.action();
            // Adopting a new tool is a change for future jobs, not something to fix on this payment.
            a.when = rule.fixableAfterPayment() && !unavailable ? "now" : "next_time";
            a.winUplift = round4(uplift);
            a.valueUsd = round2(risk * uplift * atStake);
            list.add(a);
        }
        list.sort(Comparator.comparing((Action a) -> !"now".equals(a.when)).thenComparing(a -> -a.winUplift));
        return list;
    }

    private static String headline(PaymentPrediction pp) {
        StringBuilder s = new StringBuilder();
        s.append(Character.toUpperCase(pp.disputeRiskTier.charAt(0))).append(pp.disputeRiskTier.substring(1)).append(" risk");
        if (!pp.riskDrivers.isEmpty()) s.append(" (").append(pp.riskDrivers.get(0).label.toLowerCase(Locale.ROOT)).append(")");
        Optional<Action> now = pp.actions.stream().filter(a -> "now".equals(a.when)).findFirst();
        if (now.isPresent()) s.append(". Do now: ").append(now.get().action);
        else if (!pp.actions.isEmpty()) s.append(". Next time: ").append(pp.actions.get(0).action);
        else s.append(". Paperwork looks complete.");
        return s.toString();
    }

    // ------------------------------------------------------------------ disputes

    public List<DisputePrediction> scoreDisputes(Dataset upload, HistoryIndex history) {
        ModelBundle b = store.bundle();
        boolean pub = store.publishable(ModelBundle.DISPUTE_WIN);
        Dataset teams = trainingTeams();
        ShieldProperties.Economics econ = props.getEconomics();
        Map<String, Payment> payments = new HashMap<>();
        for (Payment p : upload.payments) payments.put(p.paymentId, p);

        List<String> evidenceVocab = new ArrayList<>();
        for (String f : b.disputeWin.features) if (f.startsWith("ev:")) evidenceVocab.add(f.substring(3));

        List<DisputePrediction> out = new ArrayList<>();
        for (Dispute d : upload.disputes) {
            Payment p = payments.get(d.paymentId);
            boolean found = p != null;
            if (!found) {
                p = new Payment();
                p.paymentId = d.paymentId;
                p.invoiceId = d.invoiceId;
                p.teamId = d.teamId;
                p.amountUsd = d.amountUsd;
            }
            JobContext c = JobContext.of(p, upload, teams, history);
            Map<String, Double> x = FeatureExtractor.disputeStage(c, d);
            // p for the fight/accept decision comes from the full dispute-win model, so each dispute is judged on
            // all its facts (evidence, card checks, team record, amount, reason). If the team has not responded
            // yet, p is the model's value with "responded" set. The rules are kept as the explanation, and are
            // the fallback only when the model did not clear the publishing bar.
            RuleResult rr = ruleResult(b, WinRules.DISPUTE, c, d);
            boolean useRules = !store.publishable(ModelBundle.DISPUTE_WIN) && store.hasRules(WinRules.DISPUTE);
            boolean pubHere = useRules ? store.rulesPublishable(WinRules.DISPUTE) : pub;
            double win, winIfResponded;
            if (useRules) {
                boolean[] met = WinRules.evaluate(WinRules.DISPUTE, c, d);
                WinRules.Table t = b.winRules.get(WinRules.DISPUTE);
                win = t.probability(met);
                met[0] = true;
                winIfResponded = d.merchantResponded ? win : t.probability(met);
            } else {
                win = b.disputeWin.predict(x);
                Map<String, Double> responded = new HashMap<>(x);
                responded.put("disp:responded", 1.0);
                winIfResponded = d.merchantResponded ? win : b.disputeWin.predict(responded);
            }

            DisputePrediction dp = new DisputePrediction();
            RuleRegistry.DisputeReason reason = rules.reason(d.reason);
            dp.disputeId = d.disputeId;
            dp.paymentId = d.paymentId;
            dp.invoiceId = d.invoiceId;
            dp.teamId = d.teamId != null ? d.teamId : p.teamId;
            dp.reason = d.reason;
            dp.reasonLabel = reason.label();
            dp.reasonGuidance = reason.guidance();
            dp.openedAt = d.openedAt == null ? null : d.openedAt.toString();
            dp.amountUsd = d.amountUsd;
            dp.merchantResponded = d.merchantResponded;
            dp.evidenceSubmitted = d.evidenceSubmitted;
            dp.paymentFound = found;
            dp.winProb = pubHere ? round4(win) : null;
            dp.winProbIfResponded = pubHere ? round4(winIfResponded) : null;
            dp.winSource = useRules ? "rules" : "model";

            // Accepting loses amount + fee. Fighting costs the team's time and recovers the money if won.
            double atStake = d.amountUsd + econ.getDisputeFeeUsd();
            double recovered = d.amountUsd + (econ.isFeeRecoveredOnWin() ? econ.getDisputeFeeUsd() : 0);
            double pFight = winIfResponded;
            dp.expectedValueAccept = round2(-atStake);
            dp.expectedValueFight = round2(-atStake + pFight * recovered - econ.getFightCostUsd());
            // Expected gain of fighting over accepting: p x (amount + fee) back, minus the cost of fighting.
            // Fight only when that gain clears the minimum, so the amount at stake drives the decision.
            double gain = pFight * recovered - econ.getFightCostUsd();
            boolean fight = gain >= econ.getMinExpectedGainUsd();
            dp.expectedGainUsd = round2(gain);
            dp.recommendation = fight ? "fight" : "accept";
            dp.rationale = rationale(dp, fight, pFight, recovered, econ.getFightCostUsd(), gain, econ.getMinExpectedGainUsd());

            for (Map.Entry<String, Double> e : top(b.disputeWin.contributions(x), MAX_DRIVERS)) {
                dp.drivers.add(new Driver(e.getKey(), FeatureLabels.label(e.getKey()), round4(e.getValue())));
            }
            dp.suggestedEvidence = suggestions(b.disputeWin, x, d, evidenceVocab);
            dp.disputeRules = rr;
            out.add(dp);
        }
        return out;
    }

    private static String rationale(DisputePrediction dp, boolean fight, double p, double recovered,
                                    double cost, double gain, double minGain) {
        String base = String.format(Locale.ROOT,
                "%.0f%% chance to win x $%.2f = $%.2f expected back, minus $%.2f to fight = $%.2f expected gain (minimum $%.2f).",
                p * 100, recovered, p * recovered, cost, gain, minGain);
        if (!dp.merchantResponded && fight) return "Respond before the deadline. " + base;
        if (!dp.merchantResponded) return "Not worth responding. " + base;
        return (fight ? "Worth fighting. " : "Not worth fighting. ") + base;
    }

    private static List<EvidenceSuggestion> suggestions(LogisticModel win, Map<String, Double> x, Dispute d, List<String> vocab) {
        Set<String> submitted = new HashSet<>(d.evidenceSubmitted == null ? List.of() : d.evidenceSubmitted);
        double base = win.predict(x);
        List<EvidenceSuggestion> list = new ArrayList<>();
        for (String token : vocab) {
            if (submitted.contains(token)) continue;
            Map<String, Double> y = new HashMap<>(x);
            y.put("ev:" + token, 1.0);
            if (d.reason != null) y.put("x:reason=" + d.reason + "|ev=" + token, 1.0);
            y.put("num:evidence_count", x.getOrDefault("num:evidence_count", 0.0) + 1);
            y.put("disp:responded", 1.0);
            double up = win.predict(y) - win.predict(withResponded(x));
            if (up > 0.01) list.add(new EvidenceSuggestion(token, FeatureLabels.evidenceLabel(token), round4(up)));
        }
        list.sort(Comparator.comparingDouble((EvidenceSuggestion s) -> s.winUplift).reversed());
        return list.size() > MAX_SUGGESTIONS ? new ArrayList<>(list.subList(0, MAX_SUGGESTIONS)) : list;
    }

    private static Map<String, Double> withResponded(Map<String, Double> x) {
        Map<String, Double> y = new HashMap<>(x);
        y.put("disp:responded", 1.0);
        return y;
    }

    // ------------------------------------------------------------------ three-rule win probability

    static RuleResult ruleResult(ModelBundle b, String moment, JobContext c, Dispute d) {
        boolean[] met = WinRules.evaluate(moment, c, d);
        RuleResult r = new RuleResult();
        r.moment = moment;
        List<WinRules.Rule> defs = WinRules.RULES.get(moment);
        for (int i = 0; i < met.length; i++) {
            RuleCheck rc = new RuleCheck();
            rc.id = defs.get(i).id();
            rc.label = defs.get(i).label();
            rc.met = met[i];
            rc.action = met[i] ? null : defs.get(i).action();
            r.rules.add(rc);
            if (met[i]) r.rulesMet++;
        }
        WinRules.Table t = b.winRules == null ? null : b.winRules.get(moment);
        r.winProb = t == null ? null : round4(t.probability(met));
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map.Entry<String, Double>> top(Map<String, Double> contrib, int k) {
        return contrib.entrySet().stream()
                .filter(e -> e.getValue() > 0.05)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k).toList();
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
