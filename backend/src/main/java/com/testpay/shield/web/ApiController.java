package com.testpay.shield.web;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.model.ModelBundle;
import com.testpay.shield.rules.RuleRegistry;
import com.testpay.shield.scoring.ModelInsights;
import com.testpay.shield.scoring.ModelStore;
import com.testpay.shield.scoring.Predictions.DisputePrediction;
import com.testpay.shield.scoring.Predictions.PaymentPrediction;
import com.testpay.shield.session.SessionService;
import com.testpay.shield.session.UploadSession;
import com.testpay.shield.whatif.PolicyResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@Profile("!train")
public class ApiController {

    private final ModelStore store;
    private final SessionService sessions;
    private final ViewService views;
    private final RuleRegistry rules;
    private final double minAuc;
    private final ShieldProperties.Economics econ;

    public ApiController(ModelStore store, SessionService sessions, ViewService views, RuleRegistry rules,
                         ShieldProperties props) {
        this.store = store;
        this.sessions = sessions;
        this.views = views;
        this.rules = rules;
        this.minAuc = props.getExport().getMinAucToPublish();
        this.econ = props.getEconomics();
    }

    // ---------------------------------------------------------------- status & models

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", store.isReady());
        if (!store.isReady()) {
            out.put("error", store.loadError());
            return out;
        }
        ModelBundle b = store.bundle();
        out.put("trainedAt", b.trainedAt);
        out.put("dataStart", b.dataStart);
        out.put("dataEnd", b.dataEnd);
        out.put("assumptions", liveAssumptions(b));
        Map<String, Boolean> pub = new LinkedHashMap<>();
        for (String k : List.of(ModelBundle.INVOICE_WIN, ModelBundle.PAYMENT_WIN, ModelBundle.DISPUTE_RISK, ModelBundle.DISPUTE_WIN)) {
            pub.put(k, store.publishable(k));
        }
        out.put("published", pub);
        out.put("policies", b.whatIf.stream().map(p -> Map.of("policy", p.policy, "label", p.label)).toList());
        out.put("reasons", rules.reasons());
        out.put("evidenceRules", rules.evidenceRules());
        return out;
    }

    /** Training assumptions, with the economics overridden by the current config (scoring reads those live). */
    private Map<String, Object> liveAssumptions(ModelBundle b) {
        Map<String, Object> a = new LinkedHashMap<>(b.assumptions);
        a.put("dispute_fee_usd", econ.getDisputeFeeUsd());
        a.put("fight_cost_usd", econ.getFightCostUsd());
        a.put("fee_recovered_on_win", econ.isFeeRecoveredOnWin());
        a.remove("min_win_prob_to_fight");
        a.put("min_expected_gain_usd", econ.getMinExpectedGainUsd());
        return a;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        ModelBundle b = store.bundle();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("validation", b.validation);
        out.put("chosenApproach", b.chosenApproach);
        out.put("notes", b.notes);
        out.put("dataFindings", b.dataFindings);
        out.put("assumptions", liveAssumptions(b));
        out.put("tierThresholds", Map.of("medium", b.tierMedium, "high", b.tierHigh));
        Map<String, Boolean> pub = new LinkedHashMap<>();
        Map<String, Object> drivers = new LinkedHashMap<>();
        for (String k : b.validation.keySet()) {
            pub.put(k, store.publishable(k));
            drivers.put(k, ModelInsights.drivers(b.model(k), 6));
        }
        out.put("published", pub);
        out.put("drivers", drivers);
        out.put("minAucToPublish", minAuc);
        out.put("winRules", b.winRules);
        out.put("winRuleDefinitions", com.testpay.shield.rules.WinRules.RULES);
        return out;
    }

    @GetMapping("/whatif")
    public List<PolicyResult> whatIf() {
        return store.bundle().whatIf;
    }

    @GetMapping("/whatif/{policy}")
    public PolicyResult whatIf(@PathVariable String policy) {
        return store.bundle().whatIf.stream().filter(p -> p.policy.equals(policy)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown policy " + policy));
    }

    // ---------------------------------------------------------------- upload

    @PostMapping(value = "/sessions", consumes = "multipart/form-data")
    public Map<String, Object> createSession(@RequestParam(value = "invoices", required = false) MultipartFile invoices,
                                             @RequestParam(value = "payments", required = false) MultipartFile payments,
                                             @RequestParam(value = "photos", required = false) MultipartFile photos,
                                             @RequestParam(value = "teams", required = false) MultipartFile teams) throws IOException {
        return summary(sessions.createSession(invoices, payments, photos, teams));
    }

    @PostMapping(value = "/sessions/{id}/disputes", consumes = "multipart/form-data")
    public Map<String, Object> addDisputes(@PathVariable String id,
                                           @RequestParam(value = "disputes", required = false) MultipartFile disputes) throws IOException {
        return summary(sessions.addDisputes(id, disputes));
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable String id) {
        return summary(sessions.get(id));
    }

    private Map<String, Object> summary(UploadSession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.id);
        out.put("createdAt", s.createdAt.toString());
        out.put("files", s.fileRows);
        out.put("warnings", s.warnings);
        out.put("paymentsScored", s.payments.size());
        out.put("disputesScored", s.disputes.size());
        Map<String, Long> tiers = new LinkedHashMap<>();
        for (String t : List.of("high", "medium", "low")) tiers.put(t, s.payments.stream().filter(p -> t.equals(p.disputeRiskTier)).count());
        out.put("tiers", tiers);
        out.put("expectedLossUsd", Math.round(s.payments.stream().mapToDouble(p -> p.expectedLossUsd == null ? 0 : p.expectedLossUsd).sum() * 100) / 100.0);
        out.put("fight", s.disputes.stream().filter(d -> "fight".equals(d.recommendation)).count());
        out.put("accept", s.disputes.stream().filter(d -> "accept".equals(d.recommendation)).count());
        return out;
    }

    // ---------------------------------------------------------------- per payment / dispute

    @GetMapping("/sessions/{id}/payments")
    public Map<String, Object> payments(@PathVariable String id,
                                        @RequestParam(required = false) String tier,
                                        @RequestParam(required = false) String team,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Boolean disputed,
                                        @RequestParam(defaultValue = "expected_loss") String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        UploadSession s = sessions.get(id);
        Predicate<PaymentPrediction> f = p -> true;
        if (tier != null && !tier.isBlank()) f = f.and(p -> tier.equals(p.disputeRiskTier));
        if (team != null && !team.isBlank()) f = f.and(p -> team.equals(p.teamId));
        if (disputed != null) f = f.and(p -> p.disputed == disputed);
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase(Locale.ROOT);
            f = f.and(p -> contains(p.paymentId, needle) || contains(p.invoiceId, needle) || contains(p.customerId, needle));
        }
        Comparator<PaymentPrediction> cmp = switch (sort) {
            case "risk" -> Comparator.comparingDouble(p -> -nz(p.disputeRiskScore));
            case "amount" -> Comparator.comparingDouble(p -> -p.amountUsd);
            case "win" -> Comparator.comparingDouble(p -> nz(p.paymentWinProb));
            default -> Comparator.comparingDouble(p -> -nz(p.expectedLossUsd));
        };
        return page(s.payments.stream().filter(f).sorted(cmp), page, size);
    }

    @GetMapping("/sessions/{id}/payments/{paymentId}")
    public Map<String, Object> payment(@PathVariable String id, @PathVariable String paymentId) {
        UploadSession s = sessions.get(id);
        PaymentPrediction p = s.paymentById.get(paymentId);
        if (p == null) throw new NoSuchElementException("Payment " + paymentId + " not in this upload");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payment", p);
        out.put("disputes", s.disputes.stream().filter(d -> paymentId.equals(d.paymentId)).toList());
        return out;
    }

    @GetMapping("/sessions/{id}/disputes")
    public Map<String, Object> disputes(@PathVariable String id,
                                        @RequestParam(required = false) String reason,
                                        @RequestParam(required = false) String recommendation,
                                        @RequestParam(required = false) String team,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "amount") String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        UploadSession s = sessions.get(id);
        Predicate<DisputePrediction> f = d -> true;
        if (reason != null && !reason.isBlank()) f = f.and(d -> reason.equals(d.reason));
        if (recommendation != null && !recommendation.isBlank()) f = f.and(d -> recommendation.equals(d.recommendation));
        if (team != null && !team.isBlank()) f = f.and(d -> team.equals(d.teamId));
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase(Locale.ROOT);
            f = f.and(d -> contains(d.disputeId, needle) || contains(d.paymentId, needle));
        }
        Comparator<DisputePrediction> cmp = switch (sort) {
            case "win" -> Comparator.comparingDouble(d -> -nz(d.winProb));
            case "value" -> Comparator.comparingDouble(d -> -(d.expectedValueFight - d.expectedValueAccept));
            default -> Comparator.comparingDouble(d -> -d.amountUsd);
        };
        return page(s.disputes.stream().filter(f).sorted(cmp), page, size);
    }

    // ---------------------------------------------------------------- team & portfolio

    @GetMapping("/teams")
    public List<Map<String, Object>> teams(@RequestParam(required = false) String session) {
        return views.teams(sessions.find(session));
    }

    @GetMapping("/teams/{teamId}")
    public Map<String, Object> team(@PathVariable String teamId, @RequestParam(required = false) String session) {
        return views.team(teamId, sessions.find(session));
    }

    @GetMapping("/portfolio")
    public Map<String, Object> portfolio(@RequestParam(required = false) String session) {
        return views.portfolio(sessions.find(session));
    }

    // ---------------------------------------------------------------- helpers

    private static <T> Map<String, Object> page(Stream<T> stream, int page, int size) {
        List<T> all = stream.toList();
        int sz = Math.max(1, Math.min(size, 500));
        int from = Math.min(Math.max(page, 0) * sz, all.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", sz);
        out.put("items", all.subList(from, Math.min(from + sz, all.size())));
        return out;
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
