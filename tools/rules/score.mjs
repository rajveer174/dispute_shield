// Final rules per moment (3 for invoice and payment, 4 for a received dispute) -> "Dispute win probability" for every invoice, payment and dispute.
// 1. Checks each rule set on the last 4 months after learning on the earlier months (honest test).
// 2. Learns the final probability table from all resolved disputes.
// 3. Writes outputs/*.csv and outputs/win_rules.md.
// Usage: node tools/rules/score.mjs data outputs
import fs from 'node:fs';
import path from 'node:path';
import { load, auc } from './lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from './rules.mjs';

const dataDir = process.argv[2] || 'data';
const outDir = process.argv[3] || 'outputs';

export const FINAL = {
  'invoice ready': [
    { id: 'signed_estimate', name: 'Rule 1 · Signed estimate', col: 'rule1_signed_estimate' },
    { id: 'address', name: 'Rule 2 · Customer address on the invoice', col: 'rule2_customer_address' },
    { id: 'presence: gps OR any photo', name: 'Rule 3 · Proof the job happened: GPS check-in or at least one job photo', col: 'rule3_gps_or_photo' },
  ],
  'payment collected': [
    { id: 'paperwork score >= 3 of 6', name: 'Rule 1 · Invoice paperwork: at least 3 of 6 items', col: 'rule1_paperwork_3_of_6' },
    { id: 'signed_estimate', name: 'Rule 2 · Signed estimate', col: 'rule2_signed_estimate' },
    { id: 'card checks: present OR avs+cvv match', name: 'Rule 3 · Card verified: tapped/chip, or address and security code both matched', col: 'rule3_card_verified' },
  ],
  'dispute received': [
    { id: 'responded', name: 'Rule 1 · Team responded to the dispute', col: 'rule1_responded' },
    { id: 'evidence >= 4 items', name: 'Rule 2 · At least 4 pieces of evidence submitted', col: 'rule2_evidence_4_plus' },
    { id: 'not fraud', name: 'Rule 3 · Reason is not fraud', col: 'rule3_not_fraud' },
    { id: 'no late photos', name: 'Rule 4 · No photos added after the dispute opened', col: 'rule4_no_late_photos' },
  ],
};

const { invoiceJobs, paymentJobs, disputeJobs, missing } = load(dataDir);
const jobs = disputeJobs;
const resolved = jobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const train = resolved.filter((j) => j.disputeMonth <= maxM - 4), test = resolved.filter((j) => j.disputeMonth > maxM - 4);

const models = {}, report = {};
for (const [stage, picks] of Object.entries(FINAL)) {
  const rules = picks.map((p) => ({ ...CANDIDATES[stage].find((c) => c.id === p.id), ...p }));
  const cv = lookupModel(rules, train, { matchMap: learnReasonEvidence(train) });
  const testAuc = auc(test.map(cv.predict), test.map((j) => j.label));
  // Win rate when each rule is met / not met, over all resolved disputes.
  const perRule = rules.map((r) => {
    const yes = resolved.filter((j) => r.test(j, {})), no = resolved.filter((j) => !r.test(j, {}));
    const rate = (a) => a.reduce((s, j) => s + j.label, 0) / a.length;
    return { name: r.name, share: yes.length / resolved.length, yes: rate(yes), no: rate(no) };
  });
  const m = lookupModel(rules, resolved, {});
  models[stage] = { rules, m };
  report[stage] = { testAuc, perRule, m };
}

// ---------------------------------------------------------------- write outputs
fs.mkdirSync(outDir, { recursive: true });
const yn = (b) => (b ? 'yes' : 'no');
const p3 = (v) => v.toFixed(3);
const esc = (v) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
function write(file, header, rows) {
  const s = fs.createWriteStream(path.join(outDir, file));
  s.write(header.join(',') + '\n');
  for (const r of rows) s.write(r.map(esc).join(',') + '\n');
  s.end();
  return rows.length;
}
const flags = (stage, j) => models[stage].rules.map((r) => yn(r.test(j, {})));
const cols = (stage) => models[stage].rules.map((r) => r.col);

const nInv = write('invoice_win_probability.csv',
  ['invoice_id', 'team_id', ...cols('invoice ready'), 'rules_met', 'Dispute win probability'],
  invoiceJobs.map((j) => {
    const f = flags('invoice ready', j);
    return [j.inv.invoice_id, j.inv.team_id, ...f, f.filter((x) => x === 'yes').length, p3(models['invoice ready'].m.predict(j))];
  }));
const paid = paymentJobs;
const nPay = write('payment_win_probability.csv',
  ['payment_id', 'invoice_id', 'team_id', ...cols('payment collected'), 'rules_met', 'Dispute win probability'],
  paid.map((j) => {
    const f = flags('payment collected', j);
    return [j.pay.payment_id, j.inv.invoice_id, j.inv.team_id, ...f, f.filter((x) => x === 'yes').length, p3(models['payment collected'].m.predict(j))];
  }));
const disputed = disputeJobs;
const nDisp = write('dispute_win_probability.csv',
  ['dispute_id', 'payment_id', 'invoice_id', 'team_id', 'reason', ...cols('dispute received'), 'rules_met', 'Dispute win probability', 'actual_outcome'],
  disputed.map((j) => {
    const f = flags('dispute received', j);
    return [j.disp.dispute_id, j.pay.payment_id, j.inv.invoice_id, j.inv.team_id, j.disp.reason, ...f,
      f.filter((x) => x === 'yes').length, p3(models['dispute received'].m.predict(j)), j.disp.outcome];
  }));

// ---------------------------------------------------------------- rules document
const pct = (v) => `${(v * 100).toFixed(0)}%`;
let md = `# Dispute win rules\n\nLearned from ${resolved.length} resolved disputes (withdrawn counted as won; overall win rate ${pct(models['invoice ready'].m.base)}).\n`
  + `Each moment uses three yes/no rules. Each of the 8 yes/no combinations gets a win probability: the win rate of past disputes with `
  + `that combination, pulled toward the overall rate when the combination is rare. Meeting one more rule never lowers the `
  + `probability. Combinations with 0 past disputes cannot happen in practice (for example, evidence without a response).\n`;
for (const [stage, r] of Object.entries(report)) {
  md += `\n## ${stage[0].toUpperCase() + stage.slice(1)}\n\nTested on the last 4 months after learning on earlier ones: AUC ${r.testAuc.toFixed(3)}.\n\n`
    + '| Rule | Jobs meeting it | Win rate if yes | Win rate if no |\n|---|---|---|---|\n';
  for (const x of r.perRule) md += `| ${x.name} | ${pct(x.share)} | ${pct(x.yes)} | ${pct(x.no)} |\n`;
  md += '\n| R1 | R2 | R3 | Past disputes | Dispute win probability |\n|---|---|---|---|---|\n';
  const keys = ['111', '110', '101', '011', '100', '010', '001', '000'];
  for (const k of keys) {
    const c = r.m.cells.get(k) || { n: 0 };
    md += `| ${k.split('').map((b) => (b === '1' ? 'yes' : 'no')).join(' | ')} | ${c.n} | ${pct(r.m.prob(k))} |\n`;
  }
}
fs.writeFileSync(path.join(outDir, 'win_rules.md'), md);

console.log(md);
console.log(`\nWrote ${nInv} invoices, ${nPay} payments, ${nDisp} disputes to ${path.resolve(outDir)}`);
