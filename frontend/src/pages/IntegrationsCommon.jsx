import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Button, Callout, Tag } from '../components/ui';

// Shared bits of the integration hub pages (ws25/ws26). Vocabularies follow
// docs/phase2/contracts.md §4 (stored by NAME on the server).

/** INTEGRATION_ADMIN is held by ADMIN only (contracts §1). */
export const canManageIntegrations = (role) => role === 'ADMIN';

/** INTEGRATION_RUN: ADMIN and READ_WRITE — run history, run now, retry. */
export const canRunIntegrations = (role) => role === 'ADMIN' || role === 'READ_WRITE';

export const CONNECTION_TYPES = [
  { value: 'REST_API', label: 'REST API', hint: 'Pull from or push to an HTTPS API' },
  { value: 'DATABASE', label: 'Database', hint: 'Read a view or write a staging table' },
  { value: 'SFTP', label: 'SFTP', hint: 'Pick up or drop files in a folder' },
  { value: 'WEBHOOK', label: 'Webhook', hint: 'Send signed event notifications' },
];

export const CONNECTION_TYPE_LABELS = Object.fromEntries(
  CONNECTION_TYPES.map((t) => [t.value, t.label])
);

// Shared with the ingestion database export (utils/dbVendors.js).
export { DB_VENDORS, SSL_MODES } from '../utils/dbVendors';

/** OutboundAuthType offered for a REST API connection. */
export const REST_AUTH_TYPES = [
  { value: 'NONE', label: 'None' },
  { value: 'BASIC', label: 'Basic (username + password)' },
  { value: 'BEARER', label: 'Bearer token' },
  { value: 'API_KEY_HEADER', label: 'API key header' },
  { value: 'OAUTH2_CLIENT_CREDENTIALS', label: 'OAuth2 client credentials' },
  { value: 'MTLS', label: 'Mutual TLS (client certificate)' },
];

export const FLOW_EVENTS = [
  { value: 'INGESTION_VALIDATED', label: 'Upload validated' },
  { value: 'INGESTION_FAILED', label: 'Upload failed' },
  { value: 'DOC_RELEASED', label: 'Document released' },
  { value: 'EXTRACTION_COMPLETED', label: 'Extraction completed' },
];

export const PAYLOAD_KINDS = [
  { value: 'VALIDATED_RECORDS', label: 'Validated records' },
  { value: 'REJECTED_ROWS', label: 'Rejected rows' },
  { value: 'JOB_STATUS', label: 'Job status & errors' },
  { value: 'RELEASED_DOCUMENTS', label: 'Released documents' },
  { value: 'EVENTS', label: 'Event feed' },
];

export const PAYLOAD_FORMATS = ['JSON', 'CSV', 'XLSX', 'PDF'];

export const API_AUTH_METHODS = [
  {
    value: 'OAUTH2_CLIENT_CREDENTIALS',
    label: 'OAuth2 client credentials',
    hint: 'Client id + secret exchanged for a short-lived token',
  },
  { value: 'API_KEY', label: 'API key', hint: 'Authorization: ApiKey <prefix>.<secret>' },
  { value: 'MTLS', label: 'mTLS', hint: 'Client certificate verified at the load balancer' },
];

export const API_SCOPES = [
  { value: 'INTEGRATION_INGEST', label: 'Ingest', hint: 'Send records and files' },
  { value: 'INTEGRATION_RESULTS', label: 'Results', hint: 'Read job status, results and errors' },
  { value: 'INTEGRATION_DOCS', label: 'Documents', hint: 'Read released documents' },
  { value: 'INTEGRATION_EVENTS', label: 'Events', hint: 'Read the event feed' },
];

export function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

const STATUS_TONES = {
  OK: 'success',
  ONLINE: 'success',
  ACTIVE: 'success',
  APPROVED: 'success',
  SUCCEEDED: 'success',
  DELIVERED: 'success',
  ENABLED: 'success',
  UNTESTED: 'neutral',
  QUEUED: 'neutral',
  PENDING: 'warning',
  PENDING_ENROLMENT: 'warning',
  AWAITING_AGENT: 'info',
  RUNNING: 'info',
  RUNNING_AGENT: 'info',
  OFFLINE: 'danger',
  FAILED: 'danger',
  DEAD: 'danger',
  DISABLED: 'neutral',
  REVOKED: 'neutral',
  EXPIRED: 'neutral',
  CANCELLED: 'neutral',
};

const STATUS_LABELS = {
  PENDING_ENROLMENT: 'Pending enrolment',
  AWAITING_AGENT: 'Awaiting agent',
  RUNNING_AGENT: 'Running on agent',
};

/** A status chip for connections, runs, deliveries, agents, clients and rules. */
export function StatusChip({ status }) {
  if (!status) return <Tag>—</Tag>;
  const label =
    STATUS_LABELS[status] || status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ');
  return <Tag tone={STATUS_TONES[status] || 'neutral'}>{label}</Tag>;
}

/** "Pending approval" marker for a row with a change awaiting the checker. */
export function PendingTag({ changeId }) {
  return (
    <Tag tone="warning" title={changeId ? `Change #${changeId} awaits a second administrator` : undefined}>
      Pending approval
    </Tag>
  );
}

/**
 * Renders its children only for a bank administrator (INTEGRATION_ADMIN);
 * everyone else gets an explanation instead of a page of 403s. UX only —
 * the server enforces the permission regardless.
 */
export function RequireIntegrationAdmin({ children, allowRun = false }) {
  const { user } = useAuth();
  const allowed = allowRun ? canRunIntegrations(user?.role) : canManageIntegrations(user?.role);
  if (allowed) return children;
  return (
    <Callout variant="info" title="Administrators only">
      Integrations are managed by your bank's administrators.
    </Callout>
  );
}

/** Callout after a 202: what was sent and where the checker decides it. */
export function SentForApproval({ what, change, onDismiss }) {
  if (!change) return null;
  return (
    <div className="mb-4">
      <Callout variant="success" title="Sent for approval">
        {what} is change #{change.pendingChangeId}. A second administrator must approve it under{' '}
        <Link to="/admin/changes" className="underline">
          Approvals
        </Link>
        .
        {onDismiss && (
          <button type="button" onClick={onDismiss} className="ml-2 cursor-pointer underline">
            Dismiss
          </button>
        )}
      </Callout>
    </div>
  );
}

function remainingSeconds(expiresAt, now) {
  if (!expiresAt) return null;
  const end = new Date(expiresAt).getTime();
  if (Number.isNaN(end)) return null;
  return Math.max(0, Math.floor((end - now) / 1000));
}

function formatCountdown(seconds) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * A one-time secret (API-client secret, agent enrolment token): each value in
 * a read-only field with a Copy button, the "you won't see this again"
 * warning and, with `expiresAt`, a countdown. The values live only in the
 * parent's state, which clears them when the dialog closes.
 *
 * items: [{ label, value, multiline? }]
 */
export function OneTimeSecret({ items, expiresAt, warning }) {
  const [now, setNow] = useState(() => Date.now());
  const [copied, setCopied] = useState(null);

  useEffect(() => {
    if (!expiresAt) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [expiresAt]);

  const left = remainingSeconds(expiresAt, now);

  const copy = async (item) => {
    try {
      await navigator.clipboard.writeText(item.value);
      setCopied(item.label);
    } catch {
      setCopied(null);
    }
  };

  return (
    <div className="space-y-3">
      <Callout variant="error" title="You won't see this again">
        {warning ||
          'Copy it now and store it in your secrets manager. MoDocument keeps only a hash; once you close this dialog it cannot be shown again.'}
      </Callout>
      {items.map((item) => (
        <div key={item.label}>
          <p className="mb-1 font-medium text-ink">{item.label}</p>
          <div className="flex items-start gap-2">
            {item.multiline ? (
              <textarea
                readOnly
                aria-label={item.label}
                rows={4}
                spellCheck={false}
                className="w-full rounded border border-line bg-sidebar px-3 py-1.5 font-mono text-xs text-ink"
                value={item.value}
              />
            ) : (
              <input
                readOnly
                aria-label={item.label}
                spellCheck={false}
                autoComplete="off"
                className="w-full rounded border border-line bg-sidebar px-3 py-1.5 font-mono text-xs text-ink"
                value={item.value}
              />
            )}
            <Button
              type="button"
              size="sm"
              variant="secondary"
              aria-label={`Copy ${item.label.toLowerCase()}`}
              onClick={() => copy(item)}
            >
              {copied === item.label ? 'Copied' : 'Copy'}
            </Button>
          </div>
        </div>
      ))}
      {/* Screen readers hear the copy confirmation (the button keeps its
          aria-label) and the expiry, never the secret itself. */}
      <p aria-live="polite" className="sr-only" data-testid="secret-announcer">
        {left === 0
          ? 'This one-time value has expired.'
          : copied
            ? `${copied} copied to the clipboard.`
            : ''}
      </p>
      {left !== null && (
        <p className="text-xs text-muted">
          {left > 0 ? (
            <>
              Expires in{' '}
              <span role="timer" data-testid="secret-countdown" className="font-mono text-ink">
                {formatCountdown(left)}
              </span>{' '}
              ({formatDateTime(expiresAt)})
            </>
          ) : (
            <span className="text-danger-ink">Expired — request a new one.</span>
          )}
        </p>
      )}
    </div>
  );
}
