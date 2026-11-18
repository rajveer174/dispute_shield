// Generates SYNTHETIC files in the brief's schema, for testing the app end to end without the real data.
// The signal planted here is invented; it says nothing about the real TestPay data.
//
//   node tools/generate-sample-data.mjs [outDir] [jobsPerTeamPerMonth]
//
// Writes <outDir>/train/*.csv (months 1-22, with outcomes) and <outDir>/upload/*.csv (months 23-24:
// invoices/photos as at payment time, disputes without outcome/closed_at) plus upload/_truth_*.csv.
import fs from 'node:fs';
import path from 'node:path';

const outDir = process.argv[2] || 'sample-data';
const perMonth = Number(process.argv[3] || 6);

let seed = 12345;
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
const pick = (a) => a[Math.floor(rnd() * a.length)];
const bern = (p) => rnd() < p;
const sigmoid = (z) => 1 / (1 + Math.exp(-z));
const iso = (ms) => new Date(ms).toISOString().replace('.000Z', 'Z');
const HOUR = 3600e3, DAY = 24 * HOUR;

const professions = ['plumber', 'locksmith', 'hvac', 'electrician', 'garage_door', 'appliance_repair', 'pest_control', 'roofing'];
const jobTypes = { plumber: ['leak_repair', 'drain_clean', 'water_heater'], locksmith: ['lockout', 'rekey', 'lock_install'],
  hvac: ['ac_repair', 'furnace_service', 'install'], electrician: ['panel_upgrade', 'outlet_repair', 'lighting'],
  garage_door: ['spring_replace', 'opener_install'], appliance_repair: ['washer', 'fridge', 'oven'],
  pest_control: ['inspection', 'treatment'], roofing: ['leak_patch', 'reroof', 'inspection'] };
const priceBase = { plumber: 350, locksmith: 180, hvac: 650, electrician: 420, garage_door: 480, appliance_repair: 260, pest_control: 200, roofing: 1800 };
const states = ['CA', 'TX', 'FL', 'NY', 'AZ', 'GA', 'IL', 'WA', 'NC', 'OH'];
const reasons = ['fraud', 'service_not_provided', 'not_as_described', 'duplicate', 'credit_not_processed'];

const start = Date.UTC(2024, 10, 1); // Nov 2024 = month 1
const monthStart = (m) => Date.UTC(2024, 10 + m - 1, 1);
const teams = [];
for (let t = 1; t <= 200; t++) {
  const prof = pick(professions);
  teams.push({ team_id: `T${String(t).padStart(3, '0')}`, profession: prof, state: pick(states),
    onboarded_on: new Date(start - Math.floor(rnd() * 900) * DAY).toISOString().slice(0, 10),
    team_size: 1 + Math.floor(rnd() * 12), care: rnd(),
    // Release notes: completion signature from Dec 2025 (month 14), team by team over 3 months, optional.
    sigFrom: rnd() < 0.65 ? monthStart(14) + rnd() * 90 * DAY : Infinity });
}

const rows = { invoices: [], photos: [], payments: [], disputes: [] };
const fraudCards = Array.from({ length: 60 }, (_, i) => `fpX${i}`);
let inv = 0, pay = 0, ph = 0, dis = 0, cust = 0;

for (let m = 1; m <= 24; m++) {
  for (const team of teams) {
    const n = Math.max(0, Math.round(perMonth * (0.5 + rnd())));
    for (let k = 0; k < n; k++) {
      const care = team.care;
      const created = monthStart(m) + rnd() * 28 * DAY;
      const done = created + (2 + rnd() * 72) * HOUR;
      const amount = Math.round(priceBase[team.profession] * Math.exp((rnd() - 0.5) * 1.2) * 100) / 100;
      const I = {
        invoice_id: `I${++inv}`, team_id: team.team_id, customer_id: `C${bern(0.1) && cust > 10 ? Math.floor(rnd() * cust) : ++cust}`,
        created_at: iso(created), job_completed_at: iso(done), job_type: pick(jobTypes[team.profession]), amount_usd: amount,
        line_item_count: 1 + Math.floor(rnd() * 6), job_description_chars: Math.floor(rnd() * 200 * (0.3 + care)),
        customer_address_present: bern(0.55 + 0.4 * care), customer_email_present: bern(0.6 + 0.3 * care),
        customer_phone_present: bern(0.8), signed_estimate: bern(0.3 + 0.5 * care), terms_accepted: bern(0.5 + 0.3 * care),
        tech_gps_checkin: bern(0.4 + 0.5 * care), completion_signature: done >= team.sigFrom && bern(0.5 + 0.4 * care),
        photos_before_count: 0, photos_after_count: 0, invoice_status: 'paid', updated_at: null, month: m,
      };
      const method = bern(0.12) ? 'keyed' : pick(['tap', 'tap', 'chip', 'chip', 'ach', 'bnpl']);
      const collected = done + (rnd() < 0.1 ? -1 : 1) * rnd() * 48 * HOUR;
      const isFraudCard = method !== 'ach' && bern(0.01);
      const card = method === 'ach' ? '' : (isFraudCard ? pick(fraudCards) : `fp${pay + 1}`);
      const P = {
        payment_id: `P${++pay}`, invoice_id: I.invoice_id, team_id: team.team_id, customer_id: I.customer_id,
        collected_at: iso(collected), amount_usd: amount, tip_usd: bern(0.2) ? Math.round(amount * 0.1) : 0,
        payment_method: method, card_fingerprint: card, card_country: method === 'ach' ? '' : (bern(0.04) ? pick(['GB', 'CA', 'NG']) : 'US'),
        card_type: method === 'ach' ? '' : pick(['credit', 'debit', 'prepaid']),
        avs_result: method === 'keyed' ? pick(['match', 'match', 'partial', 'no_match']) : (method === 'ach' ? '' : 'not_applicable'),
        cvv_result: method === 'keyed' ? pick(['match', 'match', 'no_match']) : (method === 'ach' ? '' : 'not_applicable'),
        receipt_sent: bern(0.5 + 0.4 * care), month: m,
      };
      // Photos: some taken on the job, some uploaded late (after payment).
      const nBefore = bern(0.3 + 0.6 * care) ? 1 + Math.floor(rnd() * 3) : 0;
      const nAfter = bern(0.3 + 0.6 * care) ? 1 + Math.floor(rnd() * 3) : 0;
      const photoList = [];
      for (let b = 0; b < nBefore; b++) photoList.push({ kind: 'before', t: created + rnd() * (done - created) });
      for (let a = 0; a < nAfter; a++) photoList.push({ kind: 'after', t: bern(0.15) ? collected + rnd() * 10 * DAY : done + rnd() * HOUR });

      // Dispute risk.
      let z = -4.6 + (method === 'keyed' ? 1.3 : 0) + (P.avs_result === 'no_match' ? 1.0 : 0) + (P.card_country !== 'US' && P.card_country ? 0.8 : 0)
        + (I.customer_address_present ? 0 : 0.5) + (isFraudCard ? 2.2 : 0) + (P.receipt_sent ? -0.3 : 0) + (amount > 1000 ? 0.4 : 0) - 0.6 * care;
      const disputed = bern(sigmoid(z));
      let D = null;
      if (disputed) {
        const reason = method === 'keyed' || isFraudCard ? (bern(0.7) ? 'fraud' : pick(reasons)) : pick(reasons);
        const opened = collected + (5 + rnd() * 50) * DAY;
        const responded = bern(0.8);
        const hasAfter = photoList.some((p) => p.kind === 'after' && p.t <= collected);
        const evidence = [];
        if (responded) {
          evidence.push('invoice');
          if (hasAfter || bern(0.3)) evidence.push('photos');
          if (I.completion_signature) evidence.push('signature');
          if (I.tech_gps_checkin) evidence.push('gps_log');
          if (P.receipt_sent) evidence.push('receipt');
          if (I.terms_accepted) evidence.push('terms');
          if (I.signed_estimate) evidence.push('estimate');
          if (P.avs_result === 'match') evidence.push('avs_match');
          if (bern(0.4)) evidence.push('correspondence');
        }
        const ev = new Set(evidence);
        let w = -1.2 + (responded ? 1.0 : -3) + (I.customer_address_present ? 0.5 : 0) + 0.4 * ev.size / 3
          + (reason === 'fraud' && ev.has('avs_match') ? 0.9 : 0) + (reason === 'fraud' && I.tech_gps_checkin ? 0.6 : 0)
          + (reason === 'service_not_provided' && ev.has('photos') ? 1.0 : 0) + (reason === 'service_not_provided' && ev.has('gps_log') ? 0.8 : 0)
          + (reason === 'not_as_described' && ev.has('estimate') ? 1.0 : 0) + (reason === 'credit_not_processed' && ev.has('terms') ? 0.8 : 0)
          + (reason === 'duplicate' ? 0.8 : 0) + 0.5 * care;
        const won = bern(sigmoid(w));
        const closed = opened + (20 + rnd() * 60) * DAY;
        const endTrain = monthStart(23);
        const outcome = closed > endTrain ? 'open' : (bern(0.05) ? 'withdrawn' : (won ? 'won' : 'lost'));
        D = { dispute_id: `D${++dis}`, payment_id: P.payment_id, invoice_id: I.invoice_id, team_id: team.team_id, opened_at: iso(opened),
          reason, amount_usd: amount, merchant_responded: responded, evidence_submitted: evidence.join(';'),
          outcome: m <= 22 ? outcome : (won ? 'won' : 'lost'), closed_at: outcome === 'open' ? '' : iso(closed), month: m, _opened: opened };
        // Teams sometimes add photos after the dispute opens (evidence added late).
        if (bern(0.3)) photoList.push({ kind: 'after', t: opened + rnd() * 5 * DAY });
        rows.disputes.push(D);
      }
      for (const p of photoList) rows.photos.push({ photo_id: `PH${++ph}`, invoice_id: I.invoice_id, kind: p.kind, uploaded_at: iso(p.t), _t: p.t, month: m, _collected: collected });
      I._collected = collected;
      I._photos = photoList;
      I.updated_at = iso(Math.max(collected + HOUR, ...photoList.map((p) => p.t)) + rnd() * DAY);
      rows.invoices.push(I);
      rows.payments.push(P);
    }
  }
}

const cols = {
  teams: ['team_id', 'profession', 'state', 'onboarded_on', 'team_size'],
  invoices: ['invoice_id', 'team_id', 'customer_id', 'created_at', 'job_completed_at', 'job_type', 'amount_usd', 'line_item_count',
    'job_description_chars', 'customer_address_present', 'customer_email_present', 'customer_phone_present', 'signed_estimate',
    'terms_accepted', 'tech_gps_checkin', 'completion_signature', 'photos_before_count', 'photos_after_count', 'invoice_status', 'updated_at'],
  photos: ['photo_id', 'invoice_id', 'kind', 'uploaded_at'],
  payments: ['payment_id', 'invoice_id', 'team_id', 'customer_id', 'collected_at', 'amount_usd', 'tip_usd', 'payment_method',
    'card_fingerprint', 'card_country', 'card_type', 'avs_result', 'cvv_result', 'receipt_sent'],
  disputes: ['dispute_id', 'payment_id', 'invoice_id', 'team_id', 'opened_at', 'reason', 'amount_usd', 'merchant_responded',
    'evidence_submitted', 'outcome', 'closed_at'],
};
const esc = (v) => { const s = v === null || v === undefined ? '' : String(v); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
const write = (dir, name, header, list) => {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), [header.join(','), ...list.map((r) => header.map((c) => esc(r[c])).join(','))].join('\n') + '\n');
};

const trainEnd = monthStart(23);
const trainDir = path.join(outDir, 'train'), upDir = path.join(outDir, 'upload');
// Training snapshot (end of August 2026): photo counts include late photos, disputes opened before the end only.
const snapshotInvoices = rows.invoices.filter((i) => i.month <= 22).map((i) => {
  const ph = i._photos.filter((p) => p.t <= trainEnd);
  return { ...i, photos_before_count: ph.filter((p) => p.kind === 'before').length, photos_after_count: ph.filter((p) => p.kind === 'after').length };
});
write(trainDir, 'teams.csv', cols.teams, teams);
write(trainDir, 'invoices.csv', cols.invoices, snapshotInvoices);
write(trainDir, 'photos.csv', cols.photos, rows.photos.filter((p) => p.month <= 22 && p._t <= trainEnd));
write(trainDir, 'payments.csv', cols.payments, rows.payments.filter((p) => p.month <= 22));
write(trainDir, 'disputes.csv', cols.disputes, rows.disputes.filter((d) => d.month <= 22 && d._opened <= trainEnd));
fs.writeFileSync(path.join(trainDir, 'release_notes.md'), '# Release notes (synthetic)\n\nNo product changes in this synthetic data.\n');

// Upload months 23-24: invoices and photos as at payment time; disputes without outcomes.
const upInvoices = rows.invoices.filter((i) => i.month >= 23).map((i) => {
  const ph = i._photos.filter((p) => p.t <= i._collected);
  return { ...i, photos_before_count: ph.filter((p) => p.kind === 'before').length, photos_after_count: ph.filter((p) => p.kind === 'after').length, updated_at: iso(i._collected) };
});
write(upDir, 'invoices.csv', cols.invoices, upInvoices);
write(upDir, 'photos.csv', cols.photos, rows.photos.filter((p) => p.month >= 23 && p._t <= p._collected));
write(upDir, 'payments.csv', cols.payments, rows.payments.filter((p) => p.month >= 23));
const upDisputes = rows.disputes.filter((d) => d.month >= 23);
write(upDir, 'disputes.csv', cols.disputes.filter((c) => c !== 'outcome' && c !== 'closed_at'), upDisputes);
write(upDir, '_truth_disputes.csv', ['dispute_id', 'payment_id', 'outcome'], upDisputes);

console.log(`teams=${teams.length} invoices=${rows.invoices.length} payments=${rows.payments.length} disputes=${rows.disputes.length} photos=${rows.photos.length}`);
console.log(`written to ${path.resolve(outDir)}`);
