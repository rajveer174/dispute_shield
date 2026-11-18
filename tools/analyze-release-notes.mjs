// Checks how the product changes in release_notes.md show up in the data.
// Prints AGGREGATES ONLY (counts, shares, percentiles), never row-level data, so the output can be shared.
//
//   node tools/analyze-release-notes.mjs <dataDir> [reportFile]
//
// Release notes being tested:
//   Nov 2024  disputes recorded on the date the card network notifies us
//   Nov 2024  photos can be added at any time, including after the job is closed
//   Dec 2025  completion signature, rolled out team by team over three months, optional per team
//   Jun 2026  invoice status (open / paid / disputed), backfilled for older invoices
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2] || 'data';
const reportFile = process.argv[3] || 'analysis/release-notes-report.md';

// ------------------------------------------------------------------ CSV reading (handles quoted fields)
function readCsv(name) {
  const file = path.join(dir, name);
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8').replace(/^﻿/, '');
  const rows = [];
  let field = '', row = [], q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') q = false;
      else field += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  const header = rows.shift().map((h) => h.trim().toLowerCase());
  return rows.map((r) => Object.fromEntries(header.map((h, i) => [h, (r[i] ?? '').trim()])));
}

const ts = (s) => {
  if (!s) return null;
  let v = s.trim().replace(' ', 'T');
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) v += 'T00:00:00';
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(v)) v += 'Z';
  const t = Date.parse(v);
  return Number.isNaN(t) ? null : t;
};
const bool = (s) => ['true', 't', '1', '1.0', 'yes', 'y'].includes(String(s).trim().toLowerCase());
const month = (t) => { const d = new Date(t); return (d.getUTCFullYear() - 2024) * 12 + d.getUTCMonth() + 1 - 10; };
const monthLabel = (m) => { const d = new Date(Date.UTC(2024, 10 + m - 1, 1)); return d.toISOString().slice(0, 7); };
const DAY = 86400e3;
const pct = (a, b) => (b ? `${((100 * a) / b).toFixed(1)}%` : '–');
const quant = (arr, q) => { if (!arr.length) return NaN; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(q * (s.length - 1)))]; };
const f1 = (v) => (Number.isFinite(v) ? v.toFixed(1) : '–');

const out = [];
const h = (t) => out.push(`\n## ${t}\n`);
const p = (t) => out.push(t);
const table = (head, rows) => {
  out.push(`| ${head.join(' | ')} |`, `| ${head.map(() => '---').join(' | ')} |`);
  rows.forEach((r) => out.push(`| ${r.join(' | ')} |`));
  out.push('');
};

// ------------------------------------------------------------------ load
const teams = readCsv('teams.csv') || [];
const invoices = readCsv('invoices.csv') || [];
const photos = readCsv('photos.csv') || [];
const payments = readCsv('payments.csv') || [];
const disputes = readCsv('disputes.csv') || [];
out.push(`# Release-notes data checks\n\nData folder: \`${path.resolve(dir)}\`. Generated ${new Date().toISOString()}. Aggregates only.\n`);
table(['File', 'Rows'], [['teams', teams.length], ['invoices', invoices.length], ['photos', photos.length], ['payments', payments.length], ['disputes', disputes.length]]);

const invById = new Map(invoices.map((i) => [i.invoice_id, i]));
const payById = new Map(payments.map((x) => [x.payment_id, x]));
const payByInv = new Map(payments.map((x) => [x.invoice_id, x]));
const dispByPay = new Map();
for (const d of disputes) if (!dispByPay.has(d.payment_id)) dispByPay.set(d.payment_id, d);
const isWin = (o) => o === 'won' || o === 'withdrawn';
const resolved = (o) => o === 'won' || o === 'lost' || o === 'withdrawn';

// ================================================================== 1. disputes recorded on network notification date
h('1. Dispute timing (recorded when the card network notifies us)');
const lags = [];
const disputeMonthRows = new Map();
for (const d of disputes) {
  const pay = payById.get(d.payment_id);
  const o = ts(d.opened_at), c = pay && ts(pay.collected_at);
  if (o && c) lags.push((o - c) / DAY);
}
table(['Days from payment to dispute', 'Value'], [
  ['median', f1(quant(lags, 0.5))], ['p75', f1(quant(lags, 0.75))], ['p90', f1(quant(lags, 0.9))],
  ['p95', f1(quant(lags, 0.95))], ['p99', f1(quant(lags, 0.99))], ['max', f1(Math.max(...lags))],
  ['negative (dispute before payment)', lags.filter((x) => x < 0).length],
]);
const lagBuckets = [[0, 7], [7, 14], [14, 30], [30, 45], [45, 60], [60, 90], [90, 120], [120, 1e9]];
table(['Lag bucket (days)', 'Disputes', 'Share'], lagBuckets.map(([a, b]) => {
  const n = lags.filter((x) => x >= a && x < b).length;
  return [b > 1e8 ? `${a}+` : `${a}–${b}`, n, pct(n, lags.length)];
}));
// Dispute rate by payment month shows the right-censoring at the end of the window.
for (const x of payments) {
  const t = ts(x.collected_at); if (!t) continue;
  const m = month(t);
  const r = disputeMonthRows.get(m) || { pay: 0, disp: 0, open: 0, resolvedN: 0, won: 0 };
  r.pay++;
  const d = dispByPay.get(x.payment_id);
  if (d) { r.disp++; if (d.outcome === 'open') r.open++; if (resolved(d.outcome)) { r.resolvedN++; if (isWin(d.outcome)) r.won++; } }
  disputeMonthRows.set(m, r);
}
table(['Payment month', 'Payments', 'Disputed', 'Dispute rate', 'Still open', 'Win rate (resolved)'],
  [...disputeMonthRows.entries()].sort((a, b) => a[0] - b[0]).map(([m, r]) =>
    [`${m} (${monthLabel(m)})`, r.pay, r.disp, pct(r.disp, r.pay), pct(r.open, r.disp), pct(r.won, r.resolvedN)]));
p('Read: if the dispute rate falls in the last 1–3 payment months, those payments have not had time to be disputed. '
  + 'Set `censor-days` to about the p90–p95 lag above. A rising "still open" share near the end means recent win rates are not final.');

// ================================================================== 2. photos added any time
h('2. Photos (can be added at any time, even after the job is closed)');
const disputeOpenByInv = new Map();
for (const d of disputes) { const o = ts(d.opened_at); if (o) disputeOpenByInv.set(d.invoice_id || payById.get(d.payment_id)?.invoice_id, o); }
let afterDone = 0, afterPay = 0, afterDispute = 0, withTimes = 0;
const lateByKind = { before: [0, 0], after: [0, 0] };
const asOf = new Map(); // invoice -> {before, after} as at payment
for (const ph of photos) {
  const inv = invById.get(ph.invoice_id); const pay = payByInv.get(ph.invoice_id);
  const u = ts(ph.uploaded_at); if (!u || !pay) continue;
  const col = ts(pay.collected_at), done = inv && ts(inv.job_completed_at);
  withTimes++;
  if (done && u > done) afterDone++;
  const late = col && u > col;
  if (late) afterPay++;
  const op = disputeOpenByInv.get(ph.invoice_id);
  if (op && u > op) afterDispute++;
  const k = ph.kind === 'before' ? 'before' : 'after';
  lateByKind[k][1]++; if (late) lateByKind[k][0]++;
  if (!late) { const a = asOf.get(ph.invoice_id) || { before: 0, after: 0 }; a[k]++; asOf.set(ph.invoice_id, a); }
}
table(['Photo timing', 'Photos', 'Share'], [
  ['uploaded after job completed', afterDone, pct(afterDone, withTimes)],
  ['uploaded after payment collected', afterPay, pct(afterPay, withTimes)],
  ['  before photos uploaded after payment', lateByKind.before[0], pct(lateByKind.before[0], lateByKind.before[1])],
  ['  after photos uploaded after payment', lateByKind.after[0], pct(lateByKind.after[0], lateByKind.after[1])],
  ['uploaded after the dispute opened', afterDispute, pct(afterDispute, withTimes)],
]);
// Leakage size: win rate by "has after photo" using the snapshot count vs the as-at-payment count.
const photoWin = { snap: { y: [0, 0], n: [0, 0] }, asof: { y: [0, 0], n: [0, 0] } };
let mismatch = 0;
const photoCountByInv = new Map();
for (const ph of photos) photoCountByInv.set(ph.invoice_id, (photoCountByInv.get(ph.invoice_id) || 0) + 1);
for (const inv of invoices) {
  const snapCount = (+inv.photos_before_count || 0) + (+inv.photos_after_count || 0);
  if (snapCount !== (photoCountByInv.get(inv.invoice_id) || 0)) mismatch++;
}
for (const d of disputes) {
  if (!resolved(d.outcome)) continue;
  const invId = d.invoice_id || payById.get(d.payment_id)?.invoice_id;
  const inv = invById.get(invId); if (!inv) continue;
  const w = isWin(d.outcome) ? 1 : 0;
  const snapHas = (+inv.photos_after_count || 0) > 0 ? 'y' : 'n';
  const asofHas = (asOf.get(invId)?.after || 0) > 0 ? 'y' : 'n';
  photoWin.snap[snapHas][0] += w; photoWin.snap[snapHas][1]++;
  photoWin.asof[asofHas][0] += w; photoWin.asof[asofHas][1]++;
}
table(['Resolved disputes', 'With after photo: win rate (n)', 'Without: win rate (n)', 'Gap'],
  [['snapshot count (invoices.csv)', `${pct(...photoWin.snap.y)} (${photoWin.snap.y[1]})`, `${pct(...photoWin.snap.n)} (${photoWin.snap.n[1]})`,
    f1(100 * (photoWin.snap.y[0] / (photoWin.snap.y[1] || 1) - photoWin.snap.n[0] / (photoWin.snap.n[1] || 1))) + ' pts'],
   ['as at payment (photos.csv by time)', `${pct(...photoWin.asof.y)} (${photoWin.asof.y[1]})`, `${pct(...photoWin.asof.n)} (${photoWin.asof.n[1]})`,
    f1(100 * (photoWin.asof.y[0] / (photoWin.asof.y[1] || 1) - photoWin.asof.n[0] / (photoWin.asof.n[1] || 1))) + ' pts']]);
p(`Invoices whose photo counts disagree with photos.csv: ${mismatch} of ${invoices.length} (${pct(mismatch, invoices.length)}).`);
p('Read: if the snapshot gap is much bigger than the as-at-payment gap, photos added after the dispute make the evidence look more '
  + 'useful than it was. The months 23–24 uploads show invoices as at payment, so train only on as-at-payment counts (the trainer already does).');

// ================================================================== 3. completion signature, Dec 2025 rollout
h('3. Completion signature (from December 2025, team by team over 3 months, optional)');
const SIG_LAUNCH = Date.UTC(2025, 11, 1); // Dec 2025 = month 14
const sigByMonth = new Map();
const teamFirstSig = new Map();
for (const inv of invoices) {
  const t = ts(inv.job_completed_at) || ts(inv.created_at); if (!t) continue;
  const m = month(t);
  const r = sigByMonth.get(m) || [0, 0];
  r[1]++;
  if (bool(inv.completion_signature)) {
    r[0]++;
    const prev = teamFirstSig.get(inv.team_id);
    if (!prev || t < prev) teamFirstSig.set(inv.team_id, t);
  }
  sigByMonth.set(m, r);
}
const preLaunchSigned = invoices.filter((i) => bool(i.completion_signature) && (ts(i.job_completed_at) || ts(i.created_at)) < SIG_LAUNCH).length;
table(['Job month', 'Invoices', 'Signed', 'Share signed'],
  [...sigByMonth.entries()].sort((a, b) => a[0] - b[0]).map(([m, [s, n]]) => [`${m} (${monthLabel(m)})`, n, s, pct(s, n)]));
p(`Signed invoices dated before Dec 2025 (should be 0): **${preLaunchSigned}**.`);
const adoptByMonth = new Map();
for (const t of teamFirstSig.values()) adoptByMonth.set(month(t), (adoptByMonth.get(month(t)) || 0) + 1);
let cum = 0;
table(['Month', 'Teams starting to use signature', 'Cumulative teams'],
  [...adoptByMonth.entries()].sort((a, b) => a[0] - b[0]).map(([m, n]) => { cum += n; return [`${m} (${monthLabel(m)})`, n, `${cum} of ${teams.length || '?'}`]; }));
// Signature vs win rate, only where the team had adopted it at the time of the job.
const sigWin = { adoptedSigned: [0, 0], adoptedUnsigned: [0, 0], notAvailable: [0, 0] };
for (const d of disputes) {
  if (!resolved(d.outcome)) continue;
  const invId = d.invoice_id || payById.get(d.payment_id)?.invoice_id;
  const inv = invById.get(invId); if (!inv) continue;
  const t = ts(inv.job_completed_at) || ts(inv.created_at);
  const first = teamFirstSig.get(inv.team_id);
  const w = isWin(d.outcome) ? 1 : 0;
  const k = !first || t < first ? 'notAvailable' : bool(inv.completion_signature) ? 'adoptedSigned' : 'adoptedUnsigned';
  sigWin[k][0] += w; sigWin[k][1]++;
}
table(['Resolved disputes', 'Win rate', 'n'], [
  ['team had adopted, job signed', pct(...sigWin.adoptedSigned), sigWin.adoptedSigned[1]],
  ['team had adopted, job NOT signed', pct(...sigWin.adoptedUnsigned), sigWin.adoptedUnsigned[1]],
  ['signature not available to the team yet', pct(...sigWin.notAvailable), sigWin.notAvailable[1]],
]);
p('Read: before a team adopts it, "no signature" is not a paperwork gap and must not be scored as one. Compare signed vs unsigned only '
  + 'among adopted teams. Adopting teams choose to, so they may simply be more careful: treat the signature effect as an upper bound.');

// ================================================================== 4. invoice status, June 2026, backfilled
h('4. Invoice status (from June 2026, backfilled for older invoices)');
const statusCounts = new Map();
const statusVsDispute = new Map();
const disputedInvoices = new Set(disputes.map((d) => d.invoice_id || payById.get(d.payment_id)?.invoice_id));
for (const inv of invoices) {
  const s = inv.invoice_status || '(blank)';
  statusCounts.set(s, (statusCounts.get(s) || 0) + 1);
  const k = `${s}|${disputedInvoices.has(inv.invoice_id) ? 'has dispute' : 'no dispute'}`;
  statusVsDispute.set(k, (statusVsDispute.get(k) || 0) + 1);
}
table(['invoice_status', 'Invoices', 'Share'], [...statusCounts.entries()].map(([s, n]) => [s, n, pct(n, invoices.length)]));
table(['invoice_status', 'Has a dispute record', 'No dispute record'], [...statusCounts.keys()].map((s) =>
  [s, statusVsDispute.get(`${s}|has dispute`) || 0, statusVsDispute.get(`${s}|no dispute`) || 0]));
const updByMonth = new Map();
let updBeforeCreate = 0, updJune = 0;
const JUNE_START = Date.UTC(2026, 5, 1), JULY_START = Date.UTC(2026, 6, 1);
for (const inv of invoices) {
  const u = ts(inv.updated_at); if (!u) continue;
  updByMonth.set(month(u), (updByMonth.get(month(u)) || 0) + 1);
  if (u < (ts(inv.created_at) || 0)) updBeforeCreate++;
  if (u >= JUNE_START && u < JULY_START && (ts(inv.created_at) || 0) < JUNE_START) updJune++;
}
table(['updated_at month', 'Invoices'], [...updByMonth.entries()].sort((a, b) => a[0] - b[0]).map(([m, n]) => [`${m} (${monthLabel(m)})`, n]));
p(`Invoices created before June 2026 but last updated in June 2026 (backfill signature): **${updJune}** (${pct(updJune, invoices.length)}). `
  + `updated_at earlier than created_at: ${updBeforeCreate}.`);
p('Read: `invoice_status = disputed` is the label itself and is set after the fact, so it must never be a model input; uploaded '
  + 'months 23–24 invoices are as at payment, where it cannot say "disputed". If updated_at spikes in June 2026, it was touched by the '
  + 'backfill and cannot be used to detect evidence edited after a dispute.');

// ================================================================== write
fs.mkdirSync(path.dirname(reportFile), { recursive: true });
fs.writeFileSync(reportFile, out.join('\n') + '\n');
console.log(out.join('\n'));
console.log(`\nReport written to ${path.resolve(reportFile)}`);
