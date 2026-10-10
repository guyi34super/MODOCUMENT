import { useCallback, useEffect, useState } from 'react';
import * as announcementsApi from '../api/announcements';
import ConfirmDialog from '../components/ConfirmDialog';
import DataTable from '../components/DataTable';
import Modal from '../components/Modal';
import PageHeader from '../components/PageHeader';
import { Button, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

const KINDS = [
  { value: 'INFO', label: 'Information' },
  { value: 'MAINTENANCE', label: 'Maintenance' },
  { value: 'RELEASE', label: 'Release' },
];
const SEVERITIES = [
  { value: 'INFO', label: 'Info' },
  { value: 'WARNING', label: 'Warning' },
  { value: 'CRITICAL', label: 'Critical' },
];
const TITLE_MAX = 200;
const BODY_MAX = 2000;

const STATUS_STYLES = {
  ACTIVE: 'bg-success-bg text-success-ink',
  SCHEDULED: 'bg-pill text-accent',
  ENDED: 'bg-neutral-100 text-muted',
  ARCHIVED: 'bg-neutral-200 text-muted',
};

const pad = (n) => String(n).padStart(2, '0');

/** ISO instant → the value of an <input type="datetime-local"> (local time). */
export function toLocalInput(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** An <input type="datetime-local"> value (local time) → ISO instant, or null. */
export function fromLocalInput(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function formatLocal(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function emptyForm() {
  const start = new Date();
  start.setSeconds(0, 0);
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return {
    kind: 'INFO',
    severity: 'INFO',
    title: '',
    body: '',
    startsAt: toLocalInput(start.toISOString()),
    endsAt: toLocalInput(end.toISOString()),
    maintenanceStartsAt: '',
    maintenanceEndsAt: '',
    releaseVersion: '',
    allBanks: true,
    audience: [],
    audienceText: '',
  };
}

/**
 * The edit form of a row. With the bank picker (`pickable`) the codes are
 * ticked boxes; without it (the bank list could not load) they are the
 * free-text field — never both, so removing a code always removes it.
 */
function formFrom(row, pickable) {
  const codes = row.audienceBankCodes || [];
  return {
    kind: row.kind,
    severity: row.severity,
    title: row.title || '',
    body: row.body || '',
    startsAt: toLocalInput(row.startsAt),
    endsAt: toLocalInput(row.endsAt),
    maintenanceStartsAt: toLocalInput(row.maintenanceStartsAt),
    maintenanceEndsAt: toLocalInput(row.maintenanceEndsAt),
    releaseVersion: row.releaseVersion || '',
    allBanks: codes.length === 0,
    audience: pickable ? codes : [],
    audienceText: pickable ? '' : codes.join(', '),
  };
}

/** Picked banks plus any typed codes (the free-text fallback), deduplicated. */
function audienceCodes(form) {
  const typed = (form.audienceText || '')
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean);
  return [...new Set([...form.audience, ...typed])];
}

/** The API body: maintenance/release fields only for their kind, times as ISO instants. */
export function toRequest(form) {
  return {
    kind: form.kind,
    severity: form.severity,
    title: form.title.trim(),
    body: form.body,
    audienceBankCodes: form.allBanks ? null : audienceCodes(form),
    startsAt: fromLocalInput(form.startsAt),
    endsAt: fromLocalInput(form.endsAt),
    maintenanceStartsAt: form.kind === 'MAINTENANCE' ? fromLocalInput(form.maintenanceStartsAt) : null,
    maintenanceEndsAt: form.kind === 'MAINTENANCE' ? fromLocalInput(form.maintenanceEndsAt) : null,
    releaseVersion: form.kind === 'RELEASE' ? form.releaseVersion.trim() || null : null,
  };
}

function Pill({ children, className }) {
  return (
    <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${className}`}>
      {children}
    </span>
  );
}

/**
 * Platform console: announcements (ws24, platform:read to list,
 * platform:announce to write). Banners reach every bank, or the banks
 * picked in the audience, during their display window; MAINTENANCE also
 * names its outage window and can never be dismissed by users. Deleting
 * archives (the record of what was shown is kept).
 */
export default function PlatformAnnouncementsPage() {
  const toast = useToast();
  const [rows, setRows] = useState(null);
  const [banks, setBanks] = useState(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(null); // {id|null, form}
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [archiving, setArchiving] = useState(null);

  const load = useCallback(() => {
    announcementsApi
      .listPlatformAnnouncements()
      .then((data) => setRows(data?.announcements || []))
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
    announcementsApi
      .listAudienceBanks()
      .then(setBanks)
      .catch(() => setBanks([])); // free-text codes still work
  }, [load]);

  const openCreate = () => {
    setFormError('');
    setEditing({ id: null, form: emptyForm() });
  };

  const openEdit = (row) => {
    setFormError('');
    setEditing({ id: row.id, form: formFrom(row, Boolean(banks && banks.length > 0)) });
  };

  const setField = (name, value) =>
    setEditing((current) => ({ ...current, form: { ...current.form, [name]: value } }));

  const toggleBank = (code) =>
    setEditing((current) => {
      const audience = current.form.audience.includes(code)
        ? current.form.audience.filter((known) => known !== code)
        : [...current.form.audience, code];
      return { ...current, form: { ...current.form, audience } };
    });

  const submit = async (event) => {
    event.preventDefault();
    const body = toRequest(editing.form);
    if (!editing.form.allBanks && body.audienceBankCodes.length === 0) {
      setFormError('Pick at least one bank, or choose "All banks".');
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      if (editing.id) {
        await announcementsApi.updateAnnouncement(editing.id, body);
        toast.success(`Announcement "${body.title}" updated`);
      } else {
        await announcementsApi.createAnnouncement(body);
        toast.success(`Announcement "${body.title}" published`);
      }
      setEditing(null);
      load();
    } catch (err) {
      setFormError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const confirmArchive = async () => {
    setSaving(true);
    try {
      await announcementsApi.archiveAnnouncement(archiving.id);
      toast.success(`Announcement "${archiving.title}" withdrawn`);
      setArchiving(null);
      load();
    } catch (err) {
      toast.error(err.message);
      setArchiving(null);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      key: 'title',
      label: 'Announcement',
      render: (row) => (
        <div className="min-w-48">
          <p className="font-medium text-ink">{row.title}</p>
          <p className="mt-0.5 flex flex-wrap gap-1">
            <Pill className="bg-neutral-100 text-ink">{row.kind}</Pill>
            <Pill
              className={
                row.severity === 'CRITICAL'
                  ? 'bg-danger-bg text-danger-ink'
                  : row.severity === 'WARNING'
                    ? 'bg-amber-100 text-amber-800'
                    : 'bg-pill text-accent'
              }
            >
              {row.severity}
            </Pill>
            {row.releaseVersion && <Pill className="bg-pill text-accent">{row.releaseVersion}</Pill>}
          </p>
        </div>
      ),
    },
    {
      key: 'window',
      label: 'Shown',
      render: (row) => (
        <span className="whitespace-nowrap">
          {formatLocal(row.startsAt)} – {formatLocal(row.endsAt)}
          {row.kind === 'MAINTENANCE' && (
            <span className="block text-xs text-muted">
              Maintenance {formatLocal(row.maintenanceStartsAt)} – {formatLocal(row.maintenanceEndsAt)}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'audience',
      label: 'Audience',
      render: (row) =>
        row.audienceBankCodes && row.audienceBankCodes.length > 0
          ? row.audienceBankCodes.join(', ')
          : 'All banks',
    },
    {
      key: 'status',
      label: 'Status',
      render: (row) => (
        <Pill className={STATUS_STYLES[row.status] || 'bg-neutral-100 text-muted'}>{row.status}</Pill>
      ),
    },
    {
      key: 'actions',
      label: '',
      render: (row) =>
        row.archived ? null : (
          <span className="flex gap-1.5">
            <Button size="sm" variant="secondary" onClick={() => openEdit(row)}>
              Edit
            </Button>
            <Button size="sm" variant="danger" onClick={() => setArchiving(row)}>
              Withdraw
            </Button>
          </span>
        ),
    },
  ];

  const form = editing?.form;
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Platform' }, { label: 'Announcements' }]}
        title="Announcements"
        subtitle="Banners shown to bank users: information, maintenance windows, releases"
        actions={<Button onClick={openCreate}>New announcement</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      {!rows && !error && <Spinner label="Loading announcements…" />}
      {rows && (
        <DataTable
          columns={columns}
          rows={rows}
          emptyMessage="No announcements yet. New releases announce themselves when they are deployed."
        />
      )}

      <Modal
        open={Boolean(editing)}
        onClose={() => {
          if (!saving) setEditing(null);
        }}
        title={editing?.id ? 'Edit announcement' : 'New announcement'}
        description="Users see the title and text as plain text, in their own time zone."
        size="lg"
        footer={
          <>
            <Button type="button" variant="secondary" disabled={saving} onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button type="submit" form="announcement-form" disabled={saving}>
              {saving ? 'Saving…' : editing?.id ? 'Save changes' : 'Publish'}
            </Button>
          </>
        }
      >
        {form && (
          <form id="announcement-form" onSubmit={submit} className="space-y-3">
            <ErrorBanner message={formError} />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Kind">
                <select
                  className={inputClass}
                  value={form.kind}
                  onChange={(e) => setField('kind', e.target.value)}
                >
                  {KINDS.map((kind) => (
                    <option key={kind.value} value={kind.value}>
                      {kind.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Severity">
                <select
                  className={inputClass}
                  value={form.severity}
                  onChange={(e) => setField('severity', e.target.value)}
                >
                  {SEVERITIES.map((severity) => (
                    <option key={severity.value} value={severity.value}>
                      {severity.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field label="Title" hint={`${form.title.length}/${TITLE_MAX}`}>
              <input
                className={inputClass}
                required
                maxLength={TITLE_MAX}
                value={form.title}
                onChange={(e) => setField('title', e.target.value)}
              />
            </Field>
            <Field
              label="Text"
              hint={`Plain text; line breaks are kept and HTML shows as typed. ${form.body.length}/${BODY_MAX}`}
            >
              <textarea
                className={inputClass}
                rows={4}
                maxLength={BODY_MAX}
                value={form.body}
                onChange={(e) => setField('body', e.target.value)}
              />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Show from">
                <input
                  type="datetime-local"
                  className={inputClass}
                  required
                  value={form.startsAt}
                  onChange={(e) => setField('startsAt', e.target.value)}
                />
              </Field>
              <Field label="Show until">
                <input
                  type="datetime-local"
                  className={inputClass}
                  required
                  value={form.endsAt}
                  onChange={(e) => setField('endsAt', e.target.value)}
                />
              </Field>
            </div>
            {form.kind === 'MAINTENANCE' && (
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Maintenance starts" hint="Inside the display window">
                  <input
                    type="datetime-local"
                    className={inputClass}
                    required
                    value={form.maintenanceStartsAt}
                    onChange={(e) => setField('maintenanceStartsAt', e.target.value)}
                  />
                </Field>
                <Field label="Maintenance ends">
                  <input
                    type="datetime-local"
                    className={inputClass}
                    required
                    value={form.maintenanceEndsAt}
                    onChange={(e) => setField('maintenanceEndsAt', e.target.value)}
                  />
                </Field>
              </div>
            )}
            {form.kind === 'RELEASE' && (
              <Field label="Release version" hint="X.Y.Z — admins get a “What's new” link to its notes">
                <input
                  className={inputClass}
                  required
                  value={form.releaseVersion}
                  onChange={(e) => setField('releaseVersion', e.target.value)}
                />
              </Field>
            )}
            <fieldset className="text-sm">
              <legend className="mb-1 font-medium text-ink">Audience</legend>
              <label className="mr-4 inline-flex items-center gap-1.5">
                <input
                  type="radio"
                  name="audience"
                  checked={form.allBanks}
                  onChange={() => setField('allBanks', true)}
                />
                All banks
              </label>
              <label className="inline-flex items-center gap-1.5">
                <input
                  type="radio"
                  name="audience"
                  checked={!form.allBanks}
                  onChange={() => setField('allBanks', false)}
                />
                Selected banks
              </label>
              {!form.allBanks && (
                <div className="mt-2 max-h-48 overflow-y-auto rounded border border-line p-2">
                  {banks === null && <Spinner inline label="Loading banks…" />}
                  {banks && banks.length > 0 && (
                    <ul className="space-y-1">
                      {banks.map((bank) => (
                        <li key={bank.code}>
                          <label className="inline-flex items-center gap-1.5">
                            <input
                              type="checkbox"
                              checked={form.audience.includes(bank.code)}
                              onChange={() => toggleBank(bank.code)}
                            />
                            <span className="font-mono text-xs">{bank.code}</span>
                            <span className="text-muted">{bank.name}</span>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                  {(banks?.length === 0 || form.audienceText) && (
                    <Field label="Bank codes" hint="Comma-separated">
                      <input
                        className={inputClass}
                        value={form.audienceText}
                        onChange={(e) => setField('audienceText', e.target.value)}
                      />
                    </Field>
                  )}
                </div>
              )}
            </fieldset>
          </form>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(archiving)}
        title="Withdraw announcement"
        confirmLabel="Withdraw"
        tone="danger"
        busy={saving}
        onConfirm={confirmArchive}
        onCancel={() => setArchiving(null)}
      >
        <p>
          “{archiving?.title}” stops showing to every bank at once. It stays listed here as
          archived.
        </p>
      </ConfirmDialog>
    </div>
  );
}
