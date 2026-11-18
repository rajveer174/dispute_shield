// Win rate by days from payment to dispute opening, and other dispute-moment candidates. Aggregates only.
import { load } from './lib.mjs';

const { disputeJobs } = load(process.argv[2] || 'data');
const t = (s) => (s ? Date.parse(s.replace(' ', 'T')) : null);
const rows = disputeJobs.filter((j) => j.label != null).map((j) => {
  const pay = t(j.pay?.collected_at), op = t(j.disp.opened_at);
  return {
    ...j,
    lag: pay != null && op != null ? (op - pay) / 864e5 : null,
    responded: /^(true|1|yes)$/i.test(j.disp.merchant_responded),
    amt: +j.disp.amount_usd,
  };
});
const rate = (rs) => (rs.length ? `${(100 * rs.filter((r) => r.label === 1).length / rs.length).toFixed(1)}% of ${rs.length}` : '-');

console.log('all', rate(rows), ' no lag', rows.filter((r) => r.lag == null).length);
const lags = rows.map((r) => r.lag).filter((x) => x != null).sort((a, b) => a - b);
console.log('lag days p10/p50/p90/p99/max',
  [0.1, 0.5, 0.9, 0.99, 1].map((q) => lags[Math.min(lags.length - 1, Math.floor(q * lags.length))].toFixed(0)).join(' / '));
for (const [lo, hi] of [[-1e9, 0], [0, 15], [15, 30], [30, 45], [45, 60], [60, 90], [90, 120], [120, 1e9]]) {
  const b = rows.filter((r) => r.lag != null && r.lag >= lo && r.lag < hi);
  console.log(`lag ${lo}-${hi}d`.padEnd(18), 'all', rate(b).padEnd(16), 'responded', rate(b.filter((r) => r.responded)));
}

console.log('\nby reason (responded only)');
for (const re of [...new Set(rows.map((r) => r.disp.reason))]) {
  console.log(' ', String(re).padEnd(26), rate(rows.filter((r) => r.responded && r.disp.reason === re)));
}
console.log('\nby amount (responded only)');
for (const [lo, hi] of [[0, 100], [100, 250], [250, 500], [500, 1000], [1000, 1e9]]) {
  console.log(`  $${lo}-${hi}`.padEnd(16), rate(rows.filter((r) => r.responded && r.amt >= lo && r.amt < hi)));
}
