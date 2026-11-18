// Dispute rule sets that do not lean on the reason alone: test AUC on the last 4 months, and how the
// 35% minimum would split fight / accept by reason (win chance if responded). Aggregates only.
import { load, auc } from './lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from './rules.mjs';

const MIN = Number(process.argv[3] || 0.35);
const { disputeJobs } = load(process.argv[2] || 'data');
const resolved = disputeJobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };

const pool = [...CANDIDATES['dispute received'], ...CANDIDATES['payment collected'], ...CANDIDATES['invoice ready']];
const get = (id) => pool.find((c) => c.id === id) || (() => { throw new Error(id); })();
const responded = get('responded');

const SETS = {
  'current: responded, 4+ evidence, not fraud, no late photos': ['responded', 'evidence >= 4 items', 'not fraud', 'no late photos'],
  'A: reason-matched evidence instead of "not fraud"': ['responded', 'evidence >= 4 items', 'reason-matched evidence', 'no late photos'],
  'B: card verified instead of "not fraud"': ['responded', 'evidence >= 4 items', 'card checks: present OR avs+cvv match', 'no late photos'],
  'C: signed estimate submitted instead of "not fraud"': ['responded', 'evidence >= 4 items', 'signed estimate submitted', 'no late photos'],
  'D: reason-matched evidence x2 + card verified': ['responded', 'reason-matched evidence x2', 'card checks: present OR avs+cvv match', 'no late photos'],
};

for (const [name, ids] of Object.entries(SETS)) {
  const rules = ids.map(get);
  const cv = lookupModel(rules, train, ctx);
  const te = auc(test.map(cv.predict), test.map((j) => j.label));
  // Final table on all resolved disputes; decision uses the win chance with "responded" forced on.
  const m = lookupModel(rules, resolved, ctx);
  const asResponded = (j) => m.predict(new Proxy(j, {
    get: (o, k) => (k === 'disp' ? { ...o.disp, merchant_responded: 'true' } : o[k]),
  }));
  const byReason = {};
  for (const j of disputeJobs) {
    const r = (byReason[j.disp.reason] ||= { n: 0, accept: 0 });
    r.n++;
    if (asResponded(j) < MIN) r.accept++;
  }
  console.log(`\n${name}\n  test AUC ${te.toFixed(3)}   accepted below ${MIN * 100}%:`,
    Object.entries(byReason).map(([k, v]) => `${k} ${v.accept}/${v.n}`).join(', '));
}
