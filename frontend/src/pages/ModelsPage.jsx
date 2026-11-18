import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, ErrorBox } from '../components.jsx';
import { label, num, pct } from '../format.js';

// The three moments in a job's life, in the order they happen. Each has a rule checker that answers the
// merchant's question for that moment. Model drivers, test tables and rule combination tables are not shown here;
// the model comparison and every rejected approach are in experiments/.
const MOMENTS = [
  {
    step: '①',
    title: 'Invoice ready',
    rulesKey: 'invoice_ready',
    when: 'The invoice is done but the customer has not paid yet.',
    knows: 'The invoice (amount, line items, description, customer contact details, estimate, terms, GPS check-in, '
      + 'signature) and photos taken so far, plus the team (profession, state, size, time on TestPay).',
    // The invoice win chance comes from the three rules, so the model block is not shown.
    predictions: [],
    checker: {
      question: 'Is this invoice strong enough if it is ever disputed? What should be added now?',
      noun: 'invoice',
      lookupLabel: 'Invoice or payment ID',
      async fetch(sessionId, q) {
        const res = await api.payments(sessionId, { q, size: 20 });
        const hit = res.items.find((x) => x.invoiceId === q) || res.items.find((x) => x.paymentId === q) || res.items[0];
        return hit && { id: hit.invoiceId, rules: hit.invoiceRules };
      },
      done: 'Nothing to add from the three rules. Keep the signed estimate, address and photos ready to submit as evidence.',
      note: 'This is the chance to win if a dispute happens, not the chance of a dispute.',
    },
  },
  {
    step: '②',
    title: 'Payment collected',
    rulesKey: 'payment_collected',
    when: 'The customer has paid.',
    knows: 'Everything from the invoice, plus the payment: how the card was taken (tapped, chip or keyed in), '
      + 'address and security code checks, card country and type, tip, receipt, timing, and earlier disputes on the same '
      + 'card or customer.',
    // The chance of a dispute still comes from its model (no rules predict it); the payment win chance uses the rules.
    predictions: [
      { key: 'dispute_risk', question: 'How likely is this payment to be disputed?', risk: true },
    ],
    checker: {
      question: 'Is this payment protected well enough if it is ever disputed? What should be done now?',
      noun: 'payment',
      lookupLabel: 'Payment or invoice ID',
      async fetch(sessionId, q) {
        const res = await api.payments(sessionId, { q, size: 20 });
        const hit = res.items.find((x) => x.paymentId === q) || res.items.find((x) => x.invoiceId === q) || res.items[0];
        return hit && { id: hit.paymentId, rules: hit.paymentRules };
      },
      done: 'Nothing to add from the three rules. Keep the invoice paperwork and card checks ready to submit as evidence.',
      note: 'This is the chance to win if a dispute happens; the chance of a dispute is the separate prediction above.',
    },
  },
  {
    step: '③',
    title: 'Dispute received',
    rulesKey: 'dispute_received',
    when: 'The card network has told TestPay the customer disputed the payment.',
    knows: 'Everything above, plus the dispute: the reason, whether the team responded, the evidence submitted, how long '
      + 'after payment it arrived and how much is disputed.',
    predictions: [
      { key: 'dispute_win', question: 'With the evidence submitted, how likely is the team to win this dispute?' },
    ],
    checker: {
      question: 'How strong is this dispute with what we have? What should be added now?',
      noun: 'dispute',
      lookupLabel: 'Dispute or payment ID',
      async fetch(sessionId, q) {
        const res = await api.disputes(sessionId, { q, size: 20 });
        const hit = res.items.find((x) => x.disputeId === q) || res.items.find((x) => x.paymentId === q) || res.items[0];
        return hit && { id: hit.disputeId, rules: hit.disputeRules };
      },
      done: 'Nothing to add from the four rules: respond with the evidence before the deadline.',
      note: 'A quick estimate from the four rules. The fight-or-accept decision on the Disputes page uses the full model, '
        + 'which also weighs the amount, card checks and the team\'s record.',
    },
  },
];

const CHOSEN = 'logistic_l2';

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
          <RuleChecker table={d.winRules?.[m.rulesKey]} defs={d.winRuleDefinitions?.[m.rulesKey]} cfg={m.checker} />
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

/** A model the app uses directly: what it predicts and how far to trust it. */
function Prediction({ p, d }) {
  const reports = d.validation[p.key] || [];
  const chosen = reports.find((r) => r.approach === (d.chosenApproach[p.key] || CHOSEN));
  const baseline = reports.find((r) => r.approach.startsWith('baseline:'));
  const published = d.published[p.key];

  return (
    <div className="prediction">
      <div className="prediction-head">
        <h3 className="prediction-q">{p.question}</h3>
        <span className={`badge ${published ? 'conf-high' : 'conf-low'}`}>{published ? 'shown in the app' : 'not shown: not reliable enough'}</span>
      </div>
      <Trust chosen={chosen} baseline={baseline} minAuc={d.minAucToPublish} />
      {p.risk && d.tierThresholds && (
        <p className="small muted">
          Risk tiers: <b>high</b> from {pct(d.tierThresholds.high, 1)} chance of a dispute, <b>medium</b> from {pct(d.tierThresholds.medium, 1)}, otherwise <b>low</b>.
        </p>
      )}
      {d.notes[p.key] && <p className="small muted">{d.notes[p.key]}</p>}
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

const okNum = (v) => typeof v === 'number' && !Number.isNaN(v);
const WORDS = ['', 'one', 'two', 'three', 'four', 'five', 'six'];

/** The rules for this moment and each rule's effect on the win rate. */
function RuleSummary({ table, defs, explainOnly }) {
  if (!table || !defs) return null;
  return (
    <div className="prediction">
      <div className="prediction-head">
        <h3 className="prediction-q">The {WORDS[defs.length] || defs.length} rules: {explainOnly ? 'a simple explanation of the win chance' : 'what the app uses for the win chance here'}</h3>
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
    </div>
  );
}

/**
 * Answers the moment's question from its rules and their learned win-probability table: tick what is in place (or
 * load an uploaded record), press the button, and get a verdict plus the additions that raise the win chance most.
 * Rules without an action (such as the dispute reason) cannot be changed and are listed separately.
 */
function RuleChecker({ table, defs, cfg }) {
  const { sessionId } = useApp() || {};
  const [met, setMet] = useState(() => (defs || []).map(() => false));
  const [shown, setShown] = useState(false);
  const [lookup, setLookup] = useState('');
  const [loaded, setLoaded] = useState(null);
  const [error, setError] = useState(null);
  if (!table || !defs || !cfg) return null;

  const key = (m) => m.map((b) => (b ? '1' : '0')).join('');
  const prob = (m) => table.probability[key(m)] ?? table.baseRate;
  const p = prob(met);
  const best = prob(defs.map(() => true));
  const toggle = (i) => { setMet(met.map((b, j) => (j === i ? !b : b))); setLoaded(null); };

  const load = async (e) => {
    e.preventDefault();
    setError(null);
    const q = lookup.trim();
    if (!q) return;
    try {
      const hit = await cfg.fetch(sessionId, q);
      if (!hit || !hit.rules) throw new Error(`No uploaded ${cfg.noun} matches "${q}".`);
      setMet(defs.map((r) => !!hit.rules.rules.find((x) => x.id === r.id)?.met));
      setLoaded(hit.id);
      setShown(true);
    } catch (err) {
      setError(err.message || String(err));
    }
  };

  // Verdict bands from the table itself: the best combination, above the overall rate, or at/below it.
  const verdict = p >= best - 0.005 ? 'strong' : p > table.baseRate + 0.02 ? 'fair' : 'weak';
  const VERDICT = {
    strong: { label: 'Strong', text: `This ${cfg.noun} meets every rule: the best position these rules can give.` },
    fair: { label: 'Fair', text: `Better than an average disputed ${cfg.noun}, but there is still room to strengthen it.` },
    weak: { label: 'Weak', text: `If disputed, this ${cfg.noun} starts at or below the average win rate.` },
  };
  const missing = defs.map((r, i) => ({ r, i })).filter(({ i }) => !met[i]);
  // Each missing rule the team can act on, on its own, best first; then all of them together.
  const adds = missing.filter(({ r }) => r.action).map(({ r, i }) => {
    const m = met.map((b, j) => (j === i ? true : b));
    return { r, p: prob(m), gain: prob(m) - p };
  }).sort((a, b) => b.gain - a.gain);
  const fixed = missing.filter(({ r }) => !r.action);
  const reachable = prob(met.map((b, i) => b || !!defs[i].action));

  return (
    <div className="prediction checker">
      <div className="prediction-head">
        <h3 className="prediction-q">{cfg.question}</h3>
        <span className="badge conf-high">uses the {WORDS[defs.length] || defs.length} rules</span>
      </div>
      <p className="small muted">Tick what applies to the {cfg.noun}{sessionId ? ', or load one you uploaded,' : ''} then check it.</p>

      {sessionId && (
        <form className="checker-lookup" onSubmit={load}>
          <input value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder={cfg.lookupLabel} aria-label={cfg.lookupLabel} />
          <button type="submit">Load {cfg.noun}</button>
          {loaded && <span className="small muted">Loaded <span className="mono">{loaded}</span></span>}
        </form>
      )}
      {error && <p className="small bad-text">{error}</p>}

      <ul className="checker-rules">
        {defs.map((r, i) => (
          <li key={r.id}>
            <label><input type="checkbox" checked={met[i]} onChange={() => toggle(i)} /> {r.label}</label>
          </li>
        ))}
      </ul>
      <button type="button" className="primary" onClick={() => setShown(true)}>Check this {cfg.noun}</button>

      {shown && (
        <div className={`checker-summary ${verdict}`} aria-live="polite">
          <div className="checker-verdict">
            <span className={`badge verdict-${verdict}`}>{VERDICT[verdict].label}</span>
            <b> {pct(p)} chance to win</b> if disputed
            <span className="muted small"> · {met.filter(Boolean).length}/{defs.length} rules met · based on {num(table.counts[key(met)] || 0)} past disputes like it</span>
          </div>
          <p>{VERDICT[verdict].text}</p>
          {adds.length > 0 ? (
            <>
              <b>Add now</b>
              <ol className="checker-adds">
                {adds.map(({ r, p: np, gain }) => (
                  <li key={r.id}>
                    {r.action}
                    <span className="muted"> → {pct(np)}</span>{' '}
                    {gain > 0.005 ? <span className="good-text">(+{num(gain * 100)} pts)</span> : <span className="muted small">(no change on its own)</span>}
                  </li>
                ))}
              </ol>
              {adds.length > 1 && <p className="small">With all of them: <b>{pct(reachable)}</b> (+{num((reachable - p) * 100)} pts).</p>}
            </>
          ) : fixed.length === 0 && <p className="small">{cfg.done}</p>}
          {fixed.length > 0 && (
            <p className="small muted">
              Cannot be changed: {fixed.map(({ r }) => `“${r.label}” is not met`).join('; ')}.
              {verdict !== 'strong' && ` The best reachable is ${pct(reachable)}.`}
            </p>
          )}
          <p className="small muted">
            Win rates of past disputes with the same combination, learned from {num(table.trainRows)} disputes (overall {pct(table.baseRate)}).
            {' '}{cfg.note}
          </p>
        </div>
      )}
    </div>
  );
}
