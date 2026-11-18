# 03 · Three yes/no rules per moment

**Question:** can a team-friendly check of three yes/no rules per moment predict the win chance about as well as
the full model? (The assignment asked for 3 rules per moment and a "Dispute win probability" for every record.)

**Method:** each candidate rule is a plain check on what is known at that moment. A set of three gives 8 yes/no
combinations; each gets the win rate of past disputes with that combination, pulled toward the overall rate when
rare (k = 20 pseudo-disputes) and constrained so that meeting one more rule never lowers the chance. Rules
learn on the earlier months and are scored on the last 4. Full tables in `RESULTS.md`.

## Result (test AUC)

| Moment | Chosen rules | Rules | Full logistic model |
|---|---|---|---|
| Invoice ready | signed estimate · customer address · GPS check-in or a job photo | **0.628** | 0.620 |
| Payment collected | 3+ of 6 invoice items · signed estimate · card verified (tap/chip, or AVS + CVV match) | **0.628** | 0.646 |
| Dispute received | responded · 4+ pieces of evidence · reason is not fraud | **0.713** | 0.752 |

## Decisions

- **Kept: rules for the invoice and payment moments.** They match or nearly match the full model (there is little
  signal before a dispute exists) and a team can act on them directly ("get the estimate signed").
- **Kept: the full model for disputes, with the rules as the explanation.** The gap is larger (0.713 vs 0.752)
  and the decision has money attached; see `05_decision_policy`.
- **Rejected: the top sets by training AUC** for invoice and payment. They pair nested rules (`paperwork ≥ 3`
  together with `paperwork ≥ 4`), which is really two thresholds on one score rather than three checks, and they
  scored *lower* on the test months (0.59–0.61). The chosen sets were picked for three distinct, actionable checks
  among the strong training sets (rank 78 of 455 and 11 of 1,330). For disputes the chosen set is rank 1.
- **Kept: the monotone step** (a combination is at least as good as any combination it contains). It never
  hurt and raised invoice test AUC from 0.614 to 0.628. It also stops tables like "signed + address: 28%" being
  lower than "signed only: 30%", which a team would find nonsensical.
- **Shrinkage k barely matters** (0.626–0.630 for k 0–100); k = 20 kept as a safe middle for rare combinations.
- **Rejected (bug found during the search):** the first version kept only the first payment per invoice and the
  first dispute per payment, silently dropping records. Rebuilt with one job per invoice, payment and dispute
  (`tools/rules/lib.mjs`).

**Caveat:** the hand-picked sets in `tools/rules/compare.mjs` were also looked at on the test months during
development, so the chosen-set test AUCs are slightly optimistic. The ranking here uses training AUC only.

Rerun: `node experiments/03_rule_search/run.mjs data` (about 20 s). Search tools: `tools/rules/search.mjs`,
`tools/rules/compare.mjs`.
