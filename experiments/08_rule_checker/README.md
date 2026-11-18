# 08 · Rule checker on the Models page

**Question:** merchants need an answer to "Is this invoice / payment / dispute strong enough if it is ever disputed,
and what should be added now?". Can the rules answer it directly, and does the checker's verdict hold up on
disputes it has not seen?

## What changed in the app

Each moment on the Models page now has a **rule checker**. You tick what is in place, or load an uploaded record by
ID, and press **Check**. It gives a verdict, the win chance, and the missing items ranked by how much each one
raises the chance. The verdict bands come from the rule table itself:

- **Strong:** the best combination (all rules met).
- **Fair:** above the overall win rate by more than 2 points.
- **Weak:** at or below the overall win rate.

Rules a team cannot change (the dispute reason) are listed separately, and the summary then shows the best chance
still reachable.

| Moment | Rules | Question answered |
|---|---|---|
| Invoice ready | signed estimate · customer address · GPS check-in or a job photo | Is this invoice strong enough if it is ever disputed? What should be added now? |
| Payment collected | 3+ of 6 invoice items · signed estimate · card verified | Is this payment protected well enough if it is ever disputed? What should be done now? |
| Dispute received | responded · 4+ pieces of evidence · not fraud · no late photos | How strong is this dispute with what we have? What should be added now? |

**Removed from the page (rejected presentation):** the model's "Helps / Hurts the team's chance" lists, the
"How it was tested" tables and the rule "Win probability for each combination" tables. They answered "how does the
model work?" rather than "what should I do with this invoice?". Factors such as "job type: roof replacement" are
not something a merchant can act on, and an 8- or 16-row table is hard to use for one record. The model comparison
lives in `01`, and the full rule tables in `03`, `04` and the `train` log. The chance of a dispute and the
dispute-win model keep their trust notes, because the app uses them directly.

## Result: verdicts on held-out disputes

Rule tables learned on the earlier months, verdicts checked on the last 4 months (`RESULTS.md`):

| Checker verdict | Invoice ready | Payment collected | Dispute received |
|---|---|---|---|
| Strong | **70%** won (33) | **75%** (24) | **75%** (56) |
| Fair | 47% (79) | 44% (99) | 43% (47) |
| Weak | 34% (163) | 36% (152) | 31% (172) |

- **Kept:** the verdict bands separate cleanly at every moment, and each extra rule met raised the actual win rate
  (invoice: 24% → 35% → 49% → 70% for 0–3 rules). So "add this now" advice points the right way.
- **Caveat: the percentages are too low for recent months.** The held-out win rate was 42%, against 36% in the
  training months, so the checker under-states Strong (51–63% predicted vs 70–75% actual). Read the verdict and the
  ranking of additions, not the exact percentage. The groups are also small (24–56 disputes in Strong).
- **Caveat:** the rules show association, not proof that adding an item causes the win. Teams that collect
  signed estimates may be more careful in general (the same caution as the What-if page).

Rerun: `node experiments/08_rule_checker/run.mjs data`.
