# 06 · Dispute-risk tiers

**Question:** how should the chance of a dispute be turned into low / medium / high, and do the tiers mean
anything on payments the model has not seen?

**Method:** the risk model trained on the earlier months scores the held-out payments (38,995, 0.75% disputed).
Tier cut-offs are percentiles of that model's scores on its own training payments, as in the app
(`medium-tier-quantile: 0.70`, `high-tier-quantile: 0.90`). Full tables in `RESULTS.md`.

## Result (held-out months)

| Tier | Share of payments | Dispute rate | vs average | Share of all disputes |
|---|---|---|---|---|
| High (≥ 1.11%) | 9% | 4.94% | 6.6× | 58% |
| Medium (0.51%–1.11%) | 17% | 0.60% | 0.8× | 14% |
| Low (< 0.51%) | 74% | 0.28% | 0.4× | 28% |

## Decisions

- **Kept: percentile cut-offs (70th / 90th).** The high tier holds 9% of payments but 58% of disputes, so it
  is where review effort pays off. The cut-offs stay sensible when the overall dispute rate shifts.
- **Rejected: an "intuitive" absolute scale (25% / 50%).** With a 0.75% base rate, it puts 100% of payments in
  "low", including 76% of the disputes.
- **Alternatives that work:** fixed 1% / 2% gives a sharper high tier (9.3% dispute rate, 4% of payments, 50% of
  disputes). 80th / 95th percentile is similar. Either is a reasonable choice if the team wants a smaller review
  list.
- **Finding: "medium" is not really elevated.** Its dispute rate (0.60%) is below the average (0.75%), so risk is
  concentrated in the top 10%. The medium tier is best read as "watch", not "at risk". A two-tier split, or
  medium starting at the 80th percentile, would be more honest. It is left as is in the app for now.

Rerun: `node experiments/06_risk_tiers/run.mjs` (after the experiments training, see `../README.md`).
