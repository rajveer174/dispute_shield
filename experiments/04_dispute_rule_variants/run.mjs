// Variants of the dispute-received rules: adding a 4th/5th rule, a 90-day rule, and swapping "not fraud" for
// reason-neutral rules. Also win rate by days from payment to dispute. Usage: node run.mjs data
import path from 'node:path';
import { load } from '../../tools/rules/lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from '../../tools/rules/rules.mjs';
import { ROOT, auc, f3, pct, table, writeResults } from '../lib.mjs';

const { disputeJobs } = load(process.argv[2] || path.join(ROOT, 'data'));
const ts = (s) => (s ? Date.parse(s.replace(' ', 'T')) : NaN);
const lagDays = (j) => (ts(j.disp.opened_at) - ts(j.pay?.collected_at)) / 864e5;
const isResponded = (j) => /^(true|1|yes)$/i.test(j.disp.merchant_responded);

const resolved = disputeJobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };

const extra = [
  { id: 'opened 90+ days after payment', test: (j) => lagDays(j) >= 90 },
];
const pool = [...CANDIDATES['dispute received'], ...CANDIDATES['payment collected'], ...extra];
const get = (id) => pool.find((c) => c.id === id) || (() => { throw new Error(id); })();
const BASE = ['responded', 'evidence >= 4 items', 'not fraud'];

const VARIANTS = [
  ['A', 'Original three rules', BASE, 'replaced by D'],
  ['B', '+ opened within 30 days of payment', [...BASE, 'opened within 30 days of payment'], 'rejected'],
  ['C', '+ no photos added after the dispute opened', [...BASE, 'no late photos'], ''],
  ['D', '+ no late photos (4 rules)', [...BASE, 'no late photos'], '**kept (explanation)**'],
  ['E', '+ 30 days + no late photos (5 rules)', [...BASE, 'opened within 30 days of payment', 'no late photos'], 'rejected (30-day rule removed)'],
  ['F', '+ opened 90+ days after payment', [...BASE, 'opened 90+ days after payment'], 'rejected: no data past 90 days'],
  ['G', '"not fraud" → card verified', ['responded', 'evidence >= 4 items', 'card checks: present OR avs+cvv match', 'no late photos'], 'rejected'],
  ['H', '"not fraud" → signed estimate submitted', ['responded', 'evidence >= 4 items', 'signed estimate submitted', 'no late photos'], 'rejected'],
  ['I', '"not fraud" → evidence matching the reason', ['responded', 'evidence >= 4 items', 'reason-matched evidence', 'no late photos'], 'rejected'],
  ['J', 'reason-matched ×2 + card verified', ['responded', 'reason-matched evidence x2', 'card checks: present OR avs+cvv match', 'no late photos'], 'rejected'],
].filter((v, i, a) => a.findIndex((w) => w[2].join() === v[2].join()) === i || v[0] === 'D');

const MIN = 0.35;
const rows = [];
for (const [id, name, ids, decision] of VARIANTS) {
  if (id === 'C') continue; // same set as D; D is the labelled row
  const rules = ids.map(get);
  const cv = lookupModel(rules, train, ctx);
  const te = auc(test.map(cv.predict), test.map((j) => j.label));
  // Under the old "35% minimum win chance" decision: which reasons would be accepted (win chance if responded).
  const all = lookupModel(rules, resolved, ctx);
  const asResponded = (j) => all.predict(new Proxy(j, { get: (o, k) => (k === 'disp' ? { ...o.disp, merchant_responded: 'true' } : o[k]) }));
  const byReason = {};
  for (const j of disputeJobs) {
    const r = (byReason[j.disp.reason] ||= { n: 0, a: 0 });
    r.n++; if (asResponded(j) < MIN) r.a++;
  }
  const accepted = Object.entries(byReason).filter(([, v]) => v.a).map(([k, v]) => `${k} ${v.a}/${v.n}`).join(', ') || 'none';
  rules.length && rows.push([id, name, rules.length, f3(te), accepted, decision]);
}

let body = `${train.length} disputes to learn from, ${test.length} test disputes (last 4 months).\n\n## Rule-set variants\n\n`;
body += table(['', 'Variant', 'Rules', 'Test AUC', `Accepted at a ${MIN * 100}% minimum (all disputes)`, 'Decision'], rows);

body += '\n\n## Win rate by days from payment to dispute\n\n';
const lagRows = [];
for (const [lo, hi] of [[0, 15], [15, 30], [30, 45], [45, 60], [60, 90], [90, Infinity]]) {
  const b = resolved.filter((j) => lagDays(j) >= lo && lagDays(j) < hi), r = b.filter(isResponded);
  const rate = (a) => (a.length ? a.filter((j) => j.label === 1).length / a.length : NaN);
  lagRows.push([`${lo}–${hi === Infinity ? '' : hi} days`, b.length, pct(rate(b)), r.length, pct(rate(r))]);
}
body += table(['Opened after payment', 'Disputes', 'Win rate', 'Team responded', 'Win rate (responded)'], lagRows);
writeResults(import.meta.url, '04 · Dispute rule variants', body);
