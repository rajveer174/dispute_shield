// Candidate rules for each moment. Each rule only uses facts known at that moment.
import { bool, num, ts } from './lib.mjs';

const inv = (j) => j.inv;
const pay = (j) => j.pay;
const has = (j, tok) => j.evidence.includes(tok);
const realEvidence = (j) => j.evidence.filter((e) => e !== 'none' && e !== 'photos_added_after_dispute');

// ---------------------------------------------------------------- invoice ready (before payment)
const invoiceRules = [
  { id: 'signed_estimate', test: (j) => bool(inv(j).signed_estimate) },
  { id: 'terms_accepted', test: (j) => bool(inv(j).terms_accepted) },
  { id: 'agreement: estimate OR terms', test: (j) => bool(inv(j).signed_estimate) || bool(inv(j).terms_accepted) },
  { id: 'agreement: estimate AND terms', test: (j) => bool(inv(j).signed_estimate) && bool(inv(j).terms_accepted) },
  { id: 'address', test: (j) => bool(inv(j).customer_address_present) },
  { id: 'identity: address AND email', test: (j) => bool(inv(j).customer_address_present) && bool(inv(j).customer_email_present) },
  { id: 'gps_checkin', test: (j) => bool(inv(j).tech_gps_checkin) },
  { id: 'any photo', test: (j) => j.photosBefore + j.photosAfter > 0 },
  { id: 'before AND after photo', test: (j) => j.photosBefore > 0 && j.photosAfter > 0 },
  { id: 'presence: gps OR after photo', test: (j) => bool(inv(j).tech_gps_checkin) || j.photosAfter > 0 },
  { id: 'presence: gps OR any photo', test: (j) => bool(inv(j).tech_gps_checkin) || j.photosBefore + j.photosAfter > 0 },
  { id: 'description >= 80 chars', test: (j) => num(inv(j).job_description_chars) >= 80 },
  { id: 'detail: desc>=80 OR line items>=2', test: (j) => num(inv(j).job_description_chars) >= 80 || num(inv(j).line_item_count) >= 2 },
  { id: 'paperwork score >= 3 of 6', test: (j) => paperwork(j) >= 3 },
  { id: 'paperwork score >= 4 of 6', test: (j) => paperwork(j) >= 4 },
];

/** Six things a team controls on the invoice. */
export function paperwork(j) {
  const i = inv(j);
  return [bool(i.signed_estimate), bool(i.terms_accepted), bool(i.customer_address_present),
    bool(i.tech_gps_checkin), j.photosBefore + j.photosAfter > 0, num(i.job_description_chars) >= 80]
    .filter(Boolean).length;
}

// ---------------------------------------------------------------- payment collected
const cardPresent = (j) => ['tap', 'chip'].includes((pay(j).payment_method || '').toLowerCase());
const paymentRules = [
  ...invoiceRules,
  { id: 'card present (tap/chip)', test: cardPresent },
  { id: 'not keyed', test: (j) => (pay(j).payment_method || '').toLowerCase() !== 'keyed' },
  { id: 'receipt sent', test: (j) => bool(pay(j).receipt_sent) },
  { id: 'card checks: present OR avs+cvv match', test: (j) => cardPresent(j) || (pay(j).avs_result === 'match' && pay(j).cvv_result === 'match') },
  { id: 'card present OR receipt', test: (j) => cardPresent(j) || bool(pay(j).receipt_sent) },
  { id: 'paid after completion', test: (j) => ts(pay(j).collected_at) >= ts(inv(j).job_completed_at) },
];

// ---------------------------------------------------------------- dispute received
const tsOf = (s) => (s ? Date.parse(s.replace(' ', 'T')) : NaN);
// Days from payment to the dispute opening (NaN if the payment is not found, which fails the rule).
const openedDays = (j) => (tsOf(j.disp.opened_at) - tsOf(j.pay?.collected_at)) / 864e5;

const disputeRules = [
  { id: 'responded', test: (j) => bool(j.disp.merchant_responded) },
  { id: 'responded with evidence', test: (j) => bool(j.disp.merchant_responded) && realEvidence(j).length > 0 },
  { id: 'evidence >= 3 items', test: (j) => realEvidence(j).length >= 3 },
  { id: 'evidence >= 4 items', test: (j) => realEvidence(j).length >= 4 },
  { id: 'evidence >= 5 items', test: (j) => realEvidence(j).length >= 5 },
  { id: 'reason-matched evidence', test: (j, c) => (c.matchMap[j.disp.reason] || []).some((t) => has(j, t)) },
  { id: 'reason-matched evidence x2', test: (j, c) => (c.matchMap[j.disp.reason] || []).filter((t) => has(j, t)).length >= 2 },
  { id: 'not fraud', test: (j) => j.disp.reason !== 'fraud' },
  { id: 'no late photos', test: (j) => !has(j, 'photos_added_after_dispute') },
  { id: 'opened within 30 days of payment', test: (j) => openedDays(j) < 30 },
  { id: 'job photos submitted', test: (j) => has(j, 'job_photos') },
  { id: 'signed estimate submitted', test: (j) => has(j, 'signed_estimate') },
];

export const CANDIDATES = { 'invoice ready': invoiceRules, 'payment collected': paymentRules, 'dispute received': disputeRules };

/**
 * For each reason, the evidence tokens that raise the win rate by >= 10 points (with >= 20 cases each way).
 * Learned from the rows passed in (train months only).
 */
export function learnReasonEvidence(rows, minLift = 0.10, minN = 20) {
  const out = {};
  const byReason = new Map();
  for (const j of rows) {
    const r = j.disp.reason;
    if (!byReason.has(r)) byReason.set(r, []);
    byReason.get(r).push(j);
  }
  for (const [r, list] of byReason) {
    const toks = new Set(list.flatMap((j) => j.evidence));
    toks.delete('none'); toks.delete('photos_added_after_dispute');
    const good = [];
    for (const t of toks) {
      const yes = list.filter((j) => j.evidence.includes(t)), no = list.filter((j) => !j.evidence.includes(t));
      if (yes.length < minN || no.length < minN) continue;
      const lift = yes.reduce((s, j) => s + j.label, 0) / yes.length - no.reduce((s, j) => s + j.label, 0) / no.length;
      if (lift >= minLift) good.push([t, lift]);
    }
    out[r] = good.sort((a, b) => b[1] - a[1]).map(([t]) => t);
  }
  return out;
}

/**
 * Three yes/no rules -> 8 combinations. Each combination's win probability is its win rate in the
 * training rows, shrunk toward the overall rate so small combinations do not swing to 0% or 100%.
 */
export function lookupModel(rules, rows, ctx, k = 20) {
  const base = rows.reduce((s, j) => s + j.label, 0) / rows.length;
  const cells = new Map();
  const key = (j) => rules.map((r) => (r.test(j, ctx) ? 1 : 0)).join('');
  for (const j of rows) {
    const c = cells.get(key(j)) || { n: 0, w: 0 };
    c.n++; c.w += j.label; cells.set(key(j), c);
  }
  const raw = (kk) => { const c = cells.get(kk) || { n: 0, w: 0 }; return (c.w + k * base) / (c.n + k); };
  // Every rule is "good evidence", so meeting one more rule must never lower the probability:
  // a combination's probability is at least that of every combination it contains. Unseen combinations
  // only inherit from the ones they contain.
  const n = rules.length;
  const all = [...Array(1 << n).keys()].map((m) => m.toString(2).padStart(n, '0'));
  const subsetOf = (a, b) => [...a].every((ch, i) => ch === '0' || b[i] === '1');
  const table = new Map(all.map((kk) => {
    const seen = all.filter((s) => subsetOf(s, kk) && cells.has(s));
    return [kk, seen.length ? Math.max(...seen.map(raw)) : base];
  }));
  const prob = (kk) => table.get(kk);
  return { base, cells, key, prob, predict: (j) => prob(key(j)) };
}
