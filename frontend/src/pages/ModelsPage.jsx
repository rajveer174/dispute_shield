import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Card, ErrorBox } from '../components.jsx';
import { label, num, pct } from '../format.js';

// The three moments in a job's life, in the order they happen. "Payment collected" holds two predictions.
const MOMENTS = [
  {
    step: '①',
    title: 'Invoice ready',
    rulesKey: 'invoice_ready',
    when: 'The invoice is done but the customer has not paid yet.',
    knows: 'The invoice (amount, line items, description, customer contact details, estimate, terms, GPS check-in, '
      + 'signature) and photos taken so far, plus the team (profession, state, size, time on TestPay).',
    predictions: [
      {
        key: 'invoice_win',
        question: 'If this job is disputed later, how likely is the team to win?',
        up: 'Helps the team win', down: 'Hurts the team\'s chance', upTone: 'good',
      },
    ],
  },
  {
    step: '②',
    title: 'Payment collected',
    rulesKey: 'payment_collected',
    when: 'The customer has paid.',
    knows: 'Everything from the invoice, plus the payment: how the card was taken (tapped, chip or keyed in), '
      + 'address and security code checks, card country and type, tip, receipt, timing, and earlier disputes on the same '
      + 'card or customer.',
    predictions: [
      {
        key: 'dispute_risk',
        question: 'How likely is this payment to be disputed?',
        up: 'Makes a dispute more likely', down: 'Makes a dispute less likely', upTone: 'bad',
        risk: true,
      },
      {
        key: 'payment_win',
        question: 'If it is disputed, how likely is the team to win?',
        up: 'Helps the team win', down: 'Hurts the team\'s chance', upTone: 'good',
      },
    ],
  },
  {
    step: '③',
    title: 'Dispute received',
    rulesKey: 'dispute_received',
    when: 'The card network has told TestPay the customer disputed the payment.',
    knows: 'Everything above, plus the dispute: the reason, whether the team responded, the evidence submitted, how long '
      + 'after payment it arrived and how much is disputed.',
    predictions: [
      {
        key: 'dispute_win',
        question: 'With the evidence submitted, how likely is the team to win this dispute?',
        up: 'Helps the team win', down: 'Hurts the team\'s chance', upTone: 'good',
      },
    ],
  },
];

const CHOSEN = 'logistic_l2';

/** Plain names for the approaches compared during validation. */
function approachName(a) {
  if (a === CHOSEN) return 'Weighted score of all factors';
  if (a === 'naive_bayes') return 'Each factor counted on its own';
  if (a.startsWith('baseline: rate by ')) return `Simple average by ${a.slice('baseline: rate by '.length)}`;
  return label(a);
}

function approachHint(a) {
  if (a === CHOSEN) return 'Used in the app. Weighs every factor together and shows why each payment scored the way it did.';
  if (a === 'naive_bayes') return 'Treats each factor as if it were unrelated to the others. Usually overconfident.';
  if (a.startsWith('baseline:')) return 'Benchmark: uses the historical rate for one field only.';
  return '';
}

export default function ModelsPage() {
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => { api.models().then(setD).catch(setError); }, []);
  if (error) return <div className="page"><ErrorBox error={error} /></div>;
  if (!d) return <div className="page">Loading…</div>;

  return (
    <div className="page">
      <h1>Models & validation</h1>
      <p className="lead">
        The app makes a prediction at each of three moments in a job, using only what is known at that moment.
        To test each one fairly, it was trained on earlier months and checked against the last {d.assumptions.validation_months} months
        it had not seen, the same way it is used on new uploads. A prediction that does not clear the bar
        (AUC {num(d.minAucToPublish, 2)}) is not shown anywhere in the app or the exports.
        Invoice and payment win chances (and dollars at risk) use the <b>rules</b> for those moments. For a received
        dispute, the win chance and the fight-or-accept decision use the <b>full model</b>, which judges each dispute on all
        its facts; the dispute rules are shown to explain it. The chance of a dispute comes from its model.
      </p>

      {MOMENTS.map((m) => (
        <Card key={m.title} title={`${m.step} ${m.title}`} className="moment">
          <dl className="kv moment-kv">
            <dt>When</dt><dd>{m.when}</dd>
            <dt>What it knows</dt><dd>{m.knows}</dd>
          </dl>
          {m.predictions.map((p) => <Prediction key={p.key} p={p} d={d} />)}
          <RuleSummary table={d.winRules?.[m.rulesKey]} defs={d.winRuleDefinitions?.[m.rulesKey]} explainOnly={m.rulesKey === 'dispute_received'} />
        </Card>
      ))}

      <Card title="Data findings">
        <table><tbody>
          {Object.entries(d.dataFindings).map(([k, v]) => (
            <tr key={k}><td>{label(k)}</td><td>{typeof v === 'object' ? Object.entries(v).map(([a, b]) => `${a}: ${b}`).join(' · ') : typeof v === 'number' && v < 1 && v > 0 ? pct(v, 1) : String(v)}</td></tr>
          ))}
        </tbody></table>
      </Card>
      <Card title="Assumptions">
        <table><tbody>{Object.entries(d.assumptions).map(([k, v]) => <tr key={k}><td>{label(k)}</td><td>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</td></tr>)}</tbody></table>
      </Card>
    </div>
  );
}

function Prediction({ p, d }) {
  const reports = d.validation[p.key] || [];
  const chosen = reports.find((r) => r.approach === (d.chosenApproach[p.key] || CHOSEN));
  const baseline = reports.find((r) => r.approach.startsWith('baseline:'));
  const published = d.published[p.key];
  const drivers = d.drivers?.[p.key] || { raises: [], lowers: [] };

  return (
    <div className="prediction">
      <div className="prediction-head">
        <h3 className="prediction-q">{p.question}</h3>
        {p.key !== 'dispute_risk' && p.key !== 'dispute_win' && d.winRules && Object.keys(d.winRules).length
          ? <span className="badge conf-medium" title="Win chances in the app come from the rules below">for comparison: the app uses the rules</span>
          : <span className={`badge ${published ? 'conf-high' : 'conf-low'}`}>{published ? 'shown in the app' : 'not shown: not reliable enough'}</span>}
      </div>

      <Trust chosen={chosen} baseline={baseline} minAuc={d.minAucToPublish} />
      {p.risk && d.tierThresholds && (
        <p className="small muted">
          Risk tiers: <b>high</b> from {pct(d.tierThresholds.high, 1)} chance of a dispute, <b>medium</b> from {pct(d.tierThresholds.medium, 1)}, otherwise <b>low</b>.
        </p>
      )}

      <div className="grid two drivers">
        <DriverList title={p.up} items={drivers.raises} tone={p.upTone} />
        <DriverList title={p.down} items={drivers.lowers} tone={p.upTone === 'good' ? 'bad' : 'good'} />
      </div>
      <p className="small muted">
        Strength is how much the factor moves the prediction compared with an average job. For numbers (amounts, counts,
        times) it is for a typical step up.
      </p>

      {d.notes[p.key] && <p className="small muted">{d.notes[p.key]}</p>}

      <details className="validation">
        <summary>How it was tested</summary>
        {reports.length ? (
          <>
            <table>
              <thead><tr>
                <th>Approach</th>
                <th className="r" title="Payments or disputes in the held-back months">Checked on</th>
                <th className="r" title="Chance it ranks a real case above a non-case. 0.5 is a coin flip, 1 is perfect.">Ranking (AUC)</th>
                <th className="r" title="Average gap between predicted chance and what happened, in points">Calibration gap</th>
                <th className="r" title="Squared error of the predicted chance. Lower is better.">Brier</th>
              </tr></thead>
              <tbody>
                {reports.map((r) => (
                  <tr key={r.approach} className={r === chosen ? 'selected' : ''} title={approachHint(r.approach)}>
                    <td>{approachName(r.approach)}{r === chosen && <span className="badge small conf-high">used</span>}</td>
                    <td className="r">{num(r.rows)}</td>
                    <td className="r">{num(r.auc, 3)}</td>
                    <td className="r">{num(r.ece * 100, 1)} pts</td>
                    <td className="r">{num(r.brier, 4)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {chosen && <Calibration bins={chosen.calibration} />}
          </>
        ) : <p className="muted">Not enough months to hold some back for testing.</p>}
      </details>
    </div>
  );
}

function Trust({ chosen, baseline, minAuc }) {
  if (!chosen) return <p className="trust muted">Not tested: not enough months of data to hold some back.</p>;
  const auc = chosen.auc;
  const rank = auc < minAuc ? { tone: 'bad', text: 'Weak: it barely separates likely cases from unlikely ones, so the app does not show it.' }
    : auc < 0.65 ? { tone: 'warn', text: 'Modest: better than guessing, but use it as a rough guide.' }
      : auc < 0.75 ? { tone: 'warn', text: 'Fair: it usually ranks likely cases above unlikely ones.' }
        : { tone: 'good', text: 'Good: it reliably ranks likely cases above unlikely ones.' };
  const gap = chosen.ece * 100;
  const calib = gap <= 3 ? `The percentages are close to what happened (off by ${num(gap, 1)} points on average).`
    : gap <= 7 ? `The percentages are roughly right (off by ${num(gap, 1)} points on average).`
      : `Treat the exact percentages with care: on average they were off by ${num(gap, 1)} points.`;
  let vs = '';
  if (baseline && !Number.isNaN(baseline.auc)) {
    const lift = (auc - baseline.auc) * 100;
    const what = baseline.approach.replace('baseline: rate by ', '');
    vs = lift > 1 ? ` That beats a simple average by ${what} (${num(baseline.auc, 3)}).`
      : ` It does no better than a simple average by ${what} (${num(baseline.auc, 3)}).`;
  }
  return (
    <div className={`trust trust-${rank.tone}`}>
      <b>How far to trust it:</b> {rank.text} {calib}{vs}
      <span className="muted small"> Tested on {num(chosen.rows)} cases from months it had not seen (AUC {num(auc, 3)}).</span>
    </div>
  );
}

function DriverList({ title, items, tone }) {
  return (
    <div className="driver-list">
      <div className={`driver-title ${tone}-text`}>{title}</div>
      {items.length ? (
        <ul>
          {items.map((it) => (
            <li key={it.feature}>
              <span>{it.label}</span>
              <span className={`badge small strength-${it.strength}`}>{it.strength}</span>
            </li>
          ))}
        </ul>
      ) : <p className="muted small">No factor has a clear effect.</p>}
    </div>
  );
}

function Calibration({ bins }) {
  if (!bins?.length) return null;
  const max = Math.max(...bins.map((b) => Math.max(b.meanPredicted, b.observedRate)), 0.01);
  return (
    <div className="calib">
      <div className="muted small">Predicted chance (grey) against what actually happened (colour), from the lowest tenth of predictions to the highest</div>
      <div className="calib-bars">
        {bins.map((b, i) => (
          <div key={i} className="calib-col" title={`predicted ${pct(b.meanPredicted, 1)} · actual ${pct(b.observedRate, 1)} · ${b.count} cases`}>
            <span className="calib-pred" style={{ height: `${(b.meanPredicted / max) * 100}%` }} />
            <span className="calib-obs" style={{ height: `${(b.observedRate / max) * 100}%` }} />
          </div>
        ))}
      </div>
    </div>
  );
}

const okNum = (v) => typeof v === 'number' && !Number.isNaN(v);

/** The rules for this moment: each rule's effect and the probability for every yes/no combination. */
function RuleSummary({ table, defs, explainOnly }) {
  if (!table || !defs) return null;
  // Every combination, most rules met first; within that, in rule order.
  const ones = (k) => k.split('').filter((b) => b === '1').length;
  const order = Object.keys(table.probability).sort((a, b) => ones(b) - ones(a) || b.localeCompare(a));
  const words = ['', 'one', 'two', 'three', 'four', 'five', 'six'];
  return (
    <div className="prediction">
      <div className="prediction-head">
        <h3 className="prediction-q">The {words[defs.length] || defs.length} rules: {explainOnly ? 'a simple explanation of the win chance' : 'what the app uses for the win chance here'}</h3>
        {explainOnly
          ? <span className="badge conf-medium" title="The decision uses the full model above">shown as explanation</span>
          : <span className="badge conf-high">used in the app</span>}
      </div>
      <p className="small muted">
        Learned from {num(table.trainRows)} finished disputes (overall win rate {pct(table.baseRate)}).
        {okNum(table.testAuc) && <> On {num(table.testRows)} disputes from months it had not seen, ranking (AUC) {num(table.testAuc, 3)}.</>}
      </p>
      <table>
        <thead><tr><th>Rule</th><th className="r">Disputes meeting it</th><th className="r">Win if yes</th><th className="r">Win if no</th></tr></thead>
        <tbody>
          {table.stats.map((s, i) => (
            <tr key={i}><td>{i + 1}. {s.label}</td><td className="r">{pct(s.share)}</td>
              <td className="r good-text">{okNum(s.winIfYes) ? pct(s.winIfYes) : '–'}</td>
              <td className="r bad-text">{okNum(s.winIfNo) ? pct(s.winIfNo) : '–'}</td></tr>
          ))}
        </tbody>
      </table>
      <details className="validation">
        <summary>Win probability for each combination</summary>
        <table>
          <thead><tr>{defs.map((r, i) => <th key={r.id}>Rule {i + 1}</th>)}<th className="r">Past disputes</th><th className="r">Dispute win probability</th></tr></thead>
          <tbody>
            {order.map((k) => (
              <tr key={k}>
                {k.split('').map((b, i) => <td key={i}>{b === '1' ? '✓' : '✗'}</td>)}
                <td className="r">{table.counts[k] ? num(table.counts[k]) : <span className="muted" title="No past disputes had this combination">0</span>}</td>
                <td className="r">{pct(table.probability[k])}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="small muted">Rare combinations are pulled toward the overall rate, and meeting one more rule never lowers the probability.</p>
      </details>
    </div>
  );
}
