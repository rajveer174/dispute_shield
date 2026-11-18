// Back-test of fight/accept policies on the held-out months. Uses outputs/experiments/dispute_backtest.csv
// (written by the experiments training; one row per resolved dispute, no IDs). The dispute-win model there was
// trained on the earlier months only, and the rule table below is learned on the earlier months only.
//
// Realised value is measured against "accept everything": fighting a dispute is worth y x (amount + fee) - cost,
// where y = 1 if it was won. Only disputes the team actually responded to are scored, because only for those do we
// know what fighting led to.
import path from 'node:path';
import { EXP_OUT, readCsv, table, pct, usd, writeResults } from '../lib.mjs';

const FEE = 25, COST = 15, SHRINK = 20;
const rows = readCsv(path.join(EXP_OUT, 'dispute_backtest.csv')).map((r) => ({ ...r, rule_key: String(r.rule_key).padStart(4, '0') }));
const train = rows.filter((r) => r.test === 0), test = rows.filter((r) => r.test === 1);
const scored = test.filter((r) => r.responded === 1);

// Rule table as in WinRules.java: shrunk cell rates, then "one more rule met never lowers it".
function ruleTable(rs) {
  const base = rs.reduce((s, r) => s + r.y, 0) / rs.length;
  const cells = new Map();
  for (const r of rs) { const c = cells.get(r.rule_key) || [0, 0]; c[0]++; c[1] += r.y; cells.set(r.rule_key, c); }
  const raw = new Map([...cells].map(([k, [n, w]]) => [k, (w + SHRINK * base) / (n + SHRINK)]));
  const sub = (a, b) => [...a].every((ch, i) => ch === '0' || b[i] === '1');
  return (k) => { const v = [...raw].filter(([s]) => sub(s, k)).map(([, p]) => p); return v.length ? Math.max(...v) : base; };
}
const ruleP = ruleTable(train);
for (const r of scored) r.p_rule = ruleP('1' + r.rule_key.slice(1));

function run(name, fight) {
  let n = 0, won = 0, value = 0, missed = 0;
  for (const r of scored) {
    const v = r.y * (r.amount_usd + FEE) - COST;
    if (fight(r)) { n++; won += r.y; value += v; } else missed += v;
  }
  return [name, `${n} / ${scored.length - n}`, n ? pct(won / n, 0) : '–', usd(value), usd(missed)];
}
const gain = (p, r, cost = COST) => p * (r.amount_usd + FEE) - cost;

let body = `Held-out months: ${test.length} resolved disputes, ${scored.length} where the team responded (scored). `
  + `Fee $${FEE}, fight cost $${COST} (assumptions). Values are relative to accepting everything.\n\n## Policies\n\n`;
body += table(['Policy', 'Fight / accept', 'Won (of fought)', 'Realised value of fighting', 'Value given up by accepting'], [
  run('Accept everything', () => false),
  run('Fight everything', () => true),
  run('Rules p, fight if expected recovery > cost', (r) => gain(r.p_rule, r) > 0),
  run('Rules p + 35% minimum win chance', (r) => gain(r.p_rule, r) > 0 && r.p_rule >= 0.35),
  run('Model p, fight if expected recovery > cost', (r) => gain(r.p_model_if_responded, r) > 0),
  run('**Model p + $25 minimum expected gain (app)**', (r) => gain(r.p_model_if_responded, r) >= 25),
]);
body += '\n\n"Value given up" is what the accepted disputes would have returned had they been fought: positive means '
  + 'accepting them lost money, negative means accepting them saved money.\n';

body += '\n## Sensitivity: minimum expected gain (model p)\n\n';
body += table(['Minimum gain', 'Fight / accept', 'Won (of fought)', 'Realised value', 'Value given up'],
  [0, 10, 25, 50, 100, 200].map((g) => run(`$${g}`, (r) => gain(r.p_model_if_responded, r) >= g)));

body += '\n\n## Sensitivity: fight cost (model p, $25 minimum)\n\n';
const atCost = (c, fight) => {
  let n = 0, value = 0;
  for (const r of scored) if (fight(r)) { n++; value += r.y * (r.amount_usd + FEE) - c; }
  return [n, value];
};
body += table(['Real fight cost', 'Fight everything', 'Model p, break-even only', 'Model p + $25 minimum: fight / accept', 'Model p + $25 minimum: value'],
  [15, 40, 75, 150].map((c) => {
    const [, all] = atCost(c, () => true);
    const [, be] = atCost(c, (r) => gain(r.p_model_if_responded, r, c) >= 0);
    const [n, v] = atCost(c, (r) => gain(r.p_model_if_responded, r, c) >= 25);
    return [`$${c}`, usd(all), usd(be), `${n} / ${scored.length - n}`, usd(v)];
  }));

body += '\n\n## Accepted under the app policy, by reason\n\n';
const reasons = [...new Set(scored.map((r) => r.reason))];
body += table(['Reason', 'Disputes', 'Accepted (rules p + 35%)', 'Accepted (model p + $25)'], reasons.map((re) => {
  const g = scored.filter((r) => r.reason === re);
  return [re, g.length, g.filter((r) => !(gain(r.p_rule, r) > 0 && r.p_rule >= 0.35)).length,
    g.filter((r) => gain(r.p_model_if_responded, r) < 25).length];
}));
writeResults(import.meta.url, '05 · Fight / accept policy back-test', body);
