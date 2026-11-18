import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, exportUrls } from '../api.js';
import { Card, ErrorBox, RangeBar } from '../components.jsx';
import { num, signed, signedUsd, usd } from '../format.js';

const METRIC_LABEL = { disputes: 'Disputes', disputes_lost: 'Disputes lost', usd_lost: 'Dollars lost' };

export default function WhatIfPage() {
  const [policies, setPolicies] = useState(null);
  const [error, setError] = useState(null);
  const [params, setParams] = useSearchParams();
  useEffect(() => { api.whatIf().then(setPolicies).catch(setError); }, []);

  if (error) return <div className="page"><ErrorBox error={error} /></div>;
  if (!policies) return <div className="page">Loading…</div>;
  const selectedId = params.get('policy') || policies[0]?.policy;
  const p = policies.find((x) => x.policy === selectedId) || policies[0];

  return (
    <div className="page">
      <h1>What-if: product policies</h1>
      <p className="lead">Estimated change over months 1–22 if the policy had been in place, with a 90% range. Negative means fewer.</p>
      <div className="policy-tabs">
        {policies.map((x) => (
          <button key={x.policy} className={x.policy === p.policy ? 'active' : ''} onClick={() => setParams({ policy: x.policy })}>
            {x.label}
          </button>
        ))}
      </div>

      {p && (
        <>
          <Card title={p.label} actions={<span className={`badge conf-${p.confidence}`}>{p.confidence} confidence</span>}>
            <p>{p.description}</p>
            <div className="whatif-grid">
              {p.metrics.map((m) => {
                const fmt = m.metric === 'usd_lost' ? signedUsd : (v) => signed(v, 1);
                const pctChange = m.baseline ? (m.estimatedChange / m.baseline) * 100 : 0;
                return (
                  <div key={m.metric} className="whatif-metric">
                    <div className="stat-label">{METRIC_LABEL[m.metric] || m.metric}</div>
                    <div className={`stat-value ${m.estimatedChange <= 0 ? 'good-text' : 'bad-text'}`}>{fmt(m.estimatedChange)}</div>
                    <div className="stat-sub">{pctChange.toFixed(1)}% of {m.metric === 'usd_lost' ? usd(m.baseline) : num(m.baseline, 0)}</div>
                    <RangeBar low={m.low} high={m.high} estimate={m.estimatedChange} />
                    <div className="range-label">range {fmt(m.low)} to {fmt(m.high)}</div>
                    <div className="muted small">
                      from lost jobs {fmt(m.fromLostJobs)} · from better paperwork / lower risk {fmt(m.fromBehaviourChange)}
                    </div>
                  </div>
                );
              })}
            </div>
            <p className="muted"><strong>Why this confidence:</strong> {p.confidenceReason}</p>
          </Card>

          <div className="grid two">
            <Card title="Who is affected">
              <ul className="plain">
                <li><strong>{num(p.affectedPayments)}</strong> payments would have changed ({usd(p.affectedVolumeUsd)})</li>
                <li><strong>{num(p.affectedDisputes)}</strong> of the historical disputes were on those payments</li>
                <li><strong>{num(p.jobsLost, 0)}</strong> jobs would have left TestPay</li>
              </ul>
              <h3>Assumptions</h3>
              <ul>{p.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul>
            </Card>
            <Card title="How it is estimated">
              <ol>{p.method.map((a, i) => <li key={i}>{a}</li>)}</ol>
            </Card>
          </div>
        </>
      )}
      <p><a href={exportUrls().whatif} className="button">Download whatif_results.csv</a></p>
    </div>
  );
}
