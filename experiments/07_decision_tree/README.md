# 07 · Decision tree (rejected)

**Question:** would a tree-based model, which can pick up interactions such as "keyed in *and* address mismatch",
beat the logistic model?

**Tried:** a depth-5 binary decision tree with log-loss splits, a minimum leaf of max(30, rows / 200) and
smoothed leaf rates (`backend/src/main/java/com/testpay/shield/model/DecisionTreeModel.java`). It uses the same
features and training months as the other approaches and runs only in experiments mode, never in the app.
Results in `RESULTS.md`.

## Result (held-out months)

| Prediction | Logistic AUC | Tree AUC | Gap, 95% bootstrap interval |
|---|---|---|---|
| Chance of a dispute | 0.808 | 0.780 | 0.010 to 0.047 |
| Invoice win | 0.620 | 0.526 | 0.028 to 0.168 |
| Payment win | 0.646 | 0.504 | 0.057 to 0.218 |
| Dispute win | 0.752 | 0.706 | −0.012 to 0.109 |

## Decision: rejected

- **Worse ranking everywhere**, and clearly worse (the interval excludes 0) for risk, invoice win and payment
  win. With about 1,100 training disputes, the tree overfits: payment win falls to chance level (0.504).
- **Coarse probabilities:** only 15–17 distinct values, so many payments share one number. That is a poor input
  for dollars at risk.
- **Harder to explain:** a path of 5 splits per prediction, against one additive contribution per factor for the
  logistic model, which is what the app shows as "why".
- Calibration is similar for dispute risk (ECE 0.0011 vs 0.0012); otherwise worse.

**Not tried:** gradient-boosted trees. They usually beat a single tree, but with ~1,100 labelled disputes the gain
over a regularised linear model is likely small, and they give up the per-factor explanation. A candidate if
the data grows.

Rerun: `node experiments/07_decision_tree/run.mjs` (after the experiments training, see `../README.md`).
