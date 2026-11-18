// Tests candidate rules per moment and searches for the best set of three.
// Rules are learned on dispute months <= CUT and tested on later months. Aggregates only.
// Usage: node tools/rules/search.mjs data
import { load, auc, pct } from './lib.mjs';
import { CANDIDATES, learnReasonEvidence, lookupModel } from './rules.mjs';

const { disputeJobs: jobs } = load(process.argv[2] || 'data');
const resolved = jobs.filter((j) => j.label != null);
const maxM = Math.max(...resolved.map((j) => j.disputeMonth));
const CUT = maxM - 4;
const train = resolved.filter((j) => j.disputeMonth <= CUT);
const test = resolved.filter((j) => j.disputeMonth > CUT);
console.log(`train: ${train.length} disputes (months <= ${CUT}), test: ${test.length} (months ${CUT + 1}-${maxM})`);

const matchMap = learnReasonEvidence(train);
console.log('\nEvidence that lifts the win rate for each reason (learned on train months):');
for (const [r, toks] of Object.entries(matchMap)) console.log(`  ${r.padEnd(22)} ${toks.join(', ') || '(none clear)'}`);
const ctx = { matchMap };

for (const [stage, cands] of Object.entries(CANDIDATES)) {
  console.log(`\n================ ${stage}`);
  console.log('  rule'.padEnd(46) + 'train: share  win if yes / no   | test: win if yes / no');
  const rows = [];
  for (const c of cands) {
    const s = (set) => {
      let ny = 0, wy = 0, nn = 0, wn = 0;
      for (const j of set) { if (c.test(j, ctx)) { ny++; wy += j.label; } else { nn++; wn += j.label; } }
      return { share: ny / set.length, yes: ny ? wy / ny : NaN, no: nn ? wn / nn : NaN };
    };
    const a = s(train), b = s(test);
    rows.push({ c, a, b });
    console.log(`  ${c.id.padEnd(44)} ${pct(a.share).padStart(6)}  ${pct(a.yes).padStart(6)} / ${pct(a.no).padStart(6)}   | ${pct(b.yes).padStart(6)} / ${pct(b.no).padStart(6)}`);
  }
  // Exhaustive search of 3-rule sets, ranked on TRAIN AUC (test AUC reported, never used to choose).
  const combos = [];
  for (let x = 0; x < cands.length; x++) for (let y = x + 1; y < cands.length; y++) for (let z = y + 1; z < cands.length; z++) {
    const set = [cands[x], cands[y], cands[z]];
    const m = lookupModel(set, train, ctx);
    const trAuc = auc(train.map((j) => m.predict(j)), train.map((j) => j.label));
    const teAuc = auc(test.map((j) => m.predict(j)), test.map((j) => j.label));
    combos.push({ ids: set.map((c) => c.id), trAuc, teAuc });
  }
  combos.sort((p, q) => q.trAuc - p.trAuc);
  console.log('\n  best 3-rule sets (ranked on train AUC):');
  for (const k of combos.slice(0, 8)) console.log(`   train ${k.trAuc.toFixed(3)}  test ${k.teAuc.toFixed(3)}   ${k.ids.join('  +  ')}`);
}
