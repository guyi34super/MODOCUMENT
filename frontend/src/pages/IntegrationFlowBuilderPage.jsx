import { Fragment, useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { isPendingChange } from '../api/changes';
import { getTemplates } from '../api/cache';
import { createFlow, getFlow, listConnections, previewFlow, updateFlow } from '../api/integrations';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, Switch, inputClass } from '../components/ui';
import {
  CONNECTION_TYPE_LABELS,
  FLOW_EVENTS,
  PAYLOAD_FORMATS,
  PAYLOAD_KINDS,
  PendingTag,
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
} from './IntegrationsCommon';
import { toggleFlow } from './IntegrationFlowsPage';

// Spring CronExpression (6 fields: second minute hour day month weekday);
// the scheduler refuses intervals under 5 minutes.
const CRON_PRESETS = [
  { value: 'every5', label: 'Every 5 minutes', cron: '0 */5 * * * *' },
  { value: 'every15', label: 'Every 15 minutes', cron: '0 */15 * * * *' },
  { value: 'every60', label: 'Every hour', cron: '0 0 * * * *' },
  { value: 'daily', label: 'Daily at…' },
  { value: 'custom', label: 'Custom cron expression' },
];

const DAILY = /^0 (\d{1,2}) (\d{1,2}) \* \* \*$/;

/** Which preset (and daily time) a stored cron expression corresponds to. */
export function presetOf(cron) {
  if (!cron) return { preset: 'every15', time: '06:00' };
  const fixed = CRON_PRESETS.find((p) => p.cron === cron);
  if (fixed) return { preset: fixed.value, time: '06:00' };
  const daily = DAILY.exec(cron);
  if (daily) {
    return {
      preset: 'daily',
      time: `${daily[2].padStart(2, '0')}:${daily[1].padStart(2, '0')}`,
    };
  }
  return { preset: 'custom', time: '06:00' };
}

/** The cron expression for a preset (`custom` keeps what was typed). */
export function cronFor(preset, time, custom) {
  if (preset === 'daily') {
    const [hh = '0', mm = '0'] = (time || '00:00').split(':');
    return `0 ${Number(mm)} ${Number(hh)} * * *`;
  }
  if (preset === 'custom') return custom.trim();
  return CRON_PRESETS.find((p) => p.value === preset)?.cron || '';
}

const toList = (value) =>
  (Array.isArray(value) ? value : String(value || '').split(','))
    .map((s) => String(s).trim())
    .filter(Boolean);

const queryToText = (query) =>
  Object.entries(query || {})
    .map(([k, v]) => `${k}=${v}`)
    .join('\n');

const textToQuery = (text) =>
  Object.fromEntries(
    String(text || '')
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.includes('='))
      .map((line) => {
        const at = line.indexOf('=');
        return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
      })
      .filter(([k]) => k)
  );

const EMPTY = {
  name: '',
  direction: 'INBOUND',
  connectionId: '',
  templateId: '',
  trigger: 'MANUAL',
  events: [],
  payloadKinds: ['VALIDATED_RECORDS'],
  format: 'JSON',
  spec: {},
  fieldMapping: [],
  maskPii: true,
  rowCap: '',
};

/** FlowView → editable form (lists as comma text, query as lines). */
function formOf(flow) {
  const spec = { ...(flow.direction === 'OUTBOUND' ? flow.targetSpec : flow.sourceSpec) };
  if (Array.isArray(spec.columns)) spec.columns = spec.columns.join(', ');
  if (Array.isArray(spec.keyColumns)) spec.keyColumns = spec.keyColumns.join(', ');
  if (spec.query) spec.queryText = queryToText(spec.query);
  delete spec.query;
  if (spec.pagination) {
    const { type, ...rest } = spec.pagination;
    spec.paginationType = type;
    Object.entries(rest).forEach(([k, v]) => {
      spec[`pagination.${k}`] = v === null || v === undefined ? '' : String(v);
    });
  }
  delete spec.pagination;
  return {
    name: flow.name || '',
    direction: flow.direction || 'INBOUND',
    connectionId: flow.connectionId ? String(flow.connectionId) : '',
    templateId: flow.templateId ? String(flow.templateId) : '',
    trigger: flow.trigger || 'MANUAL',
    events: flow.events || [],
    payloadKinds: flow.payloadKinds || [],
    format: flow.format || 'JSON',
    spec,
    fieldMapping: (flow.fieldMapping || []).map((m) => ({ from: m.from || '', to: m.to || '' })),
    maskPii: flow.maskPii !== false,
    rowCap: flow.rowCap === null || flow.rowCap === undefined ? '' : String(flow.rowCap),
  };
}

const PAGINATION_FIELDS = {
  PAGE: [
    { key: 'pageParam', label: 'Page parameter', placeholder: 'page' },
    { key: 'sizeParam', label: 'Page-size parameter', placeholder: 'size' },
    { key: 'pageSize', label: 'Page size', placeholder: '100' },
  ],
  CURSOR: [
    { key: 'cursorParam', label: 'Cursor parameter', placeholder: 'cursor' },
    {
      key: 'cursorPath',
      label: 'Next-cursor path in the response',
      placeholder: 'meta.nextCursor',
      // The server-side REST connector takes plain dot paths only (no "$.").
      hint: 'A dot path such as next or meta.nextCursor.',
    },
  ],
  LINK: [],
  NONE: [],
};

/**
 * A JSONPath-style "$.meta.next" as the plain dot path "meta.next" the
 * server-side REST connector accepts (the agent accepts both).
 */
export function plainDotPath(value) {
  return String(value ?? '')
    .trim()
    .replace(/^\$\.?/, '');
}

/** Form spec → the §7.3 sourceSpec / targetSpec for the connection type. */
function buildSpec(type, direction, spec) {
  const s = spec || {};
  const num = (v) => (v === '' || v === undefined || v === null ? null : Number(v));
  if (direction === 'INBOUND') {
    switch (type) {
      case 'DATABASE':
        return {
          table: (s.table || '').trim(),
          columns: toList(s.columns),
          watermarkColumn: (s.watermarkColumn || '').trim() || null,
        };
      case 'REST_API': {
        const pagination = { type: s.paginationType || 'NONE' };
        (PAGINATION_FIELDS[pagination.type] || []).forEach((f) => {
          const value = s[`pagination.${f.key}`];
          if (value !== undefined && String(value).trim() !== '') {
            pagination[f.key] =
              f.key === 'pageSize'
                ? Number(value)
                : f.key === 'cursorPath'
                  ? plainDotPath(value)
                  : String(value).trim();
          }
        });
        return {
          path: (s.path || '').trim(),
          query: textToQuery(s.queryText),
          pagination,
          recordsPath: plainDotPath(s.recordsPath) || null,
        };
      }
      case 'SFTP':
        return {
          folder: (s.folder || '').trim(),
          glob: (s.glob || '').trim() || '*',
          afterRead: s.afterRead || 'MOVE',
        };
      default:
        return null;
    }
  }
  switch (type) {
    case 'DATABASE':
      return {
        table: (s.table || '').trim(),
        mode: s.mode || 'INSERT',
        keyColumns: s.mode === 'UPSERT' ? toList(s.keyColumns) : [],
      };
    case 'REST_API':
      return { method: s.method || 'POST', path: (s.path || '').trim(), batchSize: num(s.batchSize) };
    case 'SFTP':
      return { folder: (s.folder || '').trim(), fileNamePattern: (s.fileNamePattern || '').trim() };
    case 'WEBHOOK':
      return { path: (s.path || '').trim() };
    default:
      return null;
  }
}

/** First problem with the form, or ''. */
function validate(form, connectionType) {
  if (!form.name.trim()) return 'Give the flow a name.';
  if (!form.connectionId) return 'Choose a connection.';
  if (!form.templateId) return 'Choose a template.';
  if (form.trigger === 'SCHEDULE' && !form.cron) return 'Choose a schedule.';
  if (form.trigger === 'SCHEDULE' && form.cron.split(/\s+/).length !== 6)
    return 'A cron expression has six fields: second minute hour day month weekday.';
  if (form.trigger === 'EVENT' && form.events.length === 0) return 'Pick at least one event.';
  if (form.payloadKinds.length === 0) return 'Pick at least one thing to send or receive.';
  if (form.direction === 'INBOUND' && connectionType === 'WEBHOOK')
    return 'A webhook connection can only send (OUTBOUND).';
  const spec = buildSpec(connectionType, form.direction, form.spec) || {};
  if (connectionType === 'DATABASE' && !spec.table) return 'Name the table or view.';
  if (connectionType === 'DATABASE' && spec.mode === 'UPSERT' && spec.keyColumns.length === 0)
    return 'UPSERT needs at least one key column.';
  if (connectionType === 'REST_API' && !spec.path) return 'Enter the API path.';
  if (connectionType === 'SFTP' && !spec.folder) return 'Enter the folder.';
  if (form.rowCap && !/^\d+$/.test(form.rowCap)) return 'The row cap must be a whole number.';
  return '';
}

/**
 * The flow builder (ws25), `/admin/integrations/flows/new` and `/:id`:
 * direction, connection and (bank-shared) template; trigger (manual, a
 * schedule with a cron helper, or events); "what to send / receive"; format;
 * the source/target spec for the connection's type (contracts §7.3 — tables
 * and views by name, never free SQL); the field mapping, prefilled from the
 * dry run's suggestions; PII masking (on by default — turning it off needs
 * approval) and a row cap. "Dry run / preview" saves the draft and shows the
 * first 20 rows without writing anything. Enabling an OUTBOUND flow goes to
 * a second administrator (202); INBOUND enables at once; disabling is
 * immediate.
 */
export default function IntegrationFlowBuilderPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  return (
    <div>
      <PageHeader
        crumbs={[
          { label: 'Integrations', to: '/admin/integrations' },
          { label: 'Flows', to: '/admin/integrations/flows' },
          { label: isNew ? 'New flow' : `Flow #${id}` },
        ]}
        title={isNew ? 'New flow' : 'Edit flow'}
        subtitle="What to receive or send, and when"
      />
      <RequireIntegrationAdmin>
        <FlowBuilder flowId={isNew ? null : id} />
      </RequireIntegrationAdmin>
    </div>
  );
}

function FlowBuilder({ flowId }) {
  const toast = useToast();
  const navigate = useNavigate();
  const ids = { name: useId(), cron: useId(), mask: useId(), time: useId() };

  const [flow, setFlow] = useState(null); // last saved FlowView
  const [form, setForm] = useState(EMPTY);
  const [cronState, setCronState] = useState({ preset: 'every15', time: '06:00', custom: '' });
  const [connections, setConnections] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState(null);
  const [previewing, setPreviewing] = useState(false);
  const [sent, setSent] = useState(null);
  // Keyboard flow through the mapping table: after "Add row" focus lands on
  // the new row's first field; after a removal on the next row's remove
  // button (or "Add row" when none is left), never on <body>.
  const mappingRef = useRef(null);
  const addRowRef = useRef(null);
  const [mappingFocus, setMappingFocus] = useState(null); // { row, field: 'from' | 'remove' }

  useEffect(() => {
    if (!mappingFocus) return;
    const { row, field } = mappingFocus;
    const target =
      field === 'from'
        ? mappingRef.current?.querySelector(`[data-mapping-from="${row}"]`)
        : mappingRef.current?.querySelector(`[data-mapping-remove="${row}"]`);
    (target || addRowRef.current)?.focus();
    setMappingFocus(null);
  }, [mappingFocus]);

  useEffect(() => {
    listConnections()
      .then((rows) => setConnections(rows || []))
      .catch((err) => setLoadError(err.message));
    getTemplates()
      .then((rows) => setTemplates(rows || []))
      .catch(() => setTemplates([]));
  }, []);

  const adopt = useCallback((view) => {
    setFlow(view);
    setForm(formOf(view));
    const { preset, time } = presetOf(view.cron);
    setCronState({ preset, time, custom: view.cron || '' });
    setDirty(false);
  }, []);

  useEffect(() => {
    if (!flowId) return;
    getFlow(flowId)
      .then(adopt)
      .catch((err) => setLoadError(err.message));
  }, [flowId, adopt]);

  const connection = connections?.find((c) => String(c.id) === form.connectionId);
  const connectionType = connection?.type;
  const sharedTemplates = templates.filter((t) => t.visibility === 'BANK');
  const cron = form.trigger === 'SCHEDULE' ? cronFor(cronState.preset, cronState.time, cronState.custom) : null;

  const update = (patch) => {
    setForm((current) => ({ ...current, ...patch }));
    setDirty(true);
  };
  const setSpec = (key, value) => update({ spec: { ...form.spec, [key]: value } });
  const toggleIn = (key, value) =>
    update({
      [key]: form[key].includes(value) ? form[key].filter((v) => v !== value) : [...form[key], value],
    });

  const request = () => ({
    name: form.name.trim(),
    direction: form.direction,
    connectionId: Number(form.connectionId),
    templateId: Number(form.templateId),
    trigger: form.trigger,
    cron,
    events: form.trigger === 'EVENT' ? form.events : [],
    payloadKinds: form.payloadKinds,
    format: form.format,
    sourceSpec: form.direction === 'INBOUND' ? buildSpec(connectionType, 'INBOUND', form.spec) : null,
    targetSpec: form.direction === 'OUTBOUND' ? buildSpec(connectionType, 'OUTBOUND', form.spec) : null,
    fieldMapping: form.fieldMapping
      .filter((m) => m.from.trim() && m.to.trim())
      .map((m) => ({ from: m.from.trim(), to: m.to.trim() })),
    maskPii: form.maskPii,
    rowCap: form.rowCap === '' ? null : Number(form.rowCap),
    ...(flow ? { expectedVersion: flow.version } : {}),
  });

  /** Saves the form; resolves to the saved FlowView (or null when it went to approval). */
  const save = async () => {
    const problem = validate({ ...form, cron }, connectionType);
    if (problem) {
      setError(problem);
      return null;
    }
    setError('');
    setSaving(true);
    try {
      const body = request();
      const result = flow ? await updateFlow(flow.id, body) : await createFlow(body);
      if (isPendingChange(result)) {
        toast.success(`Sent for approval (change #${result.pendingChangeId})`);
        setSent({ what: `Changing flow "${body.name}"`, change: result });
        setDirty(false);
        return flow;
      }
      toast.success(`Flow "${body.name}" saved`);
      adopt(result);
      if (!flow && result?.id) navigate(`/admin/integrations/flows/${result.id}`, { replace: true });
      return result;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setSaving(false);
    }
  };

  const runPreview = async () => {
    const saved = dirty || !flow ? await save() : flow;
    if (!saved?.id) return;
    setPreviewing(true);
    setPreview(null);
    try {
      const result = await previewFlow(saved.id);
      setPreview(result);
      const suggested = result?.suggestedMapping || [];
      if (suggested.length > 0) {
        setForm((current) => {
          // Keep rows the admin already mapped; add the suggestions for the rest.
          const mapped = new Set(current.fieldMapping.filter((m) => m.from && m.to).map((m) => m.from));
          const additions = suggested
            .filter((s) => !mapped.has(s.from))
            .map((s) => ({ from: s.from, to: s.to, score: s.score }));
          return {
            ...current,
            fieldMapping: [...current.fieldMapping.filter((m) => m.from && m.to), ...additions],
          };
        });
        setDirty(true);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setPreviewing(false);
    }
  };

  const toggle = async (enable) => {
    try {
      const { pending, flow: updated } = await toggleFlow(toast, flow, enable);
      if (pending) {
        setSent({ what: `Enabling flow "${flow.name}"`, change: pending });
        setFlow({ ...flow, pendingChangeId: pending.pendingChangeId });
      } else if (updated) {
        adopt(updated);
      }
    } catch {
      // The error toast is already shown.
    }
  };

  if (loadError) return <ErrorBanner message={loadError} />;
  if (!connections || (flowId && !flow)) return <Spinner label="Loading the flow…" />;

  const outbound = form.direction === 'OUTBOUND';

  return (
    <div className="space-y-4">
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {flow && (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <StatusChip status={flow.enabled ? 'ENABLED' : 'DISABLED'} />
          {flow.pendingChangeId && <PendingTag changeId={flow.pendingChangeId} />}
          <span className="text-muted">version {flow.version}</span>
          <span className="flex-1" />
          {flow.enabled ? (
            <Button variant="danger" onClick={() => toggle(false)}>
              Disable
            </Button>
          ) : (
            <Button onClick={() => toggle(true)} disabled={dirty || Boolean(flow.pendingChangeId)}>
              {flow.direction === 'OUTBOUND' ? 'Request enable' : 'Enable'}
            </Button>
          )}
        </div>
      )}

      <Card>
        <h2 className="mb-3 text-base font-medium">Basics</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Flow name">
            <input
              id={ids.name}
              className={inputClass}
              maxLength={200}
              value={form.name}
              onChange={(e) => update({ name: e.target.value })}
            />
          </Field>
          <fieldset>
            <legend className="mb-1 text-sm font-medium">Direction</legend>
            <div className="flex gap-4 text-sm">
              {[
                { value: 'INBOUND', label: 'Inbound (receive into MoDocument)' },
                { value: 'OUTBOUND', label: 'Outbound (send to the bank)' },
              ].map((d) => (
                <label key={d.value} className="flex items-center gap-1.5">
                  <input
                    type="radio"
                    name="flow-direction"
                    value={d.value}
                    checked={form.direction === d.value}
                    disabled={Boolean(flow)}
                    onChange={() => update({ direction: d.value, spec: {} })}
                  />
                  {d.label}
                </label>
              ))}
            </div>
          </fieldset>
          <Field label="Connection">
            <select
              className={inputClass}
              value={form.connectionId}
              onChange={(e) => update({ connectionId: e.target.value, spec: {} })}
            >
              <option value="">Choose a connection…</option>
              {connections.map((c) => (
                <option key={c.id} value={String(c.id)}>
                  {c.name} ({CONNECTION_TYPE_LABELS[c.type] || c.type})
                </option>
              ))}
            </select>
          </Field>
          <Field label="Template" hint="Only bank-shared templates can be used by flows.">
            <select
              className={inputClass}
              value={form.templateId}
              onChange={(e) => update({ templateId: e.target.value })}
            >
              <option value="">Choose a template…</option>
              {sharedTemplates.map((t) => (
                <option key={t.templateId} value={String(t.templateId)}>
                  {t.name}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {connections.length === 0 && (
          <p className="mt-2 text-xs text-muted">
            No connections yet —{' '}
            <Link to="/admin/integrations/connections" className="text-accent underline">
              add one
            </Link>{' '}
            first.
          </p>
        )}
      </Card>

      <Card>
        <h2 className="mb-3 text-base font-medium">When</h2>
        <fieldset>
          <legend className="sr-only">Trigger</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            {[
              { value: 'MANUAL', label: 'Manual' },
              { value: 'SCHEDULE', label: 'Schedule' },
              { value: 'EVENT', label: 'On events' },
            ].map((t) => (
              <label key={t.value} className="flex items-center gap-1.5">
                <input
                  type="radio"
                  name="flow-trigger"
                  value={t.value}
                  checked={form.trigger === t.value}
                  onChange={() => update({ trigger: t.value })}
                />
                {t.label}
              </label>
            ))}
          </div>
        </fieldset>
        {form.trigger === 'SCHEDULE' && (
          <div className="mt-3 grid gap-3 sm:grid-cols-[1fr_1fr]">
            <Field label="Repeat">
              <select
                className={inputClass}
                value={cronState.preset}
                onChange={(e) => {
                  setCronState({ ...cronState, preset: e.target.value });
                  setDirty(true);
                }}
              >
                {CRON_PRESETS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
            {cronState.preset === 'daily' && (
              <Field label="Time (HH:MM, server time UTC)">
                <input
                  id={ids.time}
                  type="time"
                  className={inputClass}
                  value={cronState.time}
                  onChange={(e) => {
                    setCronState({ ...cronState, time: e.target.value });
                    setDirty(true);
                  }}
                />
              </Field>
            )}
            {cronState.preset === 'custom' && (
              <Field
                label="Cron expression"
                hint="Six fields: second minute hour day month weekday. At least 5 minutes apart."
              >
                <input
                  id={ids.cron}
                  className={`${inputClass} font-mono`}
                  value={cronState.custom}
                  placeholder="0 */30 * * * *"
                  onChange={(e) => {
                    setCronState({ ...cronState, custom: e.target.value });
                    setDirty(true);
                  }}
                />
              </Field>
            )}
            <p className="text-xs text-muted sm:col-span-2">
              Cron: <code data-testid="cron-value">{cron || '—'}</code>
            </p>
          </div>
        )}
        {form.trigger === 'EVENT' && (
          <fieldset className="mt-3">
            <legend className="mb-1 text-sm font-medium">Events</legend>
            <div className="flex flex-wrap gap-4 text-sm">
              {FLOW_EVENTS.map((e) => (
                <label key={e.value} className="flex items-center gap-1.5">
                  <input
                    type="checkbox"
                    checked={form.events.includes(e.value)}
                    onChange={() => toggleIn('events', e.value)}
                  />
                  {e.label}
                </label>
              ))}
            </div>
          </fieldset>
        )}
      </Card>

      <Card>
        <h2 className="mb-3 text-base font-medium">{outbound ? 'What to send' : 'What to receive'}</h2>
        <fieldset>
          <legend className="sr-only">What to send / receive</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            {PAYLOAD_KINDS.map((k) => (
              <label key={k.value} className="flex items-center gap-1.5">
                <input
                  type="checkbox"
                  checked={form.payloadKinds.includes(k.value)}
                  onChange={() => toggleIn('payloadKinds', k.value)}
                />
                {k.label}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="mt-3 max-w-48">
          <Field label="Format">
            <select
              className={inputClass}
              value={form.format}
              onChange={(e) => update({ format: e.target.value })}
            >
              {PAYLOAD_FORMATS.map((f) => (
                <option key={f} value={f}>
                  {f}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </Card>

      <Card>
        <h2 className="mb-3 text-base font-medium">{outbound ? 'Target' : 'Source'}</h2>
        {!connectionType && <p className="text-sm text-muted">Choose a connection first.</p>}
        {connectionType && (
          <SpecForm type={connectionType} outbound={outbound} spec={form.spec} setSpec={setSpec} />
        )}
      </Card>

      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-base font-medium">Field mapping</h2>
          <Button
            ref={addRowRef}
            type="button"
            size="sm"
            variant="secondary"
            onClick={() => {
              update({ fieldMapping: [...form.fieldMapping, { from: '', to: '' }] });
              setMappingFocus({ row: form.fieldMapping.length, field: 'from' });
            }}
          >
            Add row
          </Button>
        </div>
        <p className="mb-2 text-xs text-muted">
          {outbound ? 'MoDocument field → bank column/key.' : 'Bank column/key → MoDocument field.'}{' '}
          Run the dry run to get suggestions.
        </p>
        {form.fieldMapping.length === 0 ? (
          <p className="text-sm text-muted">No mapping yet.</p>
        ) : (
          <table ref={mappingRef} className="w-full text-sm" aria-label="Field mapping">
            <thead>
              <tr className="text-left text-xs text-muted">
                <th scope="col" className="py-1 pr-2 font-medium">From</th>
                <th scope="col" className="py-1 pr-2 font-medium">To</th>
                <th scope="col" className="py-1 pr-2 font-medium">Match</th>
                <th scope="col">
                  <span className="sr-only">Remove</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {form.fieldMapping.map((m, i) => (
                <tr key={i}>
                  <td className="py-1 pr-2">
                    <input
                      data-mapping-from={i}
                      aria-label={`Mapping ${i + 1} from`}
                      className={inputClass}
                      value={m.from}
                      onChange={(e) =>
                        update({
                          fieldMapping: form.fieldMapping.map((row, j) =>
                            j === i ? { ...row, from: e.target.value, score: undefined } : row
                          ),
                        })
                      }
                    />
                  </td>
                  <td className="py-1 pr-2">
                    <input
                      aria-label={`Mapping ${i + 1} to`}
                      className={inputClass}
                      value={m.to}
                      onChange={(e) =>
                        update({
                          fieldMapping: form.fieldMapping.map((row, j) =>
                            j === i ? { ...row, to: e.target.value, score: undefined } : row
                          ),
                        })
                      }
                    />
                  </td>
                  <td className="py-1 pr-2 text-xs text-muted">
                    {typeof m.score === 'number' ? `suggested ${Math.round(m.score * 100)}%` : ''}
                  </td>
                  <td className="py-1 text-right">
                    <Button
                      type="button"
                      size="sm"
                      variant="danger"
                      data-mapping-remove={i}
                      aria-label={`Remove mapping ${i + 1}`}
                      onClick={() => {
                        update({ fieldMapping: form.fieldMapping.filter((_, j) => j !== i) });
                        // The row that moves up into this slot, else the one
                        // above; with no row left the effect falls back to "Add row".
                        const left = form.fieldMapping.length - 1;
                        setMappingFocus({ row: Math.min(i, left - 1), field: 'remove' });
                      }}
                    >
                      ×
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card>
        <h2 className="mb-3 text-base font-medium">Safety</h2>
        <div className="flex items-center gap-3">
          <Switch
            labelledBy={ids.mask}
            checked={form.maskPii}
            onChange={(checked) => update({ maskPii: checked })}
          />
          <span id={ids.mask} className="text-sm">
            Mask personal data (PII)
          </span>
        </div>
        {!form.maskPii && (
          <div className="mt-2">
            <Callout variant="error" title="Masking off needs approval">
              Sending unmasked personal data is a security change: a second administrator must
              approve it before the flow sends anything unmasked.
            </Callout>
          </div>
        )}
        <div className="mt-3 max-w-48">
          <Field label="Row cap" hint="Maximum rows per run (blank: the bank default).">
            <input
              inputMode="numeric"
              className={inputClass}
              value={form.rowCap}
              onChange={(e) => update({ rowCap: e.target.value })}
            />
          </Field>
        </div>
      </Card>

      {error && (
        <p role="alert" className="text-sm text-danger-ink">
          {error}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <Button onClick={save} disabled={saving}>
          {saving ? 'Saving…' : flow ? 'Save changes' : 'Save draft'}
        </Button>
        <Button variant="secondary" onClick={runPreview} disabled={saving || previewing}>
          {previewing ? 'Running the dry run…' : 'Dry run / preview'}
        </Button>
        <Link
          to="/admin/integrations/flows"
          className="inline-flex items-center rounded border border-line px-4 py-1.5 text-sm text-accent hover:bg-pill/50"
        >
          Back to flows
        </Link>
      </div>

      {preview && (
        <section aria-label="Dry run result" className="space-y-2">
          {/* Announced when the dry run finishes (focus stays on the button). */}
          <div role="status">
            <Callout variant="info" title="Dry run — nothing was written">
              The first {preview.rows?.length ?? 0} row(s) this flow would{' '}
              {outbound ? 'send' : 'receive'}
              {form.maskPii ? ', masked as configured' : ''}.
            </Callout>
          </div>
          <DataTable
            caption="Dry run: first rows read"
            columns={(preview.columns || []).map((c) => ({ key: c, label: c }))}
            rows={(preview.rows || []).slice(0, 20)}
            emptyMessage="The dry run found no rows."
          />
        </section>
      )}
    </div>
  );
}

function SpecForm({ type, outbound, spec, setSpec }) {
  const s = spec || {};
  const input = (key, label, props = {}, hint) => (
    <Field label={label} hint={hint}>
      <input
        className={inputClass}
        value={s[key] ?? ''}
        onChange={(e) => setSpec(key, e.target.value)}
        {...props}
      />
    </Field>
  );
  const select = (key, label, options, fallback) => (
    <Field label={label}>
      <select
        className={inputClass}
        value={s[key] || fallback}
        onChange={(e) => setSpec(key, e.target.value)}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </Field>
  );

  if (!outbound) {
    switch (type) {
      case 'DATABASE':
        return (
          <div className="grid gap-3 sm:grid-cols-2">
            {input('table', 'Table or view', {}, 'Ask your DBA for a view; MoDocument never runs free SQL.')}
            {input('columns', 'Columns', {}, 'Comma-separated; blank for all.')}
            {input('watermarkColumn', 'Watermark column', {}, 'Only rows newer than the last run are read.')}
          </div>
        );
      case 'REST_API': {
        const paginationType = s.paginationType || 'NONE';
        return (
          <div className="grid gap-3 sm:grid-cols-2">
            {input('path', 'Path', { placeholder: '/accounts' })}
            {input(
              'recordsPath',
              'Records path',
              { placeholder: 'data.items' },
              'Where the records sit in the response, as a dot path such as data.items.'
            )}
            <Field label="Query parameters" hint="One name=value per line.">
              <textarea
                rows={3}
                className={`${inputClass} font-mono text-xs`}
                value={s.queryText || ''}
                onChange={(e) => setSpec('queryText', e.target.value)}
              />
            </Field>
            {select(
              'paginationType',
              'Pagination',
              [
                { value: 'NONE', label: 'None' },
                { value: 'PAGE', label: 'Page number' },
                { value: 'CURSOR', label: 'Cursor' },
                { value: 'LINK', label: 'Link header' },
              ],
              'NONE'
            )}
            {(PAGINATION_FIELDS[paginationType] || []).map((f) => (
              <Fragment key={f.key}>
                {input(`pagination.${f.key}`, f.label, { placeholder: f.placeholder }, f.hint)}
              </Fragment>
            ))}
          </div>
        );
      }
      case 'SFTP':
        return (
          <div className="grid gap-3 sm:grid-cols-3">
            {input('folder', 'Folder', { placeholder: '/inbound' })}
            {input('glob', 'File pattern', { placeholder: '*.csv' })}
            {select(
              'afterRead',
              'After reading',
              [
                { value: 'MOVE', label: 'Move to processed/' },
                { value: 'DELETE', label: 'Delete' },
              ],
              'MOVE'
            )}
          </div>
        );
      default:
        return (
          <p className="text-sm text-muted">
            A webhook connection can only send. Choose Outbound, or another connection.
          </p>
        );
    }
  }
  switch (type) {
    case 'DATABASE':
      return (
        <div className="grid gap-3 sm:grid-cols-3">
          {input('table', 'Target table', {}, 'A staging table; one transaction per batch.')}
          {select(
            'mode',
            'Write mode',
            [
              { value: 'INSERT', label: 'Insert' },
              { value: 'UPSERT', label: 'Upsert (by key)' },
            ],
            'INSERT'
          )}
          {(s.mode || 'INSERT') === 'UPSERT' && input('keyColumns', 'Key columns', {}, 'Comma-separated.')}
        </div>
      );
    case 'REST_API':
      return (
        <div className="grid gap-3 sm:grid-cols-3">
          {select(
            'method',
            'Method',
            [
              { value: 'POST', label: 'POST' },
              { value: 'PUT', label: 'PUT' },
            ],
            'POST'
          )}
          {input('path', 'Path', { placeholder: '/modocument/records' })}
          {input('batchSize', 'Batch size', { inputMode: 'numeric', placeholder: '500' })}
        </div>
      );
    case 'SFTP':
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          {input('folder', 'Folder', { placeholder: '/outbound' })}
          {input('fileNamePattern', 'File name pattern', { placeholder: 'results-{date}.csv' })}
        </div>
      );
    case 'WEBHOOK':
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          {input('path', 'URL path', { placeholder: '/hooks/modocument' }, 'Appended to the webhook URL.')}
        </div>
      );
    default:
      return null;
  }
}
