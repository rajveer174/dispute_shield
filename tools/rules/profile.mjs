// Prints aggregate value counts and win rates only (no rows), to choose candidate rules.
// Usage: node tools/rules/profile.mjs data
import { load, pct } from './lib.mjs';

const { disputeJobs: jobs, ms } = load(process.argv[2] || 'data');
console.log(`loaded in ${ms} ms`);
const resolved = jobs.filter((j) => j.label != null);
const base = resolved.reduce((s, j) => s + j.label, 0) / resolved.length;
console.log(`resolved disputes: ${resolved.length}, overall win rate ${pct(base)}, months ${Math.min(...resolved.map((j) => j.disputeMonth))}-${Math.max(...resolved.map((j) => j.disputeMonth))}`);

function tab(name, keyFn, rows = resolved, min = 15) {
  const m = new Map();
  for (const j of rows) {
    const keys = [].concat(keyFn(j));
    for (const k of keys) {
      const e = m.get(k) || { n: 0, w: 0 };
      e.n++; e.w += j.label; m.set(k, e);
    }
  }
  const list = [...m.entries()].filter(([, e]) => e.n >= min).sort((a, b) => b[1].n - a[1].n);
  console.log(`\n== ${name}`);
  for (const [k, e] of list.slice(0, 25)) console.log(`  ${String(k).padEnd(28)} n=${String(e.n).padStart(5)}  win ${pct(e.w / e.n)}`);
}

const outcomes = {};
for (const d of jobs.map((j) => j.disp)) outcomes[d.outcome || '(blank)'] = (outcomes[d.outcome || '(blank)'] || 0) + 1;
console.log('outcomes', outcomes);

tab('reason', (j) => j.disp.reason);
tab('evidence token', (j) => (j.evidence.length ? j.evidence : ['(none listed)']));
tab('evidence count', (j) => Math.min(j.evidence.length, 6));
tab('merchant responded', (j) => j.disp.merchant_responded);
tab('payment method', (j) => j.pay.payment_method);
tab('avs', (j) => j.pay.avs_result);
tab('cvv', (j) => j.pay.cvv_result);
tab('card type', (j) => j.pay.card_type);
tab('card country US', (j) => (j.pay.card_country || '').toUpperCase() === 'US');
tab('receipt sent', (j) => j.pay.receipt_sent);
tab('address', (j) => j.inv.customer_address_present);
tab('email', (j) => j.inv.customer_email_present);
tab('phone', (j) => j.inv.customer_phone_present);
tab('signed estimate', (j) => j.inv.signed_estimate);
tab('terms accepted', (j) => j.inv.terms_accepted);
tab('gps checkin', (j) => j.inv.tech_gps_checkin);
tab('signature (available jobs only)', (j) => j.inv.completion_signature, resolved.filter((j) => j.sigAvailable));
tab('before photos as of payment', (j) => Math.min(j.photosBefore, 3));
tab('after photos as of payment', (j) => Math.min(j.photosAfter, 3));
tab('description chars bucket', (j) => { const c = +j.inv.job_description_chars; return c < 40 ? '<40' : c < 80 ? '40-79' : c < 160 ? '80-159' : '160+'; });
tab('line items', (j) => Math.min(+j.inv.line_item_count || 0, 5));
tab('paid before completion', (j) => Date.parse(j.pay.collected_at) < Date.parse(j.inv.job_completed_at));
tab('reason x evidence', (j) => j.evidence.map((e) => `${j.disp.reason}|${e}`), resolved, 25);
