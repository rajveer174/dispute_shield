// Do 4th/5th dispute rules beat the three current ones on the last 4 months? Aggregates only.
import { load, auc } from './lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from './rules.mjs';

const { disputeJobs } = load(process.argv[2] || 'data');
const t = (s) => (s ? Date.parse(s.replace(' ', 'T')) : null);
const lagDays = (j) => (t(j.disp.opened_at) - t(j.pay?.collected_at)) / 864e5;
const extra = [
  { id: 'opened 90+ days after payment', test: (j) => lagDays(j) >= 90 },
  { id: 'reason is duplicate or credit not processed', test: (j) => ['duplicate', 'credit_not_processed'].includes(j.disp.reason) },
];
const all = [...CANDIDATES['dispute received'], ...extra];
const get = (id) => all.find((c) => c.id === id);

const resolved = disputeJobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };
console.log(`train ${train.length}  test ${test.length}`);

for (const r of extra) {
  const n = resolved.filter((j) => r.test(j, ctx)).length;
  console.log(`  "${r.id}" is true for ${n} of ${resolved.length}`);
}

const base = ['responded', 'evidence >= 4 items', 'not fraud'];
const sets = [
  base,
  ...extra.map((r) => [...base, r.id]),
  ...['reason-matched evidence', 'no late photos', 'signed estimate submitted', 'job photos submitted'].map((id) => [...base, id]),
  [...base, 'reason is duplicate or credit not processed', 'opened within 30 days of payment'],
  [...base, 'reason is duplicate or credit not processed', 'no late photos'],
  [...base, 'opened within 30 days of payment', 'signed estimate submitted'],
  [...base, 'opened within 30 days of payment', 'no late photos'],
];
for (const ids of sets) {
  const m = lookupModel(ids.map(get), train, ctx);
  const te = auc(test.map(m.predict), test.map((j) => j.label));
  const tr = auc(train.map(m.predict), train.map((j) => j.label));
  console.log(`  train ${tr.toFixed(3)}  test ${te.toFixed(3)}   ${ids.length} rules: ${ids.slice(3).join(' + ') || '(current three)'}`);
}
