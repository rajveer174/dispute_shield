export const usd = (v, digits = 0) =>
  v === null || v === undefined || Number.isNaN(v)
    ? '–'
    : v.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: digits, minimumFractionDigits: digits });

export const signedUsd = (v) => (v > 0 ? '+' : '') + usd(v);

export const pct = (v, digits = 0) =>
  v === null || v === undefined || Number.isNaN(v) ? '–' : `${(v * 100).toFixed(digits)}%`;

export const num = (v, digits = 0) =>
  v === null || v === undefined || Number.isNaN(v)
    ? '–'
    : v.toLocaleString('en-US', { maximumFractionDigits: digits, minimumFractionDigits: digits });

export const signed = (v, digits = 0) => (v > 0 ? '+' : '') + num(v, digits);

export const date = (iso) => (iso ? new Date(iso).toISOString().slice(0, 10) : '–');

export const label = (s) => (s ? s.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase()) : '–');
