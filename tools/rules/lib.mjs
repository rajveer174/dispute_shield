// Shared loading for the win-rule scripts. Reads the training CSVs and builds one record per job with the
// facts known at each moment (invoice ready, payment collected, dispute received), using the same timing
// rules as the app: photo counts as of payment time, completion signature only where the team had adopted it.
import fs from 'node:fs';
import path from 'node:path';

export function readCsv(file) {
  const text = fs.readFileSync(file, 'utf8');
  const rows = [];
  let field = '', row = [], inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += ch;
    } else if (ch === '"') inQuotes = true;
    else if (ch === ',') { row.push(field); field = ''; }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else field += ch;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  const header = rows.shift().map((h) => h.trim());
  return rows.map((r) => Object.fromEntries(header.map((h, i) => [h, (r[i] ?? '').trim()])));
}

export const bool = (v) => ['true', '1', 'yes', 'y', 't'].includes(String(v).toLowerCase());
export const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : 0; };
export const ts = (v) => { const t = Date.parse(v); return Number.isFinite(t) ? t : null; };
/** Month 1 = November 2024 (same as the app). */
export const monthIndex = (t) => { const d = new Date(t); return (d.getUTCFullYear() - 2024) * 12 + d.getUTCMonth() + 1 - 10; };
export const SIGNATURE_LAUNCH = Date.parse('2025-12-01T00:00:00Z');

/** 1 = won (withdrawn counts as won, as in the app), 0 = lost, null = still open / unknown. */
export const winLabel = (outcome) => {
  const o = (outcome || '').toLowerCase();
  if (o === 'won' || o === 'withdrawn') return 1;
  if (o === 'lost') return 0;
  return null;
};

export function evidenceList(s) {
  return (s || '').split(/[;|]/).map((x) => x.trim().toLowerCase()).filter(Boolean);
}

export function load(dataDir) {
  const t0 = Date.now();
  const f = (n) => path.join(dataDir, n);
  const invoices = readCsv(f('invoices.csv'));
  const payments = readCsv(f('payments.csv'));
  const photos = readCsv(f('photos.csv'));
  const disputes = readCsv(f('disputes.csv'));
  const teams = readCsv(f('teams.csv'));

  const invById = new Map(invoices.map((i) => [i.invoice_id, i]));
  const payById = new Map(payments.map((p) => [p.payment_id, p]));
  // Earliest payment per invoice: the "invoice ready" moment sees photos up to then.
  const firstPayAt = new Map();
  for (const p of payments) {
    const t = ts(p.collected_at);
    if (t == null) continue;
    const prev = firstPayAt.get(p.invoice_id);
    if (prev == null || t < prev) firstPayAt.set(p.invoice_id, t);
  }

  // Photos per invoice with timestamps, so counts can be taken "as of" a moment.
  const photosByInv = new Map();
  for (const ph of photos) {
    let a = photosByInv.get(ph.invoice_id);
    if (!a) photosByInv.set(ph.invoice_id, (a = []));
    a.push({ kind: (ph.kind || '').toLowerCase(), at: ts(ph.uploaded_at) });
  }

  // Signature adoption: each team's first signed job.
  const firstSigned = new Map();
  for (const i of invoices) {
    if (!bool(i.completion_signature)) continue;
    const t = ts(i.job_completed_at) ?? ts(i.created_at);
    if (t == null) continue;
    const prev = firstSigned.get(i.team_id);
    if (prev == null || t < prev) firstSigned.set(i.team_id, t);
  }

  const EMPTY_INV = (id, teamId) => ({ invoice_id: id, team_id: teamId });
  // One record = one moment's view of a job. Photos count only if uploaded by asOf (null = all photos).
  const job = (inv, pay, disp, asOf) => {
    let before = 0, after = 0;
    for (const ph of photosByInv.get(inv.invoice_id) || []) {
      if (asOf != null && ph.at != null && ph.at > asOf) continue;
      if (ph.kind.startsWith('before')) before++;
      else if (ph.kind.startsWith('after')) after++;
    }
    const jobT = ts(inv.job_completed_at) ?? ts(inv.created_at);
    const signed = bool(inv.completion_signature);
    const adopted = firstSigned.get(inv.team_id);
    const sigAvailable = signed || (jobT != null && jobT >= SIGNATURE_LAUNCH && adopted != null && adopted < jobT);
    return {
      inv, pay, disp,
      photosBefore: before, photosAfter: after,
      sigAvailable, signed,
      label: disp ? winLabel(disp.outcome) : null,
      disputeMonth: disp ? monthIndex(ts(disp.opened_at)) : null,
      evidence: disp ? evidenceList(disp.evidence_submitted) : [],
    };
  };

  const invoiceJobs = invoices.map((inv) => job(inv, null, null, firstPayAt.get(inv.invoice_id) ?? null));
  const paymentJobs = payments.map((p) =>
    job(invById.get(p.invoice_id) || EMPTY_INV(p.invoice_id, p.team_id), p, null, ts(p.collected_at)));
  // Disputes: every row, joined to its payment and invoice. These are also the training rows.
  const disputeJobs = disputes.map((d) => {
    const p = payById.get(d.payment_id) || { payment_id: d.payment_id, invoice_id: d.invoice_id, team_id: d.team_id };
    const inv = invById.get(p.invoice_id || d.invoice_id) || EMPTY_INV(d.invoice_id, d.team_id);
    return job(inv, p, d, ts(p.collected_at));
  });
  const missing = {
    paymentsWithoutInvoice: payments.filter((p) => !invById.has(p.invoice_id)).length,
    disputesWithoutPayment: disputes.filter((d) => !payById.has(d.payment_id)).length,
  };
  return { invoiceJobs, paymentJobs, disputeJobs, jobs: disputeJobs, missing, ms: Date.now() - t0 };
}

/** Rank-based AUC. */
export function auc(p, y) {
  const idx = p.map((_, i) => i).sort((a, b) => p[a] - p[b]);
  let rankSum = 0, nPos = 0, i = 0;
  while (i < idx.length) {
    let j = i;
    while (j + 1 < idx.length && p[idx[j + 1]] === p[idx[i]]) j++;
    const avgRank = (i + j) / 2 + 1;
    for (let k = i; k <= j; k++) if (y[idx[k]] === 1) { rankSum += avgRank; nPos++; }
    i = j + 1;
  }
  const nNeg = y.length - nPos;
  return nPos && nNeg ? (rankSum - nPos * (nPos + 1) / 2) / (nPos * nNeg) : NaN;
}

export const pct = (v) => (v == null || Number.isNaN(v) ? '  –  ' : `${(v * 100).toFixed(1)}%`);
