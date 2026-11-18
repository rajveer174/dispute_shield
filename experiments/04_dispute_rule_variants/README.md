# 04 · Dispute-received rule variants

**Question:** can more or different rules explain the dispute win chance better than the original three
(responded · 4+ pieces of evidence · reason is not fraud), and does a "late dispute" rule help?

Full tables in `RESULTS.md`. All AUCs are on the last 4 months, with the rules learned on the earlier months.

| Variant | Test AUC | Decision and why |
|---|---|---|
| A · original three rules | 0.713 | Replaced by D. |
| B · + opened within 30 days of payment | 0.731 | **Rejected** (removed on review). Early disputes do win a little more (51% vs 39–48% when responded), but the pattern is not steady by month band, and it is not something a team can act on. |
| **D · + no photos added after the dispute opened** | **0.725** | **Kept** as the explanation shown with each dispute. Late photos lower the win chance once the team has responded. |
| E · + 30 days + no late photos (5 rules) | 0.740 | Rejected with B. The best AUC, but 32 combinations for 1,417 disputes, and the 30-day rule carried most of the gain. |
| F · + opened 90+ days after payment | 0.713 | **Rejected: no evidence.** Only 3 of 1,417 disputes were opened after 88 days and none after 90, so the data cannot say whether late disputes are easier to win. |
| G–J · "not fraud" swapped for card verified / signed estimate / reason-matched evidence | 0.697–0.721 | Rejected: lower AUC than D. |

## Why the variants changed the fight/accept decision too

With the earlier "accept below a 35% win chance" decision, A and D accepted **only fraud** disputes (359 of 557),
because fraud with fewer than 4 pieces of evidence is the only combination below 35%. Swapping out "not fraud"
(G–J) made **nothing** fall below 35%. Any rule table has only a handful of possible values, so a fixed cut-off
always accepts or fights whole groups, and whichever rule sits near the line decides. This led to `05`: decide
with the full model's per-dispute chance and a dollar threshold, and keep the rules as the explanation.

**Caveat:** variant B was suggested after seeing its effect; choosing among 10 variants on 275 test disputes
overstates the winner slightly. Differences under ~0.02 AUC are within noise.

Rerun: `node experiments/04_dispute_rule_variants/run.mjs data`. Earlier one-off scripts:
`tools/rules/more-dispute-rules.mjs`, `tools/rules/alternatives.mjs`, `tools/rules/lag.mjs`.
