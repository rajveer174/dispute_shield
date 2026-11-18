// Three yes/no rules per moment: every candidate alone, an exhaustive search over sets of three, the sets
// picked for the app, and ablations of the table settings (shrinkage k, monotone constraint).
// Rules learn on dispute months <= last-4 and are scored on the last 4 months. Usage: node run.mjs data
import fs from 'node:fs';
import path from 'node:path';
import { load } from '../../tools/rules/lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from '../../tools/rules/rules.mjs';
import { ROOT, EXP_OUT, auc, f3, pct, table, writeResults } from '../lib.mjs';

const { disputeJobs } = load(process.argv[2] || path.join(ROOT, 'data'));
const resolved = disputeJobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);
const ctx = { matchMap: learnReasonEvidence(train) };
const yTest = test.map((j) => j.label);

const CHOSEN = {
  'invoice ready': ['signed_estimate', 'address', 'presence: gps OR any photo'],
  'payment collected': ['paperwork score >= 3 of 6', 'signed_estimate', 'card checks: present OR avs+cvv match'],
  'dispute received': ['responded', 'evidence >= 4 items', 'not fraud'],
};
// The full logistic model for the same moment, from experiments mode (01), for reference.
const MODEL_KEY = { 'invoice ready': 'invoice_win', 'payment collected': 'payment_win', 'dispute received': 'dispute_win' };
const modelAuc = (stage) => {
  try {
    const r = JSON.parse(fs.readFileSync(path.join(EXP_OUT, 'training_report.json'), 'utf8'));
    return r.validation[MODEL_KEY[stage]].find((x) => x.approach === 'logistic_l2').auc;
  } catch { return NaN; }
};

/** Same table as lookupModel, without the "one more rule never lowers it" step (ablation). */
function rawModel(rules, rows, k) {
  const base = rows.reduce((s, j) => s + j.label, 0) / rows.length;
  const cells = new Map();
  const key = (j) => rules.map((r) => (r.test(j, ctx) ? 1 : 0)).join('');
  for (const j of rows) { const c = cells.get(key(j)) || { n: 0, w: 0 }; c.n++; c.w += j.label; cells.set(key(j), c); }
  return { predict: (j) => { const c = cells.get(key(j)) || { n: 0, w: 0 }; return (c.w + k * base) / (c.n + k); } };
}
const testAuc = (m) => auc(test.map(m.predict), yTest);
const trainAuc = (m) => auc(train.map(m.predict), train.map((j) => j.label));

let body = `${train.length} resolved disputes to learn from (dispute months ≤ ${maxM - 4}), ${test.length} to test on (months ${maxM - 3}–${maxM}).\n`
  + 'Each set of rules becomes a lookup table of win rates per yes/no combination (rare combinations pulled toward the overall rate, k = 20).\n';

for (const [stage, cands] of Object.entries(CANDIDATES)) {
  const get = (id) => cands.find((c) => c.id === id);
  body += `\n## ${stage[0].toUpperCase() + stage.slice(1)}\n\n### Each candidate rule alone (training months)\n\n`;
  const single = cands.map((c) => {
    const yes = train.filter((j) => c.test(j, ctx)), no = train.filter((j) => !c.test(j, ctx));
    const rate = (a) => (a.length ? a.reduce((s, j) => s + j.label, 0) / a.length : NaN);
    return { c, share: yes.length / train.length, yes: rate(yes), no: rate(no), lift: rate(yes) - rate(no) };
  }).sort((a, b) => (b.lift || -1) - (a.lift || -1));
  body += table(['Rule', 'Share meeting it', 'Win if yes', 'Win if no', 'Lift'],
    single.map((s) => [`\`${s.c.id}\``, pct(s.share, 0), pct(s.yes), pct(s.no), Number.isFinite(s.lift) ? `${(100 * s.lift).toFixed(1)} pts` : '–']));

  // Exhaustive search over sets of three, ranked by training AUC (the test months are not used to choose).
  const sets = [];
  for (let a = 0; a < cands.length; a++) for (let b = a + 1; b < cands.length; b++) for (let c = b + 1; c < cands.length; c++) {
    const m = lookupModel([cands[a], cands[b], cands[c]], train, ctx);
    sets.push({ ids: [cands[a].id, cands[b].id, cands[c].id], tr: trainAuc(m), te: testAuc(m) });
  }
  sets.sort((x, y) => y.tr - x.tr);
  const chosenIds = CHOSEN[stage];
  const chosen = sets.find((s) => chosenIds.every((id) => s.ids.includes(id)));
  const rankOf = sets.indexOf(chosen) + 1;
  body += `\n\n### Sets of three (${sets.length} searched, top 5 by training AUC)\n\n`;
  body += table(['Rank', 'Rules', 'Train AUC', 'Test AUC'],
    sets.slice(0, 5).map((s, i) => [i + 1, s.ids.map((x) => `\`${x}\``).join(' + '), f3(s.tr), f3(s.te)]));
  body += `\n\n**Chosen for the app:** ${chosenIds.map((x) => `\`${x}\``).join(' + ')} — training rank ${rankOf} of ${sets.length}, `
    + `train AUC ${f3(chosen.tr)}, test AUC **${f3(chosen.te)}**. Full logistic model for this moment: ${f3(modelAuc(stage))}.\n`;

  const rules = chosenIds.map(get);
  body += '\n### Table settings for the chosen set (test AUC)\n\n';
  body += table(['Setting', 'Test AUC'], [
    ...[0, 5, 20, 50, 100].map((k) => [`shrink k = ${k}${k === 20 ? ' (used)' : ''}, monotone`, f3(testAuc(lookupModel(rules, train, ctx, k)))]),
    ['shrink k = 20, **no** monotone step', f3(testAuc(rawModel(rules, train, 20)))],
    ['shrink k = 0, no monotone step (raw rates)', f3(testAuc(rawModel(rules, train, 0)))],
  ]);
  body += '\n';
}
writeResults(import.meta.url, '03 · Rule search (three rules per moment)', body);
