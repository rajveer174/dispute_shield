// Release-note and leakage checks on the training data, plus what the trainer did about each.
// Usage: node experiments/02_leakage_checks/run.mjs data
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { ROOT, report, table, pct, writeResults } from '../lib.mjs';

const dataDir = process.argv[2] || 'data';
// The analyser also writes its own copy; send that to the temp folder so nothing lands in the repo.
const tmp = path.join(os.tmpdir(), 'release-notes-report.md');
const raw = execFileSync(process.execPath, [path.join(ROOT, 'tools', 'analyze-release-notes.mjs'), dataDir, tmp],
  { encoding: 'utf8', maxBuffer: 1 << 26 });
const checks = raw.split('\n')
  .filter((l) => !/^Data folder:|^Report written|^# Release-notes data checks/.test(l))
  .join('\n').replace(/^## /gm, '### ');

const f = report().dataFindings;
const summary = table(['Check', 'Finding', 'What the trainer does'], [
  ['Disputes arrive weeks after payment', `lag p50 ${f.payment_to_dispute_lag_days.p50} d, p95 ${f.payment_to_dispute_lag_days.p95} d`,
    `risk model drops payments from the last ${report().assumptions.censor_days} days (p95 lag)`],
  ['Photos added after payment', `${pct(f.photos_uploaded_after_payment_share)} of photos; ${f.photos_uploaded_after_dispute_opened} after the dispute opened`,
    'photo counts rebuilt from photos.csv as of payment time'],
  ['Completion signature (Dec 2025, team by team)', `${f.teams_using_completion_signature} teams adopted; not available for ${pct(f.jobs_where_signature_not_available_share)} of jobs`,
    '`signature_unavailable` feature; missing signature only counts as a gap after adoption'],
  ['Invoice status backfill (June 2026)', 'status "disputed" lines up with the dispute records: it is set after the fact',
    'invoice_status never used as an input (it is the label)'],
  ['updated_at rewritten by the backfill', (f.win_models_leakage_filter.match(/\d+ of \d+ resolved disputes[^.]*/) || [''])[0],
    '`leakage-mode: NONE`; late evidence handled via photo timestamps instead'],
]);

writeResults(import.meta.url, '02 · Leakage and release-note checks',
  `## Summary\n\n${summary}\n\n## Detailed checks (tools/analyze-release-notes.mjs)\n${checks}`);
