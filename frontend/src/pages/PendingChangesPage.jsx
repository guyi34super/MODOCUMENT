import { useCallback, useEffect, useState } from 'react';
import * as changesApi from '../api/changes';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import { Badge, Button, Card, ErrorBanner, Spinner, inputClass } from '../components/ui';

const STATUSES = ['REQUESTED', 'APPLIED', 'REJECTED', 'CANCELLED', 'FAILED'];

const TYPE_LABELS = {
  USER_CREATE: 'Create user',
  USER_ROLE_CHANGE: 'Change role',
  USER_DISABLE: 'Disable user',
  TEMPLATE_UPDATE: 'Update template',
  TEMPLATE_DELETE: 'Delete template',
};

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
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
    case 'TEMPLATE_UPDATE':
      return `Rename template ${p.templateId} to "${p.name}"`;
    case 'TEMPLATE_DELETE':
      return `Delete template ${p.name ? `"${p.name}"` : p.templateId}`;
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
  const canDecide = me && (me.role === 'ADMIN' || me.role === 'APPROVER');

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
