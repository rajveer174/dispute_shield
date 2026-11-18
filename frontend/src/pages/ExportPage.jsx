import React from 'react';
import { exportUrls } from '../api.js';
import { useApp } from '../App.jsx';
import { Card } from '../components.jsx';

export default function ExportPage() {
  const { sessionId, session, status } = useApp();
  const urls = exportUrls(sessionId);
  const pub = status?.published || {};
  const blank = Object.entries(pub).filter(([, v]) => !v).map(([k]) => k);

  return (
    <div className="page">
      <h1>Export</h1>
      <div className="grid three">
        <Card title="payment_predictions.csv">
          <p className="muted small">payment_id, invoice_win_prob, payment_win_prob, dispute_risk_tier, dispute_risk_score, expected_loss_usd. One row per uploaded payment.</p>
          {sessionId ? <a className="button primary" href={urls.payments}>Download ({session?.paymentsScored} rows)</a> : <p className="muted">Upload step 1 first.</p>}
        </Card>
        <Card title="dispute_predictions.csv">
          <p className="muted small">dispute_id, win_prob, recommendation. One row per uploaded dispute.</p>
          {session?.disputesScored ? <a className="button primary" href={urls.disputes}>Download ({session.disputesScored} rows)</a> : <p className="muted">Upload step 2 first.</p>}
        </Card>
        <Card title="whatif_results.csv">
          <p className="muted small">policy, metric, estimated_change, low, high. One row per policy and metric, over months 1–22.</p>
          <a className="button primary" href={urls.whatif}>Download</a>
        </Card>
      </div>
      <h2 className="section-title">Three-rule win probability</h2>
      <p className="muted small">Extra files, not part of the brief's formats: each rule as yes/no, how many are met, and the "Dispute win probability" from the rule table.</p>
      <div className="grid three">
        <Card title="invoice_win_rules.csv">
          <p className="muted small">invoice_id, team_id, rule1_signed_estimate, rule2_customer_address, rule3_gps_or_photo, rules_met, Dispute win probability. One row per uploaded invoice that has a payment.</p>
          {sessionId ? <a className="button primary" href={urls.invoiceRules}>Download</a> : <p className="muted">Upload step 1 first.</p>}
        </Card>
        <Card title="payment_win_rules.csv">
          <p className="muted small">payment_id, invoice_id, team_id, rule1_paperwork_3_of_6, rule2_signed_estimate, rule3_card_verified, rules_met, Dispute win probability.</p>
          {sessionId ? <a className="button primary" href={urls.paymentRules}>Download ({session?.paymentsScored} rows)</a> : <p className="muted">Upload step 1 first.</p>}
        </Card>
        <Card title="dispute_win_rules.csv">
          <p className="muted small">dispute_id, payment_id, team_id, reason, rule1_responded, rule2_evidence_4_plus, rule3_not_fraud, rules_met, Dispute win probability.</p>
          {session?.disputesScored ? <a className="button primary" href={urls.disputeRules}>Download ({session.disputesScored} rows)</a> : <p className="muted">Upload step 2 first.</p>}
        </Card>
      </div>
      {blank.length > 0 && (
        <div className="alert warn">These predictions did not beat the validation bar and are exported blank: {blank.join(', ')}.</div>
      )}
    </div>
  );
}
