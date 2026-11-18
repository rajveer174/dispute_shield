import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { api, getSessionId, setSessionId } from './api.js';
import UploadPage from './pages/UploadPage.jsx';
import PaymentsPage from './pages/PaymentsPage.jsx';
import DisputesPage from './pages/DisputesPage.jsx';
import TeamsPage from './pages/TeamsPage.jsx';
import PortfolioPage from './pages/PortfolioPage.jsx';
import WhatIfPage from './pages/WhatIfPage.jsx';
import ModelsPage from './pages/ModelsPage.jsx';
import ExportPage from './pages/ExportPage.jsx';

const AppContext = createContext(null);
export const useApp = () => useContext(AppContext);

const NAV = [
  ['/upload', 'Upload'],
  ['/payments', 'Payments'],
  ['/disputes', 'Disputes'],
  ['/teams', 'Teams'],
  ['/portfolio', 'All teams'],
  ['/whatif', 'What-if'],
  ['/models', 'Models'],
  ['/export', 'Export'],
];

export default function App() {
  const [status, setStatus] = useState(null);
  const [statusError, setStatusError] = useState(null);
  const [session, setSession] = useState(null);

  useEffect(() => {
    api.status().then(setStatus).catch((e) => setStatusError(e.message));
    const id = getSessionId();
    if (id) {
      api.session(id).then(setSession).catch(() => setSessionId(null));
    }
  }, []);

  const updateSession = useCallback((s) => {
    setSession(s);
    setSessionId(s ? s.sessionId : null);
  }, []);

  const ctx = { status, session, sessionId: session?.sessionId, updateSession };

  return (
    <AppContext.Provider value={ctx}>
      <header className="topbar">
        <div className="brand">
          <span className="logo">◆</span> Dispute Shield
        </div>
        <nav>
          {NAV.map(([to, text]) => (
            <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'active' : '')}>{text}</NavLink>
          ))}
        </nav>
        <div className="session-pill">
          {session ? (
            <>
              <span className="dot ok" /> {session.paymentsScored} payments · {session.disputesScored} disputes
            </>
          ) : (
            <><span className="dot" /> No upload yet</>
          )}
        </div>
      </header>
      <main>
        {statusError && <div className="alert error">Cannot reach the API: {statusError}</div>}
        {status && !status.ready && (
          <div className="alert error">Models are not loaded: {status.error}. Run the trainer (see README).</div>
        )}
        <Routes>
          <Route path="/" element={<Navigate to="/upload" replace />} />
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/payments" element={<PaymentsPage />} />
          <Route path="/payments/:paymentId" element={<PaymentsPage />} />
          <Route path="/disputes" element={<DisputesPage />} />
          <Route path="/disputes/:disputeId" element={<DisputesPage />} />
          <Route path="/teams" element={<TeamsPage />} />
          <Route path="/teams/:teamId" element={<TeamsPage />} />
          <Route path="/portfolio" element={<PortfolioPage />} />
          <Route path="/whatif" element={<WhatIfPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/export" element={<ExportPage />} />
          <Route path="*" element={<Navigate to="/upload" replace />} />
        </Routes>
      </main>
      <footer className="footer">
        {status?.ready && (
          <>Models trained on {status.dataStart?.slice(0, 10)} → {status.dataEnd?.slice(0, 10)} (months 1–22). Uploads are scored, never used for training.</>
        )}
      </footer>
    </AppContext.Provider>
  );
}
