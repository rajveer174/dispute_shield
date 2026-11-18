# 05 · Fight or accept: decision policy back-test

**Question:** which fight/accept rule would have done best on disputes it had not seen?

**Method:** the dispute-win model and the rule table are learned on the earlier months only. On the last 4 months,
each policy decides for every dispute the team responded to (219), and we add up what fighting actually returned:
won → amount + $25 fee back, minus the $15 fight cost; lost → −$15. "Accept everything" is $0. Disputes the team
did not respond to are left out, since we cannot know what fighting them would have returned. Full tables in
`RESULTS.md`.

## Result (held-out months)

| Policy | Fight / accept | Realised value | Given up vs best |
|---|---|---|---|
| Accept everything | 0 / 219 | $0 | $123,180 |
| Fight everything | 219 / 0 | $123,150 | $30 |
| Rules p, fight if expected recovery > $15 | 219 / 0 | $123,150 | $30 |
| Rules p + 35% minimum win chance | 170 / 49 | $113,251 | **$9,929** |
| Model p, fight if expected recovery > $15 | 217 / 2 | **$123,180** | – |
| Model p + $25 minimum expected gain (current app) | 203 / 16 | $121,923 | $1,257 |

## Decisions

- **Rejected: rules p + 35% minimum.** It accepted only fraud disputes (49 of 82 fraud, 0 of every other reason) and
  gave up about $9.9k, 8% of the value. A fixed win-chance cut on a coarse rule table splits by category, not by
  the money at stake.
- **Kept: the model's per-dispute win chance** for the decision. It accepts across reasons (11 fraud, 3 service
  not provided, 1 not as described, 1 credit not processed) and lands within $1.3k of the best policy.
- **The $25 minimum is a workload lever, not a money-maker.** At the assumed $15 cost, it avoids 16 fights
  (7%) and gives up $1,257, about $79 per avoided fight. It pays off only if a fight really costs the team
  more than about $90 of time. At a real cost of $150, deciding at break-even with the model beats fighting
  everything by $3.9k.
- **Recommendation:** set `fight-cost-usd` to the team's real cost per dispute and keep `min-expected-gain-usd`
  low (0–25). Raise the minimum only to cap the number of fights the team takes on.

**Caveats:** 219 disputes and one 4-month window, so differences of a few thousand dollars are noisy. Fighting
everything comes out almost as well as the best policy because half of responded disputes were won and amounts
are large next to a $15 cost; the policies only separate once a fight costs much more. The $15 cost and $25 fee
are assumptions.

Rerun: `node experiments/05_decision_policy/run.mjs` (after the experiments training, see `../README.md`).
