import React from 'react';
import { Link } from 'react-router-dom';
import { pct, usd } from './format.js';

export function Tier({ tier }) {
  if (!tier) return <span className="muted">–</span>;
  return <span className={`badge tier-${tier}`}>{tier}</span>;
}

export function Rec({ value }) {
  if (!value) return <span className="muted">–</span>;
  return <span className={`badge rec-${value}`}>{value}</span>;
}

/** Probability with a small bar; shows "not predicted" when the model was not published. */
export function Prob({ value, title }) {
  if (value === null || value === undefined) {
    return <span className="muted" title="This model did not beat the validation bar, so it is not published">not predicted</span>;
  }
  return (
    <span className="prob" title={title}>
      <span className="prob-bar"><span style={{ width: `${Math.round(value * 100)}%` }} /></span>
      {pct(value)}
    </span>
  );
}

export function Stat({ label, value, sub, tone }) {
  return (
    <div className={`stat ${tone || ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export function Card({ title, children, actions, className }) {
  return (
    <section className={`card ${className || ''}`}>
      {(title || actions) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

export function NeedsUpload({ step = 1 }) {
  return (
    <Empty>
      {step === 1 ? 'Upload invoices, photos and payments first.' : 'Upload disputes (step 2) to see this.'}{' '}
      <Link to="/upload">Go to Upload →</Link>
    </Empty>
  );
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="alert error">
      <strong>{error.message}</strong>
      {error.problems?.length > 0 && (
        <ul>{error.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
      )}
    </div>
  );
}

/** Horizontal bar for simple comparisons. */
export function HBar({ value, max, tone }) {
  const w = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  return <span className={`hbar ${tone || ''}`}><span style={{ width: `${w}%` }} /></span>;
}

/** Range bar centred on zero: low..high with a marker at the estimate. */
export function RangeBar({ low, high, estimate, scale }) {
  const s = scale || Math.max(Math.abs(low), Math.abs(high), Math.abs(estimate), 1e-9);
  const pos = (v) => 50 + (v / s) * 50;
  const left = pos(Math.min(low, high));
  const right = pos(Math.max(low, high));
  return (
    <div className="rangebar">
      <div className="rangebar-zero" />
      <div className={`rangebar-span ${estimate <= 0 ? 'good' : 'bad'}`} style={{ left: `${left}%`, width: `${Math.max(right - left, 0.5)}%` }} />
      <div className="rangebar-mark" style={{ left: `${pos(estimate)}%` }} />
    </div>
  );
}

export function Pager({ page, size, total, onPage }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="pager">
      <button disabled={page <= 0} onClick={() => onPage(page - 1)}>← Prev</button>
      <span>Page {page + 1} of {pages} · {total} rows</span>
      <button disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>Next →</button>
    </div>
  );
}

export function Money({ value }) {
  return <span>{usd(value)}</span>;
}

/** Three-rule result for one moment: each rule met or not, the win probability, and what to add. */
export function RuleBox({ result, title }) {
  if (!result) return null;
  const todo = result.rules.filter((r) => !r.met && r.action);
  return (
    <div className="rule-box">
      <div className="rule-box-head">
        <span className="rule-box-title">{title || 'Rule check'}</span>
        <span className="rule-box-prob" title="Dispute win probability from the rules">
          {result.winProb == null ? <span className="muted">no rule table</span> : <>Win {pct(result.winProb)}</>}
          <span className="muted small"> · {result.rulesMet}/{result.rules.length} rules met</span>
        </span>
      </div>
      <ul className="rule-list">
        {result.rules.map((r) => (
          <li key={r.id} className={r.met ? 'met' : 'unmet'}>
            <span className="rule-mark" aria-label={r.met ? 'met' : 'not met'}>{r.met ? '✓' : '✗'}</span>
            <span>{r.label}</span>
          </li>
        ))}
      </ul>
      {todo.length > 0 && <div className="small rule-todo"><b>To strengthen:</b> {todo.map((r) => r.action).join('; ')}.</div>}
    </div>
  );
}

/** Compact "57% 2/3" for tables. */
export function RuleCell({ result }) {
  if (!result || result.winProb == null) return <span className="muted">–</span>;
  const tip = result.rules.map((r) => (r.met ? '✓ ' : '✗ ') + r.label).join(String.fromCharCode(10));
  return <span title={tip}>{pct(result.winProb)} <span className="muted small">{result.rulesMet}/{result.rules.length}</span></span>;
}

/** Win chance from the rules, with how many of the rules are met. */
export function RuleProb({ value, result }) {
  return (
    <span className="rule-prob">
      <Prob value={value} title={result ? result.rules.map((r) => (r.met ? '✓ ' : '✗ ') + r.label).join(String.fromCharCode(10)) : undefined} />
      {result && <span className="muted small"> {result.rulesMet}/{result.rules.length}</span>}
    </span>
  );
}
