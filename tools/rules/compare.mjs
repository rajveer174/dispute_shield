// Compares hand-picked 3-rule sets (train months <= CUT, tested on the last 4 months). Aggregates only.
import { load, auc } from './lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from './rules.mjs';

const { disputeJobs: jobs } = load(process.argv[2] || 'data');
const resolved = jobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };

const SETS = {
  'invoice ready': [
    ['signed_estimate', 'address', 'before AND after photo'],
    ['signed_estimate', 'address', 'presence: gps OR any photo'],
    ['signed_estimate', 'address', 'gps_checkin'],
    ['agreement: estimate OR terms', 'address', 'presence: gps OR any photo'],
    ['signed_estimate', 'paperwork score >= 3 of 6', 'before AND after photo'],
    ['paperwork score >= 3 of 6', 'signed_estimate', 'address'],
  ],
  'payment collected': [
    ['paperwork score >= 3 of 6', 'card present (tap/chip)', 'receipt sent'],
    ['paperwork score >= 3 of 6', 'signed_estimate', 'card present (tap/chip)'],
    ['paperwork score >= 3 of 6', 'signed_estimate', 'card checks: present OR avs+cvv match'],
    ['signed_estimate', 'address', 'card present (tap/chip)'],
    ['paperwork score >= 4 of 6', 'address', 'card checks: present OR avs+cvv match'],
  ],
  'dispute received': [
    ['responded', 'evidence >= 4 items', 'not fraud'],
    ['responded', 'evidence >= 4 items', 'reason-matched evidence x2'],
    ['responded', 'reason-matched evidence x2', 'not fraud'],
    ['responded with evidence', 'evidence >= 4 items', 'signed estimate submitted'],
  ],
};

for (const [stage, sets] of Object.entries(SETS)) {
  console.log(`\n== ${stage}`);
  for (const ids of sets) {
    const rules = ids.map((id) => CANDIDATES[stage].find((c) => c.id === id));
    const m = lookupModel(rules, train, ctx);
    const tr = auc(train.map(m.predict), train.map((j) => j.label));
    const te = auc(test.map(m.predict), test.map((j) => j.label));
    // Range of probabilities the set can give, and how much of the test months land in its best / worst cell.
    const probs = [...m.cells.keys()].map(m.prob);
    console.log(`  train ${tr.toFixed(3)}  test ${te.toFixed(3)}  range ${(Math.min(...probs) * 100).toFixed(0)}-${(Math.max(...probs) * 100).toFixed(0)}%   ${ids.join('  +  ')}`);
  }
}
