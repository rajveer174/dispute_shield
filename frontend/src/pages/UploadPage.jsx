import React, { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../App.jsx';
import { Card, ErrorBox, Stat } from '../components.jsx';
import { usd } from '../format.js';

// Must match spring.servlet.multipart in application.yml.
const MAX_TOTAL_MB = 30;

function FileInput({ name, label, required, file, onChange, hint, disabled }) {
  const inputRef = useRef(null);
  const remove = () => {
    // Clear the native input too, so choosing the same file again still fires onChange.
    if (inputRef.current) inputRef.current.value = '';
    onChange(name, null);
  };
  return (
    <div className={`file ${file ? 'has-file' : ''}`}>
      <label className="file-pick">
        <span className="file-label">
          {label} {required ? <em>required</em> : <em className="opt">optional</em>}
        </span>
        <input ref={inputRef} type="file" accept=".csv,text/csv" disabled={disabled}
               onChange={(e) => onChange(name, e.target.files[0] || null)} />
      </label>
      <div className="file-row">
        <span className="file-name">{file ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB` : hint}</span>
        {file && (
          <button type="button" className="file-remove" onClick={remove} disabled={disabled}
                  aria-label={`Remove ${label}`} title={`Remove ${label}`}>
            ✕ Remove
          </button>
        )}
      </div>
    </div>
  );
}

export default function UploadPage() {
  const { session, updateSession, status } = useApp();
  const [files, setFiles] = useState({ invoices: null, photos: null, payments: null, teams: null });
  const [disputes, setDisputes] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  const setFile = (k, f) => setFiles((prev) => ({ ...prev, [k]: f }));
  const totalMb = Object.values(files).reduce((s, f) => s + (f ? f.size : 0), 0) / 1024 / 1024;

  const step1 = async (e) => {
    e.preventDefault();
    setError(null);
    setBusy('step1');
    try {
      const s = await api.createSession(files);
      updateSession(s);
      setDisputes(null);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  };

  const step2 = async (e) => {
    e.preventDefault();
    setError(null);
    setBusy('step2');
    try {
      updateSession(await api.addDisputes(session.sessionId, disputes));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  };

  const reset = () => {
    updateSession(null);
    setFiles({ invoices: null, photos: null, payments: null, teams: null });
    setDisputes(null);
  };

  return (
    <div className="page">
      <h1>Upload new months</h1>
      <p className="lead">
        Upload in the same CSV format as the training data. Scoring uses the models trained on months 1–22;
        uploaded files are never used to retrain.
      </p>
      <ErrorBox error={error} />

      <div className="grid two">
        <Card title="Step 1 · Invoices, photos and payments">
          <form onSubmit={step1}>
            <FileInput name="invoices" label="invoices.csv" required file={files.invoices} onChange={setFile} hint="One row per job, as at payment time" />
            <FileInput name="photos" label="photos.csv" file={files.photos} onChange={setFile} hint="One row per job photo" />
            <FileInput name="payments" label="payments.csv" required file={files.payments} onChange={setFile} hint="One row per payment" />
            <FileInput name="teams" label="teams.csv" file={files.teams} onChange={setFile} hint="Only needed for teams not seen in training" />
            <p className="small muted">Up to {MAX_TOTAL_MB} MB in total{totalMb > 0 && <> · selected {totalMb.toFixed(1)} MB</>}.</p>
            {totalMb > MAX_TOTAL_MB && <div className="alert warn">Files total {totalMb.toFixed(1)} MB; the limit is {MAX_TOTAL_MB} MB in total. Remove a file or upload fewer months.</div>}
            <button className="primary" disabled={!files.invoices || !files.payments || busy || !status?.ready || totalMb > MAX_TOTAL_MB}>
              {busy === 'step1' ? 'Scoring payments…' : 'Upload & score payments'}
            </button>
          </form>
        </Card>

        <Card title="Step 2 · Disputes (no outcomes)">
          {!session ? (
            <p className="muted">Complete step 1 first. Disputes are matched to the payments you uploaded.</p>
          ) : (
            <form onSubmit={step2}>
              <FileInput name="disputes" label="disputes.csv" required file={disputes} onChange={(_, f) => setDisputes(f)} hint="Disputes raised on the step 1 payments" />
              <p className="muted small">If outcome / closed_at columns are present they are ignored.</p>
              {disputes && disputes.size / 1024 / 1024 > MAX_TOTAL_MB && <div className="alert warn">disputes.csv is {(disputes.size / 1024 / 1024).toFixed(1)} MB; the limit is {MAX_TOTAL_MB} MB.</div>}
              <button className="primary" disabled={!disputes || busy || disputes.size / 1024 / 1024 > MAX_TOTAL_MB}>
                {busy === 'step2' ? 'Scoring disputes…' : session.disputesScored ? 'Replace & re-score disputes' : 'Upload & score disputes'}
              </button>
            </form>
          )}
        </Card>
      </div>

      {session && (
        <Card title="Current upload" actions={<button onClick={reset}>Start over</button>}>
          <div className="stats">
            <Stat label="Payments scored" value={session.paymentsScored} />
            <Stat label="High risk" value={session.tiers?.high ?? 0} tone="bad" sub={`${session.tiers?.medium ?? 0} medium · ${session.tiers?.low ?? 0} low`} />
            <Stat label="Dollars at risk" value={usd(session.expectedLossUsd)} sub="sum of expected loss" />
            <Stat label="Disputes scored" value={session.disputesScored} sub={session.disputesScored ? `${session.fight} fight · ${session.accept} accept` : 'step 2 pending'} />
          </div>
          <div className="files-summary">
            {Object.entries(session.files || {}).map(([k, v]) => <span key={k} className="chip">{k}: {v} rows</span>)}
          </div>
          {session.warnings?.length > 0 && (
            <div className="alert warn">
              <ul>{session.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
            </div>
          )}
          <div className="next-links">
            <Link to="/payments">Review payments →</Link>
            {session.disputesScored > 0 && <Link to="/disputes">Review disputes →</Link>}
            <Link to="/export">Download CSVs →</Link>
          </div>
        </Card>
      )}
    </div>
  );
}
