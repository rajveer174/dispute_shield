// Does the Models-page rule checker's verdict mean anything on disputes it has not seen?
// For each moment: learn the rule table on the earlier months, give every held-out dispute the checker's verdict
// (Strong = best combination, Fair = above the overall rate + 2 pts, Weak = otherwise), and compare the actual win
// rates. Also the win rate by number of rules met. Usage: node experiments/08_rule_checker/run.mjs data
import path from 'node:path';
import { load } from '../../tools/rules/lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from '../../tools/rules/rules.mjs';
import { ROOT, pct, table, writeResults } from '../lib.mjs';

const { disputeJobs } = load(process.argv[2] || path.join(ROOT, 'data'));
const resolved = disputeJobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };

// The rules the app's checkers use (backend/.../rules/WinRules.java).
const SETS = {
  'invoice ready': ['signed_estimate', 'address', 'presence: gps OR any photo'],
  'payment collected': ['paperwork score >= 3 of 6', 'signed_estimate', 'card checks: present OR avs+cvv match'],
  'dispute received': ['responded', 'evidence >= 4 items', 'not fraud', 'no late photos'],
};

const rate = (a) => (a.length ? a.filter((j) => j.label === 1).length / a.length : NaN);
let body = `Rule tables learned on ${train.length} disputes (months ≤ ${maxM - 4}); verdicts checked on ${test.length} `
  + `held-out disputes (months ${maxM - 3}–${maxM}), overall held-out win rate ${pct(rate(test))}.\n`;

for (const [stage, ids] of Object.entries(SETS)) {
  const rules = ids.map((id) => CANDIDATES[stage].find((c) => c.id === id));
  const m = lookupModel(rules, train, ctx);
  const best = m.prob('1'.repeat(rules.length));
  const verdict = (j) => { const p = m.predict(j); return p >= best - 0.005 ? 'Strong' : p > m.base + 0.02 ? 'Fair' : 'Weak'; };
  const bands = ['Strong', 'Fair', 'Weak'].map((v) => {
    const g = test.filter((j) => verdict(j) === v);
    const predicted = g.length ? g.reduce((s, j) => s + m.predict(j), 0) / g.length : NaN;
    return [v, g.length, pct(g.length / test.length, 0), pct(predicted), `**${pct(rate(g))}**`];
  });
  const byCount = [...Array(rules.length + 1).keys()].map((k) => {
    const g = test.filter((j) => rules.filter((r) => r.test(j, ctx)).length === k);
    return [`${k} of ${rules.length}`, g.length, pct(rate(g))];
  });
  body += `\n## ${stage[0].toUpperCase() + stage.slice(1)}\n\nRules: ${ids.map((x) => `\`${x}\``).join(' · ')}. `
    + `Strong = ${pct(best)}, overall rate learned ${pct(m.base)}.\n\n`
    + table(['Checker verdict', 'Held-out disputes', 'Share', 'Predicted win rate', 'Actual win rate'], bands)
    + '\n\n' + table(['Rules met', 'Held-out disputes', 'Actual win rate'], byCount) + '\n';
}
writeResults(import.meta.url, '08 · Rule checker verdicts', body);
