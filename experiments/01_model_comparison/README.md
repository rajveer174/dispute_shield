# 01 · Which model for each prediction

**Question:** for each of the four predictions, which approach should the app use?

**Tried** (all on the same features, the same time split, fixed seeds — see `RESULTS.md`):

| Approach | Idea | Explainable? |
|---|---|---|
| Baseline: rate by one field | Historical rate for the job's payment method / profession / dispute reason | Yes, trivially |
| Bernoulli naive Bayes | Each factor counted on its own, assumed independent | Per factor, but the independence assumption double-counts related factors |
| **L2 logistic regression** | Weighted score of all factors | Yes: each factor adds or removes a fixed amount of log-odds |

## Result (held-out months)

| Prediction | Baseline AUC | Naive Bayes AUC | Logistic AUC |
|---|---|---|---|
| Chance of a dispute | 0.727 | 0.794 | **0.809** |
| Invoice win | 0.573 | 0.618 | **0.620** |
| Payment win | 0.554 | 0.622 | **0.646** |
| Dispute win | 0.553 | 0.737 | **0.752** |

## Decision

- **Kept: logistic regression for all four.** Best ranking everywhere and the lowest Brier / log-loss, so its
  probabilities are usable directly in money calculations (dollars at risk, expected gain of fighting).
  Per-factor contributions give the "why" shown in the app.
- **Rejected: naive Bayes.** Ranks almost as well, but its probabilities are badly calibrated (dispute win
  ECE 0.24 vs 0.09; log-loss 1.15 vs 0.60): it is over-confident because related evidence items are counted as
  independent. The app multiplies probabilities by dollars, so calibration matters more than a small AUC gap.
- **Rejected: single-field baselines.** Kept only as the bar to beat. The dispute-reason baseline (0.553) shows
  the reason alone says little about winning; evidence and response matter far more.

**Caveats:** win models have only 276 held-out disputes, so AUC differences under ~0.02 are within noise. The
invoice-win model (0.620) is weak: before a payment or a dispute exists there is little signal. The app therefore
uses the simpler three-rule tables for the invoice and payment moments (`03_rule_search`).

Rerun: `node experiments/01_model_comparison/run.mjs` (after the experiments training, see `../README.md`).
