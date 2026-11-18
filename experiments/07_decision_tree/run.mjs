// Decision tree (depth 5, log-loss splits; backend/.../model/DecisionTreeModel.java) against the chosen logistic
// model on the same held-out rows, with a seeded bootstrap of the AUC difference.
import path from 'node:path';
import { EXP_OUT, readCsv, auc, brier, ece, f3, table, writeResults } from '../lib.mjs';

const KEYS = { dispute_risk: 'Chance of a dispute', invoice_win: 'Invoice win', payment_win: 'Payment win', dispute_win: 'Dispute win' };
const B = 1000;
let seed = 42;
const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);

const out = [];
for (const [key, label] of Object.entries(KEYS)) {
  const rows = readCsv(path.join(EXP_OUT, `holdout_${key}.csv`));
  const y = rows.map((r) => r.y), pl = rows.map((r) => r.logistic_l2), pt = rows.map((r) => r.decision_tree);
  const diffs = [];
  for (let b = 0; b < B; b++) {
    const idx = Array.from({ length: rows.length }, () => Math.floor(rand() * rows.length));
    const yy = idx.map((i) => y[i]);
    diffs.push(auc(idx.map((i) => pl[i]), yy) - auc(idx.map((i) => pt[i]), yy));
  }
  diffs.sort((a, b) => a - b);
  const lo = diffs[Math.floor(0.025 * B)], hi = diffs[Math.floor(0.975 * B)];
  out.push([label, rows.length.toLocaleString('en-US'), f3(auc(pl, y)), f3(auc(pt, y)),
    `${f3(lo)} to ${f3(hi)}`, `${brier(pl, y).toFixed(4)} / ${brier(pt, y).toFixed(4)}`,
    `${ece(pl, y).toFixed(4)} / ${ece(pt, y).toFixed(4)}`, new Set(pt.map((v) => v.toFixed(6))).size]);
}
writeResults(import.meta.url, '07 · Decision tree vs logistic regression',
  `Same features, same training months, scored on the same held-out rows. Bootstrap: ${B} resamples, seed 42.\n\n`
  + table(['Prediction', 'Held-out rows', 'Logistic AUC', 'Tree AUC', 'AUC gap (logistic − tree), 95% interval',
    'Brier (logistic / tree)', 'ECE (logistic / tree)', 'Distinct tree probabilities'], out));
