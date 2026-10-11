import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AUDIT_OUTCOMES,
  exportAuditCsv,
  getPlatformAuditCounts,
  listAuditActions,
  listAuditEvents,
} from '../api/audit';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import Modal from '../components/Modal';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';

/** Who holds audit:read (contracts §2); hiding the page is UX, the server enforces it. */
const ROLES = {
  bank: ['ADMIN', 'APPROVER', 'PLATFORM_ADMIN'],
  platform: ['PLATFORM_ADMIN'],
};

const EMPTY_FILTERS = {
  actor: '',
  actions: [],
  entityType: '',
  entityId: '',
  outcome: '',
  from: '',
  to: '',
};

const OUTCOME_TONES = { SUCCESS: 'success', DENIED: 'danger', FAILED: 'warning' };

/**
 * The filter form's dates are calendar days in the viewer's time zone; the
 * API takes ISO-8601 instants — from the start of `from` to the end of `to`.
 */
export function toApiFilters(filters) {
  const instant = (day, time) => {
    if (!day) return undefined;
    const date = new Date(`${day}T${time}`);
    return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
  };
  return {
    ...filters,
    from: instant(filters.from, '00:00:00'),
    to: instant(filters.to, '23:59:59.999'),
  };
}

function formatWhen(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** Where an audited entity can be opened, when the app has a page for it. */
export function entityLink(entityType, entityId) {
  if (!entityType) return null;
  const id = entityId == null || entityId === '' ? null : encodeURIComponent(entityId);
  const withId = (base) => (id ? `${base}/${id}` : null);
  switch (entityType) {
    case 'IngestionJob':
      return withId('/ingestion');
    case 'Template':
      return withId('/templates');
    case 'DocInstance':
      return withId('/creation/documents');
    case 'DocBlueprint':
    case 'Blueprint':
      return withId('/creation/blueprints');
    case 'ExtractionJob':
      return withId('/extraction/jobs');
    case 'IntegrationRun':
      return withId('/admin/integrations/runs');
    case 'IntegrationFlow':
      return withId('/admin/integrations/flows');
    case 'Bank':
      return withId('/platform/banks');
    case 'PendingChange':
      return '/admin/changes';
    case 'AppUser':
      return '/admin/users';
    case 'AlertLog':
      return '/admin/alerts';
    default:
      return null;
  }
}

/** Values arrive as objects, or as JSON text from older rows. */
function parseValues(value) {
  if (value == null || value === '') return null;
  if (typeof value === 'string') {
    try {
      return JSON.parse(value);
    } catch {
      return value;
    }
  }
  return value;
}

const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function showValue(value) {
  if (value === undefined) return '—';
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}

/**
 * old → new as one row per field: Added, Removed, Changed or Unchanged.
 * A non-object value (a bare string, a list) is compared as one "value" row.
 */
export function diffValues(oldValues, newValues) {
  const before = parseValues(oldValues);
  const after = parseValues(newValues);
  if (before == null && after == null) return [];
  const a = isRecord(before) ? before : before == null ? {} : { value: before };
  const b = isRecord(after) ? after : after == null ? {} : { value: after };
  const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])].sort();
  return keys.map((field) => {
    const inA = Object.prototype.hasOwnProperty.call(a, field);
    const inB = Object.prototype.hasOwnProperty.call(b, field);
    let change = 'Unchanged';
    if (!inA) change = 'Added';
    else if (!inB) change = 'Removed';
    else if (JSON.stringify(a[field]) !== JSON.stringify(b[field])) change = 'Changed';
    return { field, before: inA ? a[field] : undefined, after: inB ? b[field] : undefined, change };
  });
}

const CHANGE_STYLES = {
  Added: 'bg-success-bg/60',
  Removed: 'bg-danger-bg/60',
  Changed: 'bg-amber-50',
  Unchanged: 'text-muted',
};

function AuditDiff({ event }) {
  const rows = diffValues(event.oldValues, event.newValues);
  if (rows.length === 0) {
    return <p className="text-muted">No old or new values were recorded for this event.</p>;
  }
  return (
    <div className="overflow-x-auto rounded border border-line">
      <table className="w-full border-collapse text-[13px]" data-testid="audit-diff">
        <caption className="sr-only">Old and new values</caption>
        <thead>
          <tr className="bg-sidebar text-left text-muted">
            <th scope="col" className="px-3 py-1.5 font-medium">Field</th>
            <th scope="col" className="px-3 py-1.5 font-medium">Old value</th>
            <th scope="col" className="px-3 py-1.5 font-medium">New value</th>
            <th scope="col" className="px-3 py-1.5 font-medium">Change</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.field} className={`border-t border-line ${CHANGE_STYLES[row.change]}`}>
              <th scope="row" className="px-3 py-1.5 text-left font-mono text-xs font-normal">
                {row.field}
              </th>
              <td className="px-3 py-1.5 font-mono text-xs break-all">
                {row.change === 'Changed' ? <del>{showValue(row.before)}</del> : showValue(row.before)}
              </td>
              <td className="px-3 py-1.5 font-mono text-xs break-all">
                {row.change === 'Changed' ? <ins className="no-underline">{showValue(row.after)}</ins> : showValue(row.after)}
              </td>
              <td className="px-3 py-1.5 text-xs">{row.change}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EventDetails({ event, onClose }) {
  const link = event ? entityLink(event.entityType, event.entityId) : null;
  return (
    <Modal
      open={Boolean(event)}
      onClose={onClose}
      size="lg"
      title={event ? `Audit event #${event.id}` : ''}
      description={event ? `${event.action} · ${formatWhen(event.occurredAt)}` : undefined}
      footer={
        <Button variant="secondary" onClick={onClose}>
          Close
        </Button>
      }
    >
      {event && (
        <div className="space-y-4">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt className="text-muted">Outcome</dt>
            <dd>{event.outcome || '—'}</dd>
            <dt className="text-muted">Actor</dt>
            <dd>
              {event.actorEmail || (event.actorUserId != null ? `user #${event.actorUserId}` : 'system')}
              {event.actorRole && <span className="text-muted"> ({event.actorRole})</span>}
            </dd>
            <dt className="text-muted">Entity</dt>
            <dd>
              {event.entityType ? `${event.entityType}${event.entityId != null ? ` #${event.entityId}` : ''}` : '—'}
              {link && (
                <>
                  {' · '}
                  <Link to={link} className="text-accent hover:underline">
                    Open {event.entityType}
                  </Link>
                </>
              )}
            </dd>
            <dt className="text-muted">IP address</dt>
            <dd className="font-mono text-xs leading-5">{event.ip || '—'}</dd>
            <dt className="text-muted">Request</dt>
            <dd className="font-mono text-xs leading-5 break-all">{event.requestId || '—'}</dd>
          </dl>
          <section aria-labelledby="audit-diff-heading">
            <h3 id="audit-diff-heading" className="mb-2 text-sm font-medium">
              What changed
            </h3>
            <AuditDiff event={event} />
          </section>
        </div>
      )}
    </Modal>
  );
}

function groupActions(actions) {
  const groups = new Map();
  for (const action of actions) {
    const category = action.category || 'Other';
    if (!groups.has(category)) groups.set(category, []);
    groups.get(category).push(action.name);
  }
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
}

function Filters({ value, onApply, onClear, actions, actionsFailed, busy }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const set = (key) => (e) => setDraft((d) => ({ ...d, [key]: e.target.value }));
  const grouped = useMemo(() => groupActions(actions || []), [actions]);

  return (
    <Card padding="compact" className="mb-4">
      <form
        aria-label="Audit filters"
        onSubmit={(e) => {
          e.preventDefault();
          onApply(draft);
        }}
        className="space-y-3"
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Actor (email or user id)">
            <input className={inputClass} value={draft.actor} onChange={set('actor')} autoComplete="off" />
          </Field>
          <Field label="Entity type">
            <input
              className={inputClass}
              value={draft.entityType}
              onChange={set('entityType')}
              placeholder="e.g. IngestionJob"
              spellCheck={false}
            />
          </Field>
          <Field label="Entity id">
            <input className={inputClass} value={draft.entityId} onChange={set('entityId')} spellCheck={false} />
          </Field>
          <Field label="Outcome">
            <select className={inputClass} value={draft.outcome} onChange={set('outcome')}>
              <option value="">Any outcome</option>
              {AUDIT_OUTCOMES.map((o) => (
                <option key={o} value={o}>
                  {o}
                </option>
              ))}
            </select>
          </Field>
          <Field label="From">
            <input type="date" className={inputClass} value={draft.from} onChange={set('from')} />
          </Field>
          <Field label="To">
            <input type="date" className={inputClass} value={draft.to} onChange={set('to')} />
          </Field>
        </div>
        <Field label="Actions">
          <select
            multiple
            size={6}
            className={inputClass}
            value={draft.actions}
            onChange={(e) =>
              setDraft((d) => ({
                ...d,
                actions: Array.from(e.target.selectedOptions, (option) => option.value),
              }))
            }
          >
            {grouped.map(([category, names]) => (
              <optgroup key={category} label={category}>
                {names.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </Field>
        <p className="text-xs text-muted">
          {actionsFailed
            ? 'The action list could not be loaded; every action is shown.'
            : 'Ctrl/Cmd-click to pick several actions; none picked shows every action.'}
        </p>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={busy}>
            Apply filters
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              setDraft(EMPTY_FILTERS);
              onClear();
            }}
          >
            Clear filters
          </Button>
        </div>
      </form>
    </Card>
  );
}

function BankCounts({ counts, error }) {
  return (
    <section aria-labelledby="audit-counts-heading" className="mb-6">
      <h2 id="audit-counts-heading" className="mb-2 text-base font-medium">
        Events per bank
      </h2>
      <p className="mb-2 text-xs text-muted">
        Counts only: the platform console never shows another bank&apos;s audit rows.
      </p>
      {error && <p className="text-sm text-danger-ink">Counts could not be loaded: {error}</p>}
      {!counts && !error && <Spinner label="Loading counts…" />}
      {counts && (
        <DataTable
          caption="Audit events per bank"
          rows={counts}
          emptyMessage="No events in this period."
          columns={[
            {
              key: 'code',
              label: 'Bank',
              render: (row) => (
                <Link to={`/platform/banks/${row.bankId}`} className="text-accent hover:underline">
                  {row.code || `bank #${row.bankId}`}
                </Link>
              ),
            },
            { key: 'total', label: 'Events' },
            { key: 'denied', label: 'Denied' },
            { key: 'failed', label: 'Failed' },
          ]}
        />
      )}
    </section>
  );
}

/**
 * `/admin/audit` (mode "bank") and `/platform/audit` (mode "platform"),
 * v1.2.0: the audit trail with filters, keyset "Load more", a detail dialog
 * with the old → new values, CSV export and links to the audited entity.
 * The platform variant lists the platform bank's own events plus per-bank
 * counts (never other banks' rows).
 */
export default function AuditLogPage({ mode = 'bank' }) {
  const { user } = useAuth();
  const toast = useToast();
  const platform = mode === 'platform';
  const allowed = (ROLES[mode] || ROLES.bank).includes(user?.role);

  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [events, setEvents] = useState(null);
  const [nextCursor, setNextCursor] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const [actions, setActions] = useState([]);
  const [actionsFailed, setActionsFailed] = useState(false);
  const [counts, setCounts] = useState(null);
  const [countsError, setCountsError] = useState('');
  const [selected, setSelected] = useState(null);
  const [exporting, setExporting] = useState(false);
  const loadSeq = useRef(0);

  const apiFilters = useMemo(() => toApiFilters(applied), [applied]);
  const scope = platform ? 'platform' : 'bank';

  useEffect(() => {
    if (!allowed) return;
    let active = true;
    listAuditActions()
      .then((answer) => {
        if (active) setActions(Array.isArray(answer?.actions) ? answer.actions : []);
      })
      .catch(() => {
        if (active) setActionsFailed(true);
      });
    return () => {
      active = false;
    };
  }, [allowed]);

  useEffect(() => {
    if (!allowed) return;
    const seq = ++loadSeq.current;
    setEvents(null);
    setNextCursor(null);
    listAuditEvents(apiFilters, { scope })
      .then((page) => {
        if (seq !== loadSeq.current) return;
        setEvents(Array.isArray(page?.events) ? page.events : []);
        setNextCursor(page?.nextCursor || null);
        setError('');
      })
      .catch((err) => {
        if (seq !== loadSeq.current) return;
        setEvents([]);
        setError(err.message);
      });
  }, [allowed, apiFilters, scope]);

  useEffect(() => {
    if (!allowed || !platform) return;
    let active = true;
    setCounts(null);
    setCountsError('');
    getPlatformAuditCounts({ from: apiFilters.from, to: apiFilters.to })
      .then((answer) => {
        if (active) setCounts(Array.isArray(answer?.banks) ? answer.banks : []);
      })
      .catch((err) => {
        if (active) setCountsError(err.message);
      });
    return () => {
      active = false;
    };
  }, [allowed, platform, apiFilters.from, apiFilters.to]);

  const loadMore = useCallback(async () => {
    if (!nextCursor) return;
    const seq = loadSeq.current;
    setLoadingMore(true);
    try {
      const page = await listAuditEvents(apiFilters, { cursor: nextCursor, scope });
      if (seq !== loadSeq.current) return;
      setEvents((current) => [...(current || []), ...(page?.events || [])]);
      setNextCursor(page?.nextCursor || null);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingMore(false);
    }
  }, [apiFilters, nextCursor, scope]);

  const exportCsv = async () => {
    setExporting(true);
    try {
      await toast.promise(() => exportAuditCsv(apiFilters), {
        loading: 'Preparing the audit export…',
        success: 'Audit export downloaded',
        error: (err) => err.message,
      });
    } catch {
      // The error toast is already shown.
    } finally {
      setExporting(false);
    }
  };

  const crumbs = platform
    ? [{ label: 'Platform', to: '/platform' }, { label: 'Audit log' }]
    : [{ label: 'Administration' }, { label: 'Audit log' }];

  if (!allowed) {
    return (
      <div>
        <PageHeader crumbs={crumbs} title="Audit log" />
        <Callout variant="info" title="Not available">
          {platform
            ? 'The platform audit log is for the platform administrators.'
            : "The audit log is for your bank's administrators and approvers."}
        </Callout>
      </div>
    );
  }

  const columns = [
    {
      key: 'occurredAt',
      label: 'When',
      render: (row) => (
        <time dateTime={row.occurredAt} className="whitespace-nowrap">
          {formatWhen(row.occurredAt)}
        </time>
      ),
    },
    { key: 'action', label: 'Action', render: (row) => <span className="font-mono text-xs">{row.action}</span> },
    {
      key: 'outcome',
      label: 'Outcome',
      render: (row) => <Tag tone={OUTCOME_TONES[row.outcome] || 'neutral'}>{row.outcome || '—'}</Tag>,
    },
    {
      key: 'actor',
      label: 'Actor',
      render: (row) => (
        <span>
          {row.actorEmail || (row.actorUserId != null ? `user #${row.actorUserId}` : 'system')}
          {row.actorRole && <span className="block text-xs text-muted">{row.actorRole}</span>}
        </span>
      ),
    },
    {
      key: 'entity',
      label: 'Entity',
      render: (row) => {
        if (!row.entityType) return '—';
        const text = `${row.entityType}${row.entityId != null ? ` #${row.entityId}` : ''}`;
        const link = entityLink(row.entityType, row.entityId);
        return link ? (
          <Link to={link} className="text-accent hover:underline">
            {text}
          </Link>
        ) : (
          text
        );
      },
    },
    { key: 'ip', label: 'IP', render: (row) => <span className="font-mono text-xs">{row.ip || '—'}</span> },
    {
      key: 'details',
      label: '',
      srLabel: 'Details',
      render: (row) => (
        <Button
          variant="secondary"
          size="sm"
          aria-label={`Details of event ${row.id}`}
          onClick={() => setSelected(row)}
        >
          Details
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        crumbs={crumbs}
        title="Audit log"
        subtitle={
          platform
            ? 'The platform bank’s own trail, and event counts per bank'
            : 'Who did what, when — newest first'
        }
        actions={
          <Button variant="secondary" onClick={exportCsv} disabled={exporting}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </Button>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Filters
        value={applied}
        onApply={setApplied}
        onClear={() => setApplied(EMPTY_FILTERS)}
        actions={actions}
        actionsFailed={actionsFailed}
        busy={events === null}
      />

      {platform && <BankCounts counts={counts} error={countsError} />}

      <section aria-labelledby="audit-events-heading">
        <h2 id="audit-events-heading" className="mb-2 text-base font-medium">
          {platform ? 'Platform bank events' : 'Events'}
        </h2>
        {events === null ? (
          <Spinner label="Loading audit events…" />
        ) : (
          <>
            <DataTable
              caption="Audit events"
              columns={columns}
              rows={events}
              emptyMessage="No audit events match these filters."
            />
            <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-sm">
              <span className="text-muted" aria-live="polite">
                {events.length} event{events.length === 1 ? '' : 's'} shown
                {nextCursor ? ' — more are available' : ''}
              </span>
              {nextCursor && (
                <Button variant="secondary" onClick={loadMore} disabled={loadingMore}>
                  {loadingMore ? 'Loading…' : 'Load more'}
                </Button>
              )}
            </div>
          </>
        )}
      </section>

      <EventDetails event={selected} onClose={() => setSelected(null)} />
    </div>
  );
}
