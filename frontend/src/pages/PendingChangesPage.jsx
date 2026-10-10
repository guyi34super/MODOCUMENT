import { useCallback, useEffect, useState } from 'react';
import * as changesApi from '../api/changes';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import { Badge, Button, Card, ErrorBanner, Spinner, inputClass } from '../components/ui';

const STATUSES = ['REQUESTED', 'APPLIED', 'REJECTED', 'CANCELLED', 'FAILED'];

// Every maker/checker change type (ws10 + phase 2, docs/phase2/contracts.md
// §2). Owners of new types send SE3 the label + summary line; payload
// fields marked "display" below (targetEmail, bankCode, flowName, host...)
// are optional extras an applier may store for the checker.
const TYPE_LABELS = {
  USER_CREATE: 'Create user',
  USER_ROLE_CHANGE: 'Change role',
  USER_DISABLE: 'Disable user',
  TEMPLATE_UPDATE: 'Update template',
  TEMPLATE_DELETE: 'Delete template',
  USER_PASSWORD_RESET: 'Reset password',
  USER_MFA_RESET: 'Reset MFA',
  SETTING_UPDATE: 'Change settings',
  PLATFORM_SETTING_UPDATE: 'Change platform settings',
  BANK_CREATE: 'Onboard bank',
  BANK_SUSPEND: 'Suspend bank',
  BANK_ADMIN_ADD: 'Add bank administrator',
  INTEGRATION_CONNECTION_CHANGE: 'Change integration connection',
  INTEGRATION_FLOW_ENABLE: 'Enable integration flow',
  API_CLIENT_CREATE: 'Create API client',
  AGENT_REGISTER: 'Register connector agent',
  EGRESS_RULE_APPROVE: 'Approve egress destination',
};

/** ws23 template extraction modes (contracts §4 ExtractionMode). */
const EXTRACTION_MODE_LABELS = {
  AI_WITH_CODE_FALLBACK: 'AI with code fallback',
  CODE_ONLY: 'code only',
  AI_ONLY: 'AI only',
};

/** Roles allowed to decide (change:approve, see role-permission-matrix.csv). */
const DECIDER_ROLES = ['ADMIN', 'APPROVER', 'PLATFORM_ADMIN'];

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** A setting value for a diff line; null means "back to the inherited default". */
function formatValue(value) {
  if (value === null || value === undefined) return 'default';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

const userOf = (p) => p.targetEmail || p.email || `user ${p.userId ?? p.targetUserId}`;
const bankOf = (p) => p.bankCode || p.bankName || `bank ${p.bankId}`;
const withReason = (text, reason) => (reason ? `${text} — ${reason}` : text);
const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;

/**
 * The `key: from → to` lines of a settings change (SETTING_UPDATE and
 * PLATFORM_SETTING_UPDATE, which may also move the bank bounds).
 */
function settingDiffs(change) {
  const changes = Array.isArray(change.payload?.changes) ? change.payload.changes : [];
  return changes.map((c) => {
    let line = `${c.key}: ${formatValue(c.from)} → ${formatValue(c.to)}`;
    if (c.bankMin !== undefined || c.bankMax !== undefined) {
      line += ` (bank range ${formatValue(c.bankMin)}–${formatValue(c.bankMax)})`;
    }
    return line;
  });
}

/** One human line per change: what would happen if this were approved. */
function summarize(change) {
  const p = change.payload || {};
  switch (change.changeType) {
    case 'USER_CREATE':
      return `Create ${p.email || '?'} as ${p.role || '?'}`;
    case 'USER_ROLE_CHANGE':
      return `Change ${p.targetEmail || `user ${p.targetUserId}`} to ${p.role || '?'}`;
    case 'USER_DISABLE':
      return `Disable ${p.targetEmail || `user ${p.targetUserId}`}`;
    case 'TEMPLATE_UPDATE': {
      const rename = `Rename template ${p.templateId} to "${p.name}"`;
      // ws23: the key is present only when the mode changes (null = bank default).
      if (!Object.prototype.hasOwnProperty.call(p, 'extractionMode')) return rename;
      const mode =
        p.extractionMode === null
          ? 'the bank default'
          : EXTRACTION_MODE_LABELS[p.extractionMode] || p.extractionMode;
      return `${rename}; extraction mode → ${mode}`;
    }
    case 'TEMPLATE_DELETE':
      return `Delete template ${p.name ? `"${p.name}"` : p.templateId}`;
    case 'USER_PASSWORD_RESET':
      return `Password reset for ${userOf(p)}`;
    case 'USER_MFA_RESET':
      return withReason(`MFA reset for ${userOf(p)}`, p.reason);
    case 'SETTING_UPDATE': {
      const count = Array.isArray(p.changes) ? p.changes.length : 0;
      return `Change ${plural(count, 'setting')}`;
    }
    case 'PLATFORM_SETTING_UPDATE': {
      const count = Array.isArray(p.changes) ? p.changes.length : 0;
      // A bankId: that bank's own value of platform-set settings.
      if (p.bankId !== undefined && p.bankId !== null) {
        return `Change ${plural(count, 'platform-set setting')} of ${bankOf(p)}`;
      }
      return `Change ${plural(count, 'platform setting')}`;
    }
    case 'BANK_CREATE': {
      const users = Array.isArray(p.users) ? p.users : [];
      const admins = users.filter((u) => u.role === 'ADMIN').length;
      return (
        `Onboard bank ${p.code || '?'}${p.name ? ` "${p.name}"` : ''} with ` +
        `${plural(users.length, 'user')} (${plural(admins, 'administrator')})`
      );
    }
    case 'BANK_SUSPEND':
      return withReason(`Suspend ${bankOf(p)}`, p.reason);
    case 'BANK_ADMIN_ADD':
      return withReason(
        `Add ${p.email || '?'} as administrator of ${bankOf(p)}`,
        p.reason
      );
    case 'INTEGRATION_CONNECTION_CHANGE': {
      // The payload carries the sealed secret (never shown) and the
      // non-secret config: name the destination the checker approves.
      const credentials = p.secretCiphertext || p.secretRef ? ', new credentials' : '';
      const c = p.config || {};
      const where = c.baseUrl || c.url || (c.host ? `${c.host}${c.port ? `:${c.port}` : ''}` : '');
      return (
        `${p.connectionId ? 'Change' : 'Create'} connection "${p.name || '?'}" ` +
        `(${[p.type, p.mode].filter(Boolean).join(', ')}${credentials})` +
        `${where ? ` to ${where}` : ''}`
      );
    }
    case 'INTEGRATION_FLOW_ENABLE':
      return (
        `Enable flow ${p.flowName ? `"${p.flowName}"` : `#${p.flowId}`}` +
        `${p.flowVersion !== undefined ? ` (version ${p.flowVersion})` : ''}`
      );
    case 'API_CLIENT_CREATE': {
      const scopes = Array.isArray(p.scopes) ? p.scopes.join(', ') : p.scopes;
      const cidrs = Array.isArray(p.allowedCidrs) ? p.allowedCidrs : [];
      return (
        `Create API client "${p.name || '?'}" (${p.authMethod || '?'})` +
        `${scopes ? ` with scopes ${scopes}` : ''}` +
        `${cidrs.length ? `, from ${cidrs.join(', ')}` : ''}` +
        `${p.expiresAt ? `, expires ${formatDate(p.expiresAt)}` : ''}`
      );
    }
    case 'AGENT_REGISTER':
      return `Register connector agent "${p.name || '?'}"`;
    case 'EGRESS_RULE_APPROVE':
      return p.host || p.cidr
        ? `Allow egress to ${p.host ?? p.cidr}${p.port ? `:${p.port}` : ''}${p.bankCode ? ` for ${p.bankCode}` : ''}`
        : `Approve egress rule #${p.ruleId}`;
    default:
      return change.changeType;
  }
}

/**
 * The maker/checker approval queue (ws10, SEC-023). The server enforces
 * everything — this page renders the caller's bank's queue: holders of
 * change:approve (ADMIN, APPROVER) decide with a comment; a maker sees their
 * own submissions and can cancel undecided ones. Approving your own change
 * is a hard 409 server-side; the error simply surfaces here.
 */
export default function PendingChangesPage() {
  const toast = useToast();
  const { user: me } = useAuth();
  const canDecide = !!me && DECIDER_ROLES.includes(me.role);

  const [status, setStatus] = useState('REQUESTED');
  const [changes, setChanges] = useState(null);
  const [error, setError] = useState('');
  const [comments, setComments] = useState({});

  const load = useCallback(() => {
    setChanges(null);
    changesApi
      .listChanges(status)
      .then(setChanges)
      .catch((err) => setError(err.message));
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const commentFor = (id) => comments[id] || '';

  const decide = async (change, decision) => {
    const call =
      decision === 'approve'
        ? changesApi.approveChange(change.pendingChangeId, commentFor(change.pendingChangeId))
        : changesApi.rejectChange(change.pendingChangeId, commentFor(change.pendingChangeId));
    try {
      await toast.promise(call, {
        loading: decision === 'approve' ? 'Approving…' : 'Rejecting…',
        success:
          decision === 'approve'
            ? `Change #${change.pendingChangeId} approved and applied`
            : `Change #${change.pendingChangeId} rejected`,
      });
      load();
    } catch {
      // The error toast is already shown (self-approval answers 409 here).
    }
  };

  const cancel = async (change) => {
    try {
      await toast.promise(changesApi.cancelChange(change.pendingChangeId), {
        loading: 'Cancelling…',
        success: `Change #${change.pendingChangeId} cancelled`,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Approvals' }]}
        title="Approvals"
        subtitle="Critical changes need a second pair of eyes — you cannot approve your own"
        actions={
          <>
            <select
              aria-label="Status filter"
              className={`${inputClass} w-auto`}
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={load}>
              Refresh
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!changes && !error && <Spinner label="Loading changes…" />}
      {changes && changes.length === 0 && (
        <Card>
          <p className="text-sm text-muted">No {status.toLowerCase()} changes.</p>
        </Card>
      )}
      <div className="space-y-3">
        {changes &&
          changes.map((change) => {
            const mine = me && change.makerUserId === me.userId;
            const open = change.status === 'REQUESTED';
            return (
              <Card key={change.pendingChangeId}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-medium text-ink">
                      #{change.pendingChangeId} ·{' '}
                      {TYPE_LABELS[change.changeType] || change.changeType}
                      {mine && <span className="ml-1 text-xs text-muted">(yours)</span>}
                    </p>
                    <p className="mt-0.5 text-sm text-ink">{summarize(change)}</p>
                    {(change.changeType === 'SETTING_UPDATE' ||
                      change.changeType === 'PLATFORM_SETTING_UPDATE') && (
                      <ul
                        className="mt-1 list-disc pl-5 font-mono text-xs text-ink"
                        aria-label={`Changes in #${change.pendingChangeId}`}
                      >
                        {settingDiffs(change).map((line) => (
                          <li key={line}>{line}</li>
                        ))}
                      </ul>
                    )}
                    <p className="mt-1 text-xs text-muted">
                      Requested by {change.makerEmail || `user ${change.makerUserId}`} ·{' '}
                      {formatDate(change.requestedAt)}
                      {change.makerComment && ` · “${change.makerComment}”`}
                    </p>
                    {change.checkerUserId && (
                      <p className="mt-0.5 text-xs text-muted">
                        Decided by {change.checkerEmail || `user ${change.checkerUserId}`} ·{' '}
                        {formatDate(change.decidedAt)}
                        {change.checkerComment && ` · “${change.checkerComment}”`}
                      </p>
                    )}
                  </div>
                  <Badge status={change.status} />
                </div>

                {open && (canDecide || mine) && (
                  <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line/60 pt-3">
                    {canDecide && (
                      <>
                        <input
                          aria-label={`Comment for change ${change.pendingChangeId}`}
                          className={`${inputClass} max-w-72`}
                          placeholder="Decision comment (optional)"
                          value={commentFor(change.pendingChangeId)}
                          onChange={(e) =>
                            setComments({
                              ...comments,
                              [change.pendingChangeId]: e.target.value,
                            })
                          }
                        />
                        <Button size="sm" onClick={() => decide(change, 'approve')}>
                          Approve
                        </Button>
                        <Button
                          size="sm"
                          variant="danger"
                          onClick={() => decide(change, 'reject')}
                        >
                          Reject
                        </Button>
                      </>
                    )}
                    {mine && (
                      <Button size="sm" variant="secondary" onClick={() => cancel(change)}>
                        Cancel request
                      </Button>
                    )}
                  </div>
                )}
              </Card>
            );
          })}
      </div>
    </div>
  );
}
