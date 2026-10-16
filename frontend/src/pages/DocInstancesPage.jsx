import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  blueprintPagePath,
  createInstance,
  deleteInstance,
  listBlueprints,
  listInstances,
} from '../api/doccreation';
import AuthedImage from '../components/AuthedImage';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

const blueprintIdOf = (b) => b.id ?? b.blueprintId;

/** The status filter (field-validation contract §4.4): '' = all documents. */
const STATUSES = [
  ['', 'All'],
  ['DRAFT', 'Draft'],
  ['VALIDATED', 'Validated'],
  ['GENERATED', 'Generated'],
  ['SUBMITTED', 'Submitted'],
  ['RELEASED', 'Released'],
];
const instanceIdOf = (row) => row.instanceId ?? row.id;

export default function DocInstancesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [instances, setInstances] = useState(null);
  const [blueprints, setBlueprints] = useState([]);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [status, setStatus] = useState('');

  const [blueprintId, setBlueprintId] = useState('');
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [showCreate, setShowCreate] = useState(null);
  const createOpen = showCreate ?? (instances !== null && instances.length === 0 && !status);
  const chosen = blueprints.find((b) => String(blueprintIdOf(b)) === String(blueprintId));

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [docs, prints] = await Promise.all([listInstances(status ? { status } : {}), listBlueprints()]);
      setInstances(docs);
      // Only confirmed templates can start a document (a DRAFT answers 409).
      setBlueprints((prints || []).filter((b) => b.status === 'READY'));
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setRefreshing(false);
    }
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const create = async (e) => {
    e.preventDefault();
    if (!blueprintId || !title.trim()) return;
    setCreating(true);
    try {
      const created = await toast.promise(() => createInstance(Number(blueprintId), title.trim()), {
        loading: 'Creating document…',
        success: 'Document created',
        error: (err) => err.message,
      });
      navigate(`/creation/documents/${instanceIdOf(created)}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setCreating(false);
    }
  };

  const remove = async (instance) => {
    if (!window.confirm(`Delete document "${instance.title}" and its images?`)) return;
    try {
      await toast.promise(() => deleteInstance(instanceIdOf(instance)), {
        loading: 'Deleting…',
        success: 'Document deleted',
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'My Documents' }]}
        title="My Documents"
        subtitle="Documents built on a PDF template — fill in the fields, add photos, then generate"
        actions={
          <>
            <Button variant="secondary" onClick={load} disabled={refreshing}>
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button onClick={() => setShowCreate(!createOpen)}>
              {createOpen ? 'Close' : 'New document'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {createOpen && (
        <Card className="mb-6">
          <h2 className="mb-4 text-lg font-normal">New document</h2>
          <form onSubmit={create} className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Template">
                <select
                  required
                  className={inputClass}
                  value={blueprintId}
                  onChange={(e) => setBlueprintId(e.target.value)}
                >
                  <option value="">Select a template…</option>
                  {blueprints.map((b) => (
                    <option key={blueprintIdOf(b)} value={blueprintIdOf(b)}>
                      {b.name}
                      {b.pageCount ? ` (${b.pageCount} page${b.pageCount === 1 ? '' : 's'})` : ''}
                    </option>
                  ))}
                </select>
                {blueprints.length === 0 && (
                  <p className="mt-1 text-xs text-muted">
                    No ready templates yet —{' '}
                    <Link to="/creation/blueprints" className="text-accent hover:underline">
                      upload and confirm one first
                    </Link>
                    .
                  </p>
                )}
              </Field>
              <Field label="Title" hint="Also the generated file's name">
                <input
                  required
                  className={inputClass}
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="e.g. Survey — 12 Oak Street"
                />
              </Field>
            </div>
            {chosen && (
              <figure className="w-40">
                <AuthedImage
                  path={blueprintPagePath(blueprintIdOf(chosen), 0, {
                    variant: 'template',
                    version: chosen.currentVersion,
                    dpi: 50,
                  })}
                  alt={`Page 1 of ${chosen.name}`}
                  className="h-52 w-40 rounded border border-line object-contain"
                />
                <figcaption className="mt-1 text-xs text-muted">
                  {chosen.name} · page 1{chosen.pageCount > 1 ? ` of ${chosen.pageCount}` : ''}
                </figcaption>
              </figure>
            )}
            <Button type="submit" disabled={creating || !blueprintId || !title.trim()}>
              {creating ? 'Creating…' : 'Create document'}
            </Button>
          </form>
        </Card>
      )}

      <div className="mb-3 flex flex-wrap items-end gap-3">
        <h2 className="flex-1 text-base font-medium">Documents</h2>
        <label className="text-sm">
          <span className="mr-2 text-muted">Status</span>
          <select
            className={`${inputClass} !w-40`}
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            data-testid="status-filter"
          >
            {STATUSES.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {!instances ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            {
              key: 'title',
              label: 'Title',
              render: (row) => (
                <Link to={`/creation/documents/${instanceIdOf(row)}`} className="text-accent hover:underline">
                  {row.title}
                </Link>
              ),
            },
            { key: 'blueprintName', label: 'Template' },
            { key: 'pageCount', label: 'Pages', render: (row) => row.pageCount ?? '—' },
            { key: 'status', label: 'Status', render: (row) => <Badge status={row.status} /> },
            {
              key: 'lastGeneratedDate',
              label: 'Last generated',
              render: (row) =>
                row.lastGeneratedDate ? new Date(row.lastGeneratedDate).toLocaleString() : '—',
            },
            {
              key: 'actions',
              label: '',
              render: (row) => (
                <Button
                  variant="secondary"
                  size="sm"
                  aria-label={`Delete ${row.title}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    remove(row);
                  }}
                >
                  Delete
                </Button>
              ),
            },
          ]}
          rows={instances}
          caption="My documents"
          emptyMessage={
            status
              ? `No ${STATUSES.find(([v]) => v === status)[1].toLowerCase()} documents.`
              : "No documents yet — click 'New document' to start one from a template."
          }
          onRowClick={(row) => navigate(`/creation/documents/${instanceIdOf(row)}`)}
        />
      )}
    </div>
  );
}
