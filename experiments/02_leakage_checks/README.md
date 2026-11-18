# 02 · Leakage and release-note checks

**Question:** which fields would let a model "see the future" (information that only exists after the moment it
predicts for), and how should the trainer handle each release note?

**Tried:** each release-note item was checked on the data with `tools/analyze-release-notes.mjs` before deciding
how to treat it. Full tables in `RESULTS.md`.

| Item | What the data showed | Decision |
|---|---|---|
| Disputes recorded when the card network notifies TestPay | Median 29 days from payment to dispute, p95 80 days, none after 90. The last months look dispute-free only because their disputes have not arrived. | **Kept:** risk model drops payments from the last 80 days (p95 lag, derived automatically). **Rejected:** training on all payments, which would teach the model that recent payments are safe. |
| Photos can be added at any time | 10% of photos were uploaded after payment, 574 after the dispute opened. Snapshot counts overstate the "after photo" win gap (11.6 pts vs 10.4 pts as of payment). | **Kept:** photo counts rebuilt from `photos.csv` as of payment time. **Rejected:** the `invoices.csv` snapshot counts. |
| Completion signature from Dec 2025, team by team | 0 signed jobs before launch; 102 of 200 teams adopted; not available for 80% of jobs. Adopted-but-unsigned jobs win about as often as signed ones (48% vs 46%, small n). | **Kept:** `signature_unavailable` flag; "no signature" only counts as a gap once the team has adopted it. Effect treated as an upper bound, since adopting teams self-select. |
| Invoice status from June 2026, backfilled | `disputed` matches the dispute records exactly: it is the label, set after the fact. | **Rejected as an input** everywhere. |
| `updated_at` | Every resolved dispute's invoice was updated after the dispute opened (the backfill touched them). | **Rejected:** filtering "edited after the dispute" rows by `updated_at` (`leakage-mode: NONE`); it would drop everything. Late evidence is caught through photo timestamps instead. |

**Also excluded from inputs:** `outcome` and `closed_at` (the labels), and anything recorded after the moment
being scored. Card and customer history uses only strictly earlier events.

Rerun: `node experiments/02_leakage_checks/run.mjs data`.
