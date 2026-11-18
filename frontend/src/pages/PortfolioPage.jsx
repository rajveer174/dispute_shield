import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, ErrorBox, HBar, Stat, Tier } from '../components.jsx';
import { num, pct, usd } from '../format.js';

export default function PortfolioPage() {
  const { sessionId, session } = useApp();
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => { api.portfolio(sessionId).then(setD).catch(setError); }, [sessionId, session?.disputesScored]);

  if (error) return <div className="page"><ErrorBox error={error} /></div>;
  if (!d) return <div className="page">Loading…</div>;
  const h = d.history;
  const maxLost = Math.max(...h.byReason.map((r) => r.usdLost), 1);
  const maxMonth = Math.max(...h.byMonth.map((m) => m.usdLost), 1);
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  return (
    <div className="page">
      <h1>Across all teams</h1>
      <div className="stats">
        <Stat label="Payments · months 1–22" value={num(h.payments)} sub={usd(h.volumeUsd)} />
        <Stat label="Disputes" value={num(h.disputes)} sub={`${pct(h.disputes / Math.max(h.payments, 1), 2)} of payments`} />
        <Stat label="Win rate" value={pct(h.winRate)} sub="won / (won + lost)" />
        <Stat label="Dollars lost" value={usd(h.usdLost)} tone="bad" sub="amount + $25 fee on lost disputes" />
      </div>

      <Card title="By dispute reason · months 1–22">
        <table>
          <thead><tr><th>Reason</th><th className="r">Disputes</th><th className="r">Won</th><th className="r">Lost</th><th className="r">Withdrawn</th><th className="r">Open</th><th>Win rate</th><th className="r">Disputed $</th><th>Dollars lost</th></tr></thead>
          <tbody>
            {h.byReason.map((r) => (
              <tr key={r.reason}>
                <td>{r.label}</td><td className="r">{r.disputes}</td><td className="r">{r.won}</td><td className="r">{r.lost}</td>
                <td className="r">{r.withdrawn}</td><td className="r">{r.open}</td>
                <td><HBar value={r.winRate} max={1} tone="good" /> {pct(r.winRate)}</td>
                <td className="r">{usd(r.disputedUsd)}</td>
                <td><HBar value={r.usdLost} max={maxLost} tone="bad" /> {usd(r.usdLost)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {d.upload && (
        <div className="grid two">
          <Card title="Uploaded disputes by reason (predicted)">
            {d.upload.byReason.length ? (
              <table>
                <thead><tr><th>Reason</th><th className="r">Disputes</th><th>Predicted win rate</th><th className="r">Expected $ lost</th><th className="r">Fight / accept</th></tr></thead>
                <tbody>
                  {d.upload.byReason.map((r) => (
                    <tr key={r.reason}><td>{r.label}</td><td className="r">{r.disputes}</td><td>{pct(r.predictedWinRate)}</td><td className="r">{usd(r.expectedUsdLost)}</td><td className="r">{r.fight} / {r.accept}</td></tr>
                  ))}
                </tbody>
              </table>
            ) : <p className="muted">Upload disputes (step 2) to see this.</p>}
          </Card>
          <Card title="Uploaded payments by risk tier">
            <table>
              <thead><tr><th>Tier</th><th className="r">Payments</th><th className="r">Volume</th><th className="r">Dollars at risk</th><th className="r">Disputed</th></tr></thead>
              <tbody>
                {d.upload.byTier.map((t) => (
                  <tr key={t.tier}><td><Tier tier={t.tier} /></td><td className="r">{t.payments}</td><td className="r">{usd(t.volumeUsd)}</td><td className="r">{usd(t.expectedLossUsd)}</td><td className="r">{session?.disputesScored ? t.disputed : '–'}</td></tr>
                ))}
              </tbody>
            </table>
            <p className="muted small">"Disputed" shows how many payments in each tier received a dispute in step 2: a live check of the risk tiers.</p>
          </Card>
        </div>
      )}

      <Card title="Dollars lost by month · months 1–22">
        <div className="months-wrap">
          <div className="months-axis small muted"><span>{usd(maxMonth)}</span><span>$0</span></div>
          <div className="months">
            {h.byMonth.map((m, i) => {
              const [y, mo] = m.label.split('-');
              const showYear = i === 0 || mo === '01';
              return (
                <div key={m.month} className="month-col" title={`${m.label}: ${m.disputes} disputes, ${usd(m.usdLost)} lost`}>
                  <div className="month-bar">
                    <span style={{ height: `${(m.usdLost / maxMonth) * 100}%` }} />
                  </div>
                  <div className="month-label">{MONTHS[Number(mo) - 1] || mo}</div>
                  <div className="month-year">{showYear ? y : ' '}</div>
                </div>
              );
            })}
          </div>
        </div>
        <p className="muted small">Recent months look better partly because disputes arrive weeks after payment and many are still open.</p>
      </Card>
    </div>
  );
}
