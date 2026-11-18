# Experiments

Everything tried while building Dispute Shield, including the approaches that were rejected. Each folder has a
`README.md` (question, what was tried, decision and why) and a `run.mjs` that regenerates its `RESULTS.md`.

**Data rule:** scripts read the data or held-out predictions locally and write **totals only**. No raw rows, IDs or
data files are committed; `data/` and `outputs/` are git-ignored.

## Index

| # | Question | Result | Kept | Rejected |
|---|---|---|---|---|
| [01](01_model_comparison/) | Which model per prediction? | Logistic best on all four (AUC 0.809 risk, 0.620 / 0.646 / 0.752 win) | L2 logistic regression | Naive Bayes (poorly calibrated), single-field baselines |
| [02](02_leakage_checks/) | Which fields leak the future? | Disputes lag up to 90 days; 10% of photos added after payment; `invoice_status` is the label | 80-day censoring, as-of-payment photo counts, signature adoption flag | Snapshot photo counts, `invoice_status` as input, `updated_at` filter |
| [03](03_rule_search/) | Can 3 yes/no rules per moment do the job? | Rules 0.628 / 0.628 / 0.713 vs model 0.620 / 0.646 / 0.752 | Rules for invoice and payment; monotone tables, k = 20 | Top-ranked sets with nested rules; first loader that dropped records |
| [04](04_dispute_rule_variants/) | More or different dispute rules? | 4 rules (+ no late photos) 0.725; a 90-day rule has no data to support it | 4 rules, shown as the explanation | 30-day rule, 5 rules, 90-day rule, reason-neutral swaps |
| [05](05_decision_policy/) | Which fight/accept policy? | Rules + 35% minimum gave up $9.9k; model p within $1.3k of the best | Model p + expected-gain threshold | Rule-table win-chance minimum |
| [06](06_risk_tiers/) | How to set risk tiers? | High tier: 9% of payments, 58% of disputes, 6.6× average | 70th / 90th percentile cut-offs | Absolute 25% / 50% scale; note that "medium" is below average |
| [07](07_decision_tree/) | Would a tree model do better? | Worse everywhere (e.g. payment win 0.504 vs 0.646) | – | Depth-5 decision tree |
| [08](08_rule_checker/) | Can the rules tell a merchant "is this strong enough, what to add now"? | Held-out win rate Strong 70–75%, Fair 43–47%, Weak 31–36% at every moment | Rule checker on the Models page | Model drivers, test tables and combination tables on that page |

## Reproduce

```bash
# 1. Build the jar (see the main README), then train in experiments mode. Same seeds and settings as the app,
#    plus a decision-tree candidate and held-out predictions written to outputs/experiments/ (git-ignored).
java -Xmx3g -jar backend/target/dispute-shield.jar train --data=data \
     --out=outputs/experiments/model_bundle.json.gz --experiments=outputs/experiments

# 2. Regenerate any experiment's RESULTS.md
node experiments/01_model_comparison/run.mjs
```

Experiments that only need the data files take it as an argument: `node experiments/<folder>/run.mjs data`.

## Validation used everywhere

Time split: train on the earlier months, score the **last 4 months**, which the model never saw. This mirrors how
the app is used (trained on the past, applied to new uploads). AUC measures ranking; Brier, log-loss and ECE
measure whether the probabilities can be taken at face value.
