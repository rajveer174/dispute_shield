// How well the low / medium / high dispute-risk tiers separate payments on the held-out months.
// Cut-offs are percentiles of the early model's scores on its own training payments (as the app does).
import fs from 'node:fs';
import path from 'node:path';
import { EXP_OUT, need, readCsv, table, pct, writeResults } from '../lib.mjs';

const rows = readCsv(path.join(EXP_OUT, 'holdout_dispute_risk.csv'));
const meta = JSON.parse(fs.readFileSync(need(path.join(EXP_OUT, 'holdout_dispute_risk.json')), 'utf8'));
const base = rows.reduce((s, r) => s + r.y, 0) / rows.length;
const totalDisputes = rows.reduce((s, r) => s + r.y, 0);

function tiers(lo, hi) {
  const t = { low: [], medium: [], high: [] };
  for (const r of rows) t[r.logistic_l2 >= hi ? 'high' : r.logistic_l2 >= lo ? 'medium' : 'low'].push(r);
  return ['high', 'medium', 'low'].map((k) => {
    const d = t[k].reduce((s, r) => s + r.y, 0), rate = t[k].length ? d / t[k].length : NaN;
    return [k, t[k].length.toLocaleString('en-US'), pct(t[k].length / rows.length, 0), d, pct(rate, 2),
      `${(rate / base).toFixed(1)}×`, pct(d / totalDisputes, 0)];
  });
}
const COLS = ['Tier', 'Payments', 'Share', 'Disputed', 'Dispute rate', 'vs average', 'Share of all disputes'];

let body = `${rows.length.toLocaleString('en-US')} held-out payments, ${totalDisputes} disputed (${pct(base, 2)}). `
  + `Cut-offs from the early model's training scores: 70th percentile = ${pct(meta.train_logistic_q70, 2)}, `
  + `90th = ${pct(meta.train_logistic_q90, 2)}.\n\n## Used: 70th / 90th percentile (top 10% high, next 20% medium)\n\n`;
body += table(COLS, tiers(meta.train_logistic_q70, meta.train_logistic_q90));
body += '\n\n## Alternatives\n\n';
const sorted = rows.map((r) => r.logistic_l2).sort((a, b) => a - b);
const q = (p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
for (const [name, lo, hi] of [
  ['80th / 95th percentile (held-out scores)', q(0.8), q(0.95)],
  ['50th / 90th percentile (held-out scores)', q(0.5), q(0.9)],
  ['Fixed 1% / 2% chance of a dispute', 0.01, 0.02],
  ['Fixed 25% / 50% (an "intuitive" absolute scale)', 0.25, 0.5],
]) body += `### ${name}\n\n${table(COLS, tiers(lo, hi))}\n\n`;
writeResults(import.meta.url, '06 · Dispute-risk tiers', body);
