# Dispute Shield

Predicts dispute risk and win chances for TestPay field-service payments at three moments
(invoice ready → payment collected → dispute received), recommends fight/accept, and estimates the
effect of product policies over months 1-22.

- **Backend**: Spring Boot 3 (Java 17). CSV ingest, as-of feature building, pure-Java models, what-if engine, REST API, CSV export.
- **Frontend**: React 18 + Vite 4. Built into the Spring Boot jar, so one process serves everything.
- **Models** are trained once on months 1-22 by `train` mode and shipped as `models/model_bundle.json.gz`.
  The web app only loads that bundle; uploaded files are scored, never used to retrain.

## Run steps

Prerequisites: Java 17, Maven 3.9, Node 16+.

```bash
# 1. Put the brief's files in ./data (teams.csv, invoices.csv, photos.csv, payments.csv, disputes.csv)
#    No data? Generate synthetic files in the same schema:
node tools/generate-sample-data.mjs sample-data 6      # -> sample-data/train, sample-data/upload

# 2. Build the frontend into the backend, then the jar
cd frontend && npm install && npm run build && cd ..
mvn -f backend/pom.xml -DskipTests package

# 3. Train (writes models/model_bundle.json.gz and models/training_report.json; prints the validation table)
java -jar backend/target/dispute-shield.jar train --data=data --out=models/model_bundle.json.gz

# 4. Run the app  ->  http://localhost:8080   (PORT=8091 to change the port)
java -jar backend/target/dispute-shield.jar
```

Frontend dev with hot reload: run the jar, then `cd frontend && npm run dev` (proxies `/api` to :8080).

`models/` is not in git (the bundle holds record-level history from the data), so run step 3 before building the image.

Deploy: hosted on Fly.io at https://dispute-shield.fly.dev, configured by [fly.toml](fly.toml)
(one always-on machine in Singapore, 2 GB). To redeploy, run `fly deploy` from this folder: Fly uploads
the local folder and builds the Dockerfile remotely, so `models/` reaches the image without going into git.

Locally: `docker build -t dispute-shield . && docker run -p 8080:8080 dispute-shield`.

## Using the app

| Page | What it answers |
|---|---|
| Upload | Step 1: invoices, photos, payments → every payment scored. Step 2: disputes (no outcomes) → every dispute scored. |
| Payments | Per payment: invoice win chance, payment win chance, risk tier, dollars at risk, why, and what to do now / next time. |
| Disputes | Per dispute: win chance with evidence submitted, fight or accept with the expected value, evidence to add. |
| Teams | Pick a team ID: track record, uploaded payments and disputes, main evidence gaps ranked by dollars. |
| All teams | Disputes, win rate and dollars lost by dispute reason (history + uploaded predictions). |
| What-if | Policy effects over months 1-22 with a range, confidence and assumptions. |
| Models | Per moment: what it predicts, reasons, how far to trust it, and the three-rule version with its probability table. |
| Export | `payment_predictions.csv`, `dispute_predictions.csv`, `whatif_results.csv` (the brief's formats), plus `invoice_win_rules.csv`, `payment_win_rules.csv`, `dispute_win_rules.csv` with the rule-based "Dispute win probability". |

## Rule-based win probability

Alongside the models, each moment has yes/no rules: three for invoice ready and payment collected, four for
a received dispute. Each yes/no combination (8 or 16) gets the win rate of past
disputes with that combination (rare ones pulled toward the overall rate; meeting one more rule never lowers it).
The tables are learned by `train` and shown on the Payments, Disputes and Models pages. Invoice and payment win
chances use them directly; for a received dispute the decision uses the full model and the rules explain it.

| Moment | Rule 1 | Rule 2 | Rule 3 | Rule 4 |
|---|---|---|---|---|
| Invoice ready | Signed estimate | Customer address on invoice | GPS check-in or a job photo | |
| Payment collected | 3+ of 6 invoice items | Signed estimate | Card tapped/chip, or AVS and CVV matched | |
| Dispute received | Team responded | 4+ pieces of evidence | Reason is not fraud | No photos added after the dispute opened |

The rules are defined in `backend/src/main/java/com/testpay/shield/rules/WinRules.java`. `tools/rules/` holds the
scripts used to choose them (`search.mjs`, `compare.mjs`; `more-dispute-rules.mjs` tested extra rules, including a 30-day and a 90-day rule,
which the data cannot support: no dispute opened later than 90 days after payment) and to score the full training files offline (`score.mjs`).

## Design notes

**What is predicted when** (`FeatureExtractor`):
- *Invoice ready*: invoice + team fields only → `invoice_win_prob`. The dispute reason is unknown, so it
  averages over the historical mix of reasons. Dispute *risk* is not scored here: most risk signal
  (payment method, AVS/CVV, card history) does not exist yet.
- *Payment collected*: + payment and cross-team card/customer history (strictly earlier events) →
  `payment_win_prob`, `dispute_risk_score/tier`, `expected_loss_usd = P(dispute) × (1 − P(win)) × (amount + $25)`.
- *Dispute received*: + reason, response, evidence submitted → `win_prob` from the full dispute-win model
  (each dispute judged on all its facts; the four dispute rules are shown as the explanation, and are the fallback
  if the model is not published). Fight when the expected gain
  `P(win | respond) × (amount + fee) − $15 ≥ min-expected-gain-usd` (default $25), otherwise accept.
- A model whose validation AUC is below `shield.export.min-auc-to-publish` is not published and its
  column is exported blank.

**Release notes and leakage** (`ReleaseCalendar`; run `node tools/analyze-release-notes.mjs data` to check each item on the data):
- *Disputes recorded when the card network notifies TestPay*: disputes arrive weeks after payment. Payments
  inside the 95th-percentile payment-to-dispute lag at the end of the data are left out of the risk model
  (`censor-days: 0` = derive the window automatically; the chosen value is in the training report).
- *Photos can be added at any time*: `invoices.csv` is an end-of-period snapshot, while uploads show invoices
  as at payment time. Photo counts are rebuilt from `photos.csv` using only photos uploaded by `collected_at`.
- *Completion signature from Dec 2025, adopted team by team*: the feature `inv:signature_unavailable` marks
  jobs from before the launch or before the team's first signed job (no look-ahead). Those jobs are not
  counted as missing a signature in team gaps. Teams that have not adopted it get "start using signatures"
  as a next-time action instead of "collect a signature".
- *Invoice status from June 2026, backfilled*: `invoice_status` is never a model input (`disputed` is the
  label). The backfill rewrote `updated_at`, so `leakage-mode` defaults to `NONE`; the report still counts
  invoices edited after their dispute opened.

**Validation**: time split, the last 4 months held out. Three approaches per prediction: a segment-rate
baseline, Bernoulli naive Bayes, and L2 logistic regression (chosen: calibrated, explainable per feature).

**What-if** (`WhatIfEngine`): anchored on what actually happened. Jobs lost to the policy remove their
disputes. On affected jobs that stay, disputes scale by the risk model's ratio, and losses shift by the
change in payment-win probability. The range comes from a team-level bootstrap, a causal discount
k ∈ [0.5, 1] (missing evidence partly marks careless teams) and the uncertainty on the job-loss fraction.

## Experiments

[experiments/](experiments/) records every approach tried, including the rejected ones (model choice, leakage
checks, rule search, dispute-rule variants, fight/accept back-test, risk tiers, decision tree). Each has a
README with the decision, a `run.mjs` and a totals-only `RESULTS.md`. Reproduce with
`train --experiments=outputs/experiments` (see `experiments/README.md`).

## Extending

- **New dispute reason**: add it to `backend/src/main/resources/rules/dispute-reasons.json` (label, guidance)
  and retrain. Reasons are one-hot features learned from the data; unknown reasons are still scored.
- **New evidence rule**: add an entry to `rules/evidence-rules.json` pointing at a feature name.
- **New policy**: add a `@Component` implementing `WhatIfPolicy` (see `whatif/policies/`), then retrain.
  It shows up on the What-if page and in `whatif_results.csv` automatically.
- Business assumptions (fee, fight cost, withdrawn = won, tiers, censoring, policy thresholds) are in `application.yml`.

## Layout

```
backend/src/main/java/com/testpay/shield/
  ingest/     CSV schema + parsing          features/  as-of contexts, history index, feature extraction
  model/      logistic, naive Bayes, baseline, metrics, bundle      training/  trainer, history stats
  whatif/     policy interface, engine, policies/                    scoring/   model store, scoring, labels
  session/    upload sessions (in memory)   rules/     evidence + reason registries   web/  REST + export
frontend/src/ pages/ (one per view), api.js, components.jsx
tools/generate-sample-data.mjs   synthetic data in the brief's schema (for testing only)
```
