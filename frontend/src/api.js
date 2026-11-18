// Thin wrapper over the Spring Boot REST API.
const SESSION_KEY = 'dispute-shield.session';

export function getSessionId() {
  try { return localStorage.getItem(SESSION_KEY); } catch { return null; }
}

export function setSessionId(id) {
  try {
    if (id) localStorage.setItem(SESSION_KEY, id);
    else localStorage.removeItem(SESSION_KEY);
  } catch { /* storage unavailable: session lasts for this page only */ }
}

export class ApiError extends Error {
  constructor(message, status, problems = []) {
    super(message);
    this.status = status;
    this.problems = problems;
  }
}

async function handle(res) {
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!res.ok) throw new ApiError(body?.error || `Request failed (${res.status})`, res.status, body?.problems || []);
  return body;
}

export async function get(path, params = {}) {
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.set(k, v); });
  const url = qs.toString() ? `${path}?${qs}` : path;
  return handle(await fetch(url));
}

export async function upload(path, files) {
  const form = new FormData();
  Object.entries(files).forEach(([k, f]) => { if (f) form.append(k, f); });
  return handle(await fetch(path, { method: 'POST', body: form }));
}

export const api = {
  status: () => get('/api/status'),
  models: () => get('/api/models'),
  whatIf: () => get('/api/whatif'),
  session: (id) => get(`/api/sessions/${id}`),
  createSession: (files) => upload('/api/sessions', files),
  addDisputes: (id, file) => upload(`/api/sessions/${id}/disputes`, { disputes: file }),
  payments: (id, params) => get(`/api/sessions/${id}/payments`, params),
  payment: (id, paymentId) => get(`/api/sessions/${id}/payments/${encodeURIComponent(paymentId)}`),
  disputes: (id, params) => get(`/api/sessions/${id}/disputes`, params),
  teams: (session) => get('/api/teams', { session }),
  team: (teamId, session) => get(`/api/teams/${encodeURIComponent(teamId)}`, { session }),
  portfolio: (session) => get('/api/portfolio', { session }),
};

export const exportUrls = (id) => ({
  payments: id ? `/api/sessions/${id}/export/payment_predictions.csv` : null,
  disputes: id ? `/api/sessions/${id}/export/dispute_predictions.csv` : null,
  whatif: '/api/export/whatif_results.csv',
  invoiceRules: id ? `/api/sessions/${id}/export/invoice_win_rules.csv` : null,
  paymentRules: id ? `/api/sessions/${id}/export/payment_win_rules.csv` : null,
  disputeRules: id ? `/api/sessions/${id}/export/dispute_win_rules.csv` : null,
});
