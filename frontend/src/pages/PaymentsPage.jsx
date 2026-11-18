import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, ErrorBox, NeedsUpload, Pager, Prob, Rec, RuleBox, RuleProb, Tier } from '../components.jsx';
import { date, label, pct, usd } from '../format.js';

export default function PaymentsPage() {
  const { sessionId } = useApp();
  const { paymentId } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const tier = params.get('tier') || '';
  const team = params.get('team') || '';
  const q = params.get('q') || '';
  const sort = params.get('sort') || 'expected_loss';
  const page = Number(params.get('page') || 0);

  useEffect(() => {
    if (!sessionId) return;
    api.payments(sessionId, { tier, team, q, sort, page, size: 50 }).then(setData).catch(setError);
  }, [sessionId, tier, team, q, sort, page]);

  if (!sessionId) return <div className="page"><h1>Payments</h1><NeedsUpload /></div>;

  const set = (k, v) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    if (k !== 'page') next.delete('page');
    setParams(next);
  };

  return (
    <div className="page">
      <h1>Payments</h1>
      <p className="lead">Each uploaded payment: how strong its paperwork is, how likely it is to be disputed, and what to do now.</p>
      <ErrorBox error={error} />
      <div className="filters">
        <select value={tier} onChange={(e) => set('tier', e.target.value)}>
          <option value="">All tiers</option>
          <option value="high">High risk</option>
          <option value="medium">Medium risk</option>
          <option value="low">Low risk</option>
        </select>
        <input placeholder="Team ID" value={team} onChange={(e) => set('team', e.target.value.trim())} />
        <input placeholder="Search payment / invoice / customer" value={q} onChange={(e) => set('q', e.target.value)} />
        <select value={sort} onChange={(e) => set('sort', e.target.value)}>
          <option value="expected_loss">Sort: dollars at risk</option>
          <option value="risk">Sort: dispute risk</option>
          <option value="win">Sort: weakest paperwork</option>
          <option value="amount">Sort: amount</option>
        </select>
      </div>

      <div className={`split ${paymentId ? 'with-detail' : ''}`}>
        <Card className="table-card">
          {data && (
            <>
              <table>
                <thead>
                  <tr>
                    <th>Payment</th><th>Team</th><th>Method</th><th className="r">Amount</th>
                    <th title="Win chance if disputed, from the three invoice rules">Invoice win</th><th title="Win chance if disputed, from the three payment rules">Payment win</th><th>Risk</th><th className="r">At risk</th><th>Top action</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((p) => (
                    <tr key={p.paymentId} className={p.paymentId === paymentId ? 'selected' : ''}
                        onClick={() => navigate(`/payments/${encodeURIComponent(p.paymentId)}?${params}`)}>
                      <td><span className="mono">{p.paymentId}</span>{p.disputed && <span className="badge small rec-accept" title="Disputed in step 2">disputed</span>}</td>
                      <td><Link to={`/teams/${p.teamId}`} onClick={(e) => e.stopPropagation()}>{p.teamId}</Link></td>
                      <td>{p.paymentMethod || '–'}</td>
                      <td className="r">{usd(p.amountUsd)}</td>
                      <td><RuleProb value={p.invoiceWinProb} result={p.invoiceRules} /></td>
                      <td><RuleProb value={p.paymentWinProb} result={p.paymentRules} /></td>
                      <td><Tier tier={p.disputeRiskTier} /></td>
                      <td className="r">{usd(p.expectedLossUsd, 2)}</td>
                      <td className="truncate">{p.actions[0]?.label || <span className="muted">none</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} size={data.size} total={data.total} onPage={(p) => set('page', String(p))} />
            </>
          )}
        </Card>
        {paymentId && <PaymentDetail sessionId={sessionId} paymentId={paymentId} onClose={() => navigate(`/payments?${params}`)} />}
      </div>
    </div>
  );
}

function PaymentDetail({ sessionId, paymentId, onClose }) {
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    setD(null);
    api.payment(sessionId, paymentId).then(setD).catch(setError);
  }, [sessionId, paymentId]);

  if (error) return <Card className="detail"><ErrorBox error={error} /></Card>;
  if (!d) return <Card className="detail">Loading…</Card>;
  const p = d.payment;
  const now = p.actions.filter((a) => a.when === 'now');
  const next = p.actions.filter((a) => a.when === 'next_time');

  return (
    <Card className="detail" title={<span className="mono">{p.paymentId}</span>} actions={<button onClick={onClose}>✕</button>}>
      <p className="headline">{p.headline}</p>
      <dl className="kv">
        <dt>Team</dt><dd><Link to={`/teams/${p.teamId}`}>{p.teamId}</Link></dd>
        <dt>Invoice</dt><dd className="mono">{p.invoiceId}{p.invoiceMissing && <span className="badge small tier-high">missing</span>}</dd>
        <dt>Collected</dt><dd>{date(p.collectedAt)}</dd>
        <dt>Amount</dt><dd>{usd(p.amountUsd, 2)} · {p.paymentMethod} · {label(p.jobType)}</dd>
      </dl>

      <div className="moments">
        <div className="moment">
          <div className="moment-title">① Invoice ready</div>
          <div className="moment-q">If disputed, chance to win on the invoice alone (three invoice rules)</div>
          <Prob value={p.invoiceWinProb} />
          <RuleBox result={p.invoiceRules} title="Invoice rules" />
        </div>
        <div className="moment">
          <div className="moment-title">② Payment collected</div>
          <div className="moment-q">Chance to win, now including payment data (three payment rules)</div>
          <Prob value={p.paymentWinProb} />
          <div className="moment-q">Chance of a dispute</div>
          <div><Tier tier={p.disputeRiskTier} /> {p.disputeRiskScore != null && <span className="muted">{pct(p.disputeRiskScore, 1)}</span>}</div>
          <div className="moment-q">Dollars at risk</div>
          <div className="big">{usd(p.expectedLossUsd, 2)}</div>
          <RuleBox result={p.paymentRules} title="Payment rules" />
        </div>
      </div>

      {p.riskDrivers.length > 0 && (
        <>
          <h3>Why this risk level</h3>
          <ul className="drivers">{p.riskDrivers.map((r) => <li key={r.feature}>{r.label}</li>)}</ul>
        </>
      )}

      <h3>Do now</h3>
      {now.length ? <ActionList actions={now} /> : <p className="muted">Nothing that can still be fixed for this payment.</p>}
      {next.length > 0 && (<><h3>Next time on jobs like this</h3><ActionList actions={next} /></>)}
      {p.missingEvidence.length > 0 && <p className="muted small">Missing: {p.missingEvidence.join(', ')}</p>}

      {d.disputes.length > 0 && (
        <>
          <h3>Disputes on this payment</h3>
          {d.disputes.map((x) => (
            <div key={x.disputeId} className="mini-dispute">
              <Link to={`/disputes/${x.disputeId}`} className="mono">{x.disputeId}</Link> · {x.reasonLabel} · <Prob value={x.winProb} /> <Rec value={x.recommendation} />
            </div>
          ))}
        </>
      )}
    </Card>
  );
}

function ActionList({ actions }) {
  return (
    <ul className="actions">
      {actions.map((a) => (
        <li key={a.ruleId}>
          <div>{a.action}</div>
          <div className="muted small">+{pct(a.winUplift)} win chance · protects ~{usd(a.valueUsd, 2)}</div>
        </li>
      ))}
    </ul>
  );
}
