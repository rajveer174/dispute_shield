// Three approaches per prediction, trained on earlier months and scored on the held-out last 4 months.
// Reads outputs/experiments/training_report.json (the trainer's own comparison, fixed seeds).
import fs from 'node:fs';
import path from 'node:path';
import { report, table, f3, pct, writeResults, EXP_OUT, need } from '../lib.mjs';

const f4 = (x) => (Number.isFinite(x) ? x.toFixed(4) : '–');
// Month index 0 = the first calendar month in the data (2024-10). Win models split on the dispute month,
// the risk model on the payment month.
const meta = (key) => JSON.parse(fs.readFileSync(need(path.join(EXP_OUT, `holdout_${key}.json`)), 'utf8'));

const r = report();
const NAMES = {
  dispute_risk: 'Chance of a dispute (payment collected)',
  invoice_win: 'Win if disputed (invoice ready)',
  payment_win: 'Win if disputed (payment collected)',
  dispute_win: 'Win (dispute received)',
};
let body = `Each model is trained on the earlier months and scored on the last ${r.assumptions.validation_months} months, which it never saw.\n`
  + 'AUC: ranking (higher is better). Brier and log-loss: probability error (lower is better). '
  + 'ECE: average gap between predicted and observed rate over 10 bins (lower is better).\n';

for (const [key, reports] of Object.entries(r.validation)) {
  const rows = reports.filter((x) => x.approach !== 'decision_tree');
  const best = rows.reduce((a, b) => (b.auc > a.auc ? b : a));
  const m = meta(key);
  body += `\n## ${NAMES[key] || key}\n\nTrained on ${m.train_rows.toLocaleString('en-US')} rows (month index ≤ ${m.train_months_up_to}); `
    + `${rows[0].rows.toLocaleString('en-US')} held-out rows, positive rate ${pct(rows[0].positiveRate)}.\n\n`;
  body += table(['Approach', 'AUC', 'Brier', 'Log-loss', 'ECE', ''],
    rows.map((x) => [x.approach, f3(x.auc), f4(x.brier), f3(x.logLoss), f4(x.ece),
      x.approach === r.chosenApproach[key] ? '**chosen**' : x === best ? 'best AUC' : '']));
  const chosen = reports.find((x) => x.approach === r.chosenApproach[key]);
  if (chosen?.calibration?.length) {
    body += `\n\nReliability of the chosen model (10 equal-count bins):\n\n`;
    body += table(['Bin', 'Rows', 'Mean predicted', 'Observed'],
      chosen.calibration.map((c, i) => [i + 1, c.count, pct(c.meanPredicted), pct(c.observedRate)]));
  }
  body += '\n';
}
writeResults(import.meta.url, '01 · Model comparison', body);
