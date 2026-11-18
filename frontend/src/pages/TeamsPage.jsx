import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, Empty, ErrorBox, HBar, Prob, Rec, Stat, Tier } from '../components.jsx';
import { label, pct, usd } from '../format.js';

export default function TeamsPage() {
  const { sessionId } = useApp();
  const { teamId } = useParams();
  const navigate = useNavigate();
  const [teams, setTeams] = useState([]);
  const [filter, setFilter] = useState('');
  const [error, setError] = useState(null);

  useEffect(() => { api.teams(sessionId).then(setTeams).catch(setError); }, [sessionId]);

  const shown = useMemo(() => {
    const f = filter.toLowerCase();
    const list = teams.filter((t) => !f || t.teamId.toLowerCase().includes(f) || (t.profession || '').toLowerCase().includes(f));
    return [...list].sort((a, b) => (b.uploadedExpectedLossUsd - a.uploadedExpectedLossUsd) || (b.historyUsdLost - a.historyUsdLost));
  }, [teams, filter]);

  return (
    <div className="page">
      <h1>By team</h1>
      <ErrorBox error={error} />
      <div className="team-picker">
        <input list="team-ids" placeholder="Type a team ID…" defaultValue={teamId || ''}
               onKeyDown={(e) => { if (e.key === 'Enter' && e.target.value.trim()) navigate(`/teams/${e.target.value.trim()}`); }}
               onChange={(e) => { setFilter(e.target.value); if (teams.some((t) => t.teamId === e.target.value)) navigate(`/teams/${e.target.value}`); }} />
        <datalist id="team-ids">{teams.map((t) => <option key={t.teamId} value={t.teamId}>{label(t.profession)}</option>)}</datalist>
      </div>
      {teamId ? <TeamDetail teamId={teamId} sessionId={sessionId} /> : (
        <Card title="Teams (most dollars at risk first)">
          <table>
            <thead><tr><th>Team</th><th>Profession</th><th>State</th><th className="r">Disputes (mo 1–22)</th><th className="r">Lost (mo 1–22)</th><th className="r">Uploaded payments</th><th className="r">High risk</th><th className="r">At risk now</th></tr></thead>
            <tbody>
              {shown.slice(0, 200).map((t) => (
                <tr key={t.teamId} onClick={() => navigate(`/teams/${t.teamId}`)}>
                  <td className="mono">{t.teamId}</td><td>{label(t.profession)}</td><td>{t.state || '–'}</td>
                  <td className="r">{t.historyDisputes}</td><td className="r">{usd(t.historyUsdLost)}</td>
                  <td className="r">{t.uploadedPayments}</td><td className="r">{t.uploadedHighRisk}</td><td className="r">{usd(t.uploadedExpectedLossUsd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function TeamDetail({ teamId, sessionId }) {
  const [d, setD] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => { setD(null); setError(null); api.team(teamId, sessionId).then(setD).catch(setError); }, [teamId, sessionId]);
  if (error) return <ErrorBox error={error} />;
  if (!d) return <p>Loading…</p>;
  const h = d.history;
  const u = d.upload;
  const topGaps = (h?.gaps || []).filter((g) => g.impact > 0).slice(0, 5);

  return (
    <>
      <div className="team-head">
        <h2 className="mono">{teamId}</h2>
        {h && <span className="muted">{label(h.profession)} · {h.state} · {h.teamSize} people</span>}
        <Link to="/teams" className="small">← all teams</Link>
      </div>

      <div className="grid two">
        <Card title="Track record · months 1–22">
          {h ? (
            <div className="stats">
              <Stat label="Payments" value={h.payments.toLocaleString()} sub={usd(h.volumeUsd)} />
              <Stat label="Disputes" value={h.disputes} sub={`${pct(h.disputeRate, 2)} of payments`} />
              <Stat label="Win rate" value={pct(h.winRate)} sub={`${h.won} won · ${h.lost} lost · ${h.open} open`} />
              <Stat label="Lost to disputes" value={usd(h.usdLost)} tone="bad" sub="amount + fees" />
            </div>
          ) : <Empty>No history: new team.</Empty>}
        </Card>
        <Card title="Uploaded months">
          {u.payments ? (
            <div className="stats">
              <Stat label="Payments" value={u.payments} sub={usd(u.volumeUsd)} />
              <Stat label="High risk" value={u.tiers.high} tone="bad" sub={`${u.tiers.medium} medium`} />
              <Stat label="Dollars at risk" value={usd(u.expectedLossUsd)} />
              <Stat label="Disputes" value={u.disputes} sub={u.disputes ? `${u.fight} fight · ${u.accept} accept` : ''} />
            </div>
          ) : <Empty>No uploaded payments for this team.</Empty>}
        </Card>
      </div>

      <div className="grid two">
        <Card title="Main gaps · what to fix first">
          {d.uploadGaps.length > 0 && (
            <>
              <p className="muted small">From the uploaded invoices, ranked by dollars the fix would protect.</p>
              <table>
                <thead><tr><th>Missing</th><th>Share of jobs</th><th className="r">Protects</th></tr></thead>
                <tbody>
                  {d.uploadGaps.filter((g) => g.valueUsd > 0).slice(0, 6).map((g) => (
                    <tr key={g.label} title={g.action}><td>{g.label}<div className="muted small">{g.action}</div></td><td><HBar value={g.missingShare} max={1} tone="bad" /> {pct(g.missingShare)}</td><td className="r">{usd(g.valueUsd, 2)}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {topGaps.length > 0 && (
            <>
              <p className="muted small">History (months 1–22): how often this team missed evidence compared with all teams.</p>
              <table>
                <thead><tr><th>Evidence</th><th>This team missing</th><th>All teams</th><th className="r">Lost disputes without it</th></tr></thead>
                <tbody>
                  {topGaps.map((g) => (
                    <tr key={g.ruleId}><td>{g.label}</td><td>{pct(g.teamMissingRate)}</td><td className="muted">{pct(g.platformMissingRate)}</td><td className="r">{g.lostDisputesMissing}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {!d.uploadGaps.length && !topGaps.length && <Empty>No significant gaps.</Empty>}
        </Card>
        <Card title="Disputes by reason · months 1–22">
          {h && Object.keys(h.disputesByReason).length ? (
            <table><tbody>
              {Object.entries(h.disputesByReason).sort((a, b) => b[1] - a[1]).map(([r, n]) => (
                <tr key={r}><td>{label(r)}</td><td><HBar value={n} max={Math.max(...Object.values(h.disputesByReason))} /></td><td className="r">{n}</td></tr>
              ))}
            </tbody></table>
          ) : <Empty>No disputes.</Empty>}
        </Card>
      </div>

      {d.payments.length > 0 && (
        <Card title={`Uploaded payments (${d.payments.length})`}>
          <table>
            <thead><tr><th>Payment</th><th className="r">Amount</th><th>Method</th><th>Payment win</th><th>Risk</th><th className="r">At risk</th><th>Do now</th></tr></thead>
            <tbody>
              {d.payments.slice(0, 100).map((p) => (
                <tr key={p.paymentId}>
                  <td><Link className="mono" to={`/payments/${p.paymentId}`}>{p.paymentId}</Link></td>
                  <td className="r">{usd(p.amountUsd)}</td><td>{p.paymentMethod}</td><td><Prob value={p.paymentWinProb} /></td>
                  <td><Tier tier={p.disputeRiskTier} /></td><td className="r">{usd(p.expectedLossUsd, 2)}</td>
                  <td className="truncate">{p.actions.find((a) => a.when === 'now')?.action || <span className="muted">–</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {d.disputes.length > 0 && (
        <Card title={`Uploaded disputes (${d.disputes.length})`}>
          <table>
            <thead><tr><th>Dispute</th><th>Reason</th><th className="r">Amount</th><th>Win chance</th><th>Decision</th><th>Rationale</th></tr></thead>
            <tbody>
              {d.disputes.map((x) => (
                <tr key={x.disputeId}>
                  <td><Link className="mono" to={`/disputes/${x.disputeId}`}>{x.disputeId}</Link></td>
                  <td>{x.reasonLabel}</td><td className="r">{usd(x.amountUsd)}</td><td><Prob value={x.winProb} /></td>
                  <td><Rec value={x.recommendation} /></td><td className="small">{x.rationale}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}
