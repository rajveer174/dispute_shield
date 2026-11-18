# Experiments

Everything tried while building Dispute Shield, including the approaches that were rejected. Each folder has a
`README.md` (question, what was tried, decision and why) and a `run.mjs` that regenerates its `RESULTS.md`.

**Data rule:** scripts read the data or held-out predictions locally and write **totals only**. No raw rows, IDs or
data files are committed; `data/` and `outputs/` are git-ignored.

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
