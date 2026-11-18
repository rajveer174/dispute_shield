import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, Empty, ErrorBox, NeedsUpload, Pager, Prob, Rec, RuleBox, RuleProb } from '../components.jsx';
import { date, pct, usd } from '../format.js';

export default function DisputesPage() {
  const { sessionId, session, status } = useApp();
  const { disputeId } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const reason = params.get('reason') || '';
  const recommendation = params.get('recommendation') || '';
  const team = params.get('team') || '';
  const q = params.get('q') || '';
  const sort = params.get('sort') || 'amount';
  const page = Number(params.get('page') || 0);

  useEffect(() => {
    if (!sessionId) return;
    api.disputes(sessionId, { reason, recommendation, team, q, sort, page, size: 50 }).then(setData).catch(setError);
  }, [sessionId, reason, recommendation, team, q, sort, page, session?.disputesScored]);

  // A dispute opened by link may not be on the current page: look it up directly.
  const [lookup, setLookup] = useState(null);
  useEffect(() => {
    if (!sessionId || !disputeId || !data || data.items.some((d) => d.disputeId === disputeId)) return;
    api.disputes(sessionId, { q: disputeId, size: 500 })
      .then((r) => setLookup(r.items.find((d) => d.disputeId === disputeId) || null))
      .catch(setError);
  }, [sessionId, disputeId, data]);

  if (!sessionId) return <div className="page"><h1>Disputes</h1><NeedsUpload /></div>;
  if (!session?.disputesScored) return <div className="page"><h1>Disputes</h1><NeedsUpload step={2} /></div>;

  const set = (k, v) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    if (k !== 'page') next.delete('page');
    setParams(next);
  };
  const econ = status?.assumptions || {};
  const selected = data?.items.find((d) => d.disputeId === disputeId) || (lookup?.disputeId === disputeId ? lookup : null);

  return (
    <div className="page">
      <h1>Disputes</h1>
      <p className="lead">
        Can we win, what to submit, and is it worth it? The win chance comes from the full model, which weighs every
        fact of the dispute (evidence, card checks, team record, amount, reason). Fight when chance to win × (amount +
        ${econ.dispute_fee_usd ?? 25} fee) minus the ${econ.fight_cost_usd ?? 15} it costs to fight is at least
        ${econ.min_expected_gain_usd ?? 25}; otherwise accept.
      </p>
      <ErrorBox error={error} />
      <div className="filters">
        <select value={reason} onChange={(e) => set('reason', e.target.value)}>
          <option value="">All reasons</option>
          {(status?.reasons || []).map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
        </select>
        <select value={recommendation} onChange={(e) => set('recommendation', e.target.value)}>
          <option value="">Fight & accept</option>
          <option value="fight">Fight</option>
          <option value="accept">Accept</option>
        </select>
        <input placeholder="Team ID" value={team} onChange={(e) => set('team', e.target.value.trim())} />
        <input placeholder="Search dispute / payment" value={q} onChange={(e) => set('q', e.target.value)} />
        <select value={sort} onChange={(e) => set('sort', e.target.value)}>
          <option value="amount">Sort: amount</option>
          <option value="win">Sort: win chance</option>
          <option value="value">Sort: value of fighting</option>
        </select>
      </div>

      <div className={`split ${disputeId ? 'with-detail' : ''}`}>
        <Card className="table-card">
          {data && (data.items.length === 0 ? <Empty>No disputes match.</Empty> : (
            <>
              <table>
                <thead>
                  <tr><th>Dispute</th><th>Team</th><th>Reason</th><th className="r">Amount</th><th>Responded</th><th title="Win chance from the full model; x/4 is how many dispute rules are met">Win chance</th><th>Decision</th><th className="r" title="Expected gain of fighting over accepting">Expected gain</th></tr>
                </thead>
                <tbody>
                  {data.items.map((d) => (
                    <tr key={d.disputeId} className={d.disputeId === disputeId ? 'selected' : ''}
                        onClick={() => navigate(`/disputes/${encodeURIComponent(d.disputeId)}?${params}`)}>
                      <td className="mono">{d.disputeId}</td>
                      <td><Link to={`/teams/${d.teamId}`} onClick={(e) => e.stopPropagation()}>{d.teamId}</Link></td>
                      <td>{d.reasonLabel}</td>
                      <td className="r">{usd(d.amountUsd)}</td>
                      <td>{d.merchantResponded ? 'yes' : <span className="warn-text">not yet</span>}</td>
                      <td><RuleProb value={d.winProb} result={d.disputeRules} /></td>
                      <td><Rec value={d.recommendation} /></td>
                      <td className="r">{usd(d.expectedGainUsd, 2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} size={data.size} total={data.total} onPage={(p) => set('page', String(p))} />
            </>
          ))}
        </Card>
        {disputeId && selected && <DisputeDetail d={selected} onClose={() => navigate(`/disputes?${params}`)} />}
      </div>
    </div>
  );
}

function DisputeDetail({ d, onClose }) {
  return (
    <Card className="detail" title={<span className="mono">{d.disputeId}</span>} actions={<button onClick={onClose}>✕</button>}>
      <div className={`decision ${d.recommendation}`}>
        <div className="decision-label">{d.recommendation === 'fight' ? 'Fight this dispute' : 'Accept this dispute'}</div>
        <div>{d.rationale}</div>
      </div>
      <dl className="kv">
        <dt>Reason</dt><dd>{d.reasonLabel}</dd>
        <dt>Amount</dt><dd>{usd(d.amountUsd, 2)}</dd>
        <dt>Opened</dt><dd>{date(d.openedAt)}</dd>
        <dt>Payment</dt><dd>{d.paymentFound ? <Link className="mono" to={`/payments/${d.paymentId}`}>{d.paymentId}</Link> : <span className="mono">{d.paymentId} (not uploaded)</span>}</dd>
        <dt>Win chance</dt><dd><Prob value={d.winProb} /> {!d.merchantResponded && d.winProbIfResponded != null && <span className="muted small"> → {pct(d.winProbIfResponded)} if you respond</span>}
          <div className="muted small">{d.winSource === 'rules' ? 'from the dispute rules (the full model is not published)' : <>from the full model{d.disputeRules?.winProb != null && <> · the four rules alone give {pct(d.disputeRules.winProb)}</>}</>}</div></dd>
        <dt>Expected gain</dt><dd>{usd(d.expectedGainUsd, 2)} <span className="muted small">from fighting instead of accepting</span></dd>
        <dt>Expected result</dt><dd>Fight {usd(d.expectedValueFight, 2)} · Accept {usd(d.expectedValueAccept, 2)}</dd>
      </dl>
      <RuleBox result={d.disputeRules} title="Dispute rules (explain the chance; the decision uses the full model)" />
      <h3>Evidence submitted</h3>
      {d.evidenceSubmitted.length ? <div className="chips">{d.evidenceSubmitted.map((e) => <span key={e} className="chip">{e.replace(/_/g, ' ')}</span>)}</div> : <p className="muted">None yet.</p>}
      <h3>Add if you have it</h3>
      {d.suggestedEvidence.length ? (
        <ul className="actions">
          {d.suggestedEvidence.map((s) => <li key={s.token}><div>{s.label}</div><div className="muted small">+{pct(s.winUplift)} win chance for this reason</div></li>)}
        </ul>
      ) : <p className="muted">No other evidence type improved wins for this reason in months 1–22.</p>}
      {d.reasonGuidance && <p className="guidance"><strong>For {d.reasonLabel.toLowerCase()}:</strong> {d.reasonGuidance}</p>}
      {d.drivers.length > 0 && (<><h3>What helps this case</h3><ul className="drivers">{d.drivers.map((x) => <li key={x.feature}>{x.label}</li>)}</ul></>)}
    </Card>
  );
}
