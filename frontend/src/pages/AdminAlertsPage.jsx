import { useCallback, useEffect, useState } from 'react';
import * as alertsApi from '../api/alerts';
import PageHeader from '../components/PageHeader';
import { Button, Card, ErrorBanner, Spinner } from '../components/ui';

const SEVERITY_STYLES = {
  CRITICAL: 'bg-danger-bg text-danger-ink',
  HIGH: 'bg-danger-bg text-danger-ink',
  MEDIUM: 'bg-amber-100 text-amber-800',
  LOW: 'bg-pill text-accent',
  INFO: 'bg-neutral-100 text-muted',
};

const STATUS_LABELS = {
  SENT: 'Delivered',
  PENDING: 'Delivering…',
  FAILED: 'Delivery failed',
};

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** Seconds from detection to delivery — the "< 5 minutes" evidence (gate D1). */
function deliveryLatency(alert) {
  if (!alert.createdAt || !alert.deliveredAt) return null;
  const ms = new Date(alert.deliveredAt).getTime() - new Date(alert.createdAt).getTime();
  if (Number.isNaN(ms)) return null;
  return ms < 1000 ? '<1s' : `${Math.round(ms / 1000)}s`;
}

function SeverityPill({ severity }) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${
        SEVERITY_STYLES[severity] || 'bg-neutral-100 text-muted'
      }`}
    >
      {severity}
    </span>
  );
}

/** The ws18 alert panel: the bank's recent security alerts with delivery evidence. */
export function AlertsPanel({ onError }) {
  const [alerts, setAlerts] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    alertsApi
      .listAlerts()
      .then(setAlerts)
      .catch((err) => onError(err.message))
      .finally(() => setLoading(false));
  }, [onError]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-normal">Recent security alerts</h2>
        <Button variant="secondary" onClick={load} disabled={loading}>
          {loading ? 'Refreshing…' : 'Refresh'}
        </Button>
      </div>
      <p className="mb-4 text-sm text-muted">
        Raised from the audit trail within seconds (lockouts, token reuse, blocked exports, SoD
        violations) or by the minute sweep (failed-login and admin-action bursts), and delivered
        to the configured channels. Thresholds: docs/security/alerting-and-export-caps.md.
      </p>
      {!alerts && <Spinner label="Loading alerts…" />}
      {alerts && alerts.length === 0 && (
        <p className="text-sm text-muted">No alerts have been raised for this bank.</p>
      )}
      {alerts && alerts.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th className="px-2 py-1.5">Raised</th>
                <th className="px-2 py-1.5">Severity</th>
                <th className="px-2 py-1.5">Rule</th>
                <th className="px-2 py-1.5">Summary</th>
                <th className="px-2 py-1.5">Delivery</th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((alert) => (
                <tr key={alert.alertId} className="border-b border-line/60 align-top last:border-0">
                  <td className="whitespace-nowrap px-2 py-1.5">{formatDate(alert.createdAt)}</td>
                  <td className="px-2 py-1.5">
                    <SeverityPill severity={alert.severity} />
                  </td>
                  <td className="whitespace-nowrap px-2 py-1.5 font-mono text-xs">{alert.rule}</td>
                  <td className="px-2 py-1.5">
                    {alert.summary}
                    {alert.eventCount > 1 && (
                      <span className="ml-1 text-muted">({alert.eventCount} events)</span>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-2 py-1.5 text-muted">
                    {STATUS_LABELS[alert.status] || alert.status}
                    {alert.channels && <span className="block text-xs">via {alert.channels}</span>}
                    {deliveryLatency(alert) && (
                      <span className="block text-xs">in {deliveryLatency(alert)}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/**
 * ws18 security alerts (gate D1): what the alert framework raised for this
 * bank and whether it reached a person. The server enforces user:admin and
 * scopes the list to the caller's bank; this page is rendered for ADMINs.
 */
export default function AdminAlertsPage() {
  const [error, setError] = useState('');

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Security alerts' }]}
        title="Security alerts"
        subtitle="Alerts raised for your bank and their delivery evidence"
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <AlertsPanel onError={setError} />
    </div>
  );
}
