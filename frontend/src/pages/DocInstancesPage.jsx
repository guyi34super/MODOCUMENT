import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createInstance, deleteInstance, listBlueprints, listInstances } from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function DocInstancesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [instances, setInstances] = useState(null);
  const [blueprints, setBlueprints] = useState([]);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const [blueprintId, setBlueprintId] = useState('');
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [showCreate, setShowCreate] = useState(null);
  const createOpen = showCreate ?? (instances !== null && instances.length === 0);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [docs, prints] = await Promise.all([listInstances(), listBlueprints()]);
      setInstances(docs);
      setBlueprints(prints);
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setRefreshing(false);
    }
  }, []);

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
      navigate(`/creation/documents/${created.instanceId}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setCreating(false);
    }
  };

  const remove = async (instance) => {
    if (!window.confirm(`Delete document "${instance.title}" and its images?`)) return;
    try {
      await toast.promise(() => deleteInstance(instance.instanceId), {
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
        subtitle="Documents being built on a blueprint — fill the sections, then generate"
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
              <Field label="Blueprint">
                <select
                  required
                  className={inputClass}
                  value={blueprintId}
                  onChange={(e) => setBlueprintId(e.target.value)}
                >
                  <option value="">Select a blueprint…</option>
                  {blueprints.map((b) => (
                    <option key={b.blueprintId} value={b.blueprintId}>
                      {b.name}
                    </option>
                  ))}
                </select>
                {blueprints.length === 0 && (
                  <p className="mt-1 text-xs text-muted">
                    No blueprints yet —{' '}
                    <Link to="/creation/blueprints" className="text-accent hover:underline">
                      upload one first
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
            <Button type="submit" disabled={creating || !blueprintId || !title.trim()}>
              {creating ? 'Creating…' : 'Create document'}
            </Button>
          </form>
        </Card>
      )}

      <h2 className="mb-3 text-base font-medium">Documents</h2>
      {!instances ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            {
              key: 'title',
              label: 'Title',
              render: (row) => (
                <Link to={`/creation/documents/${row.instanceId}`} className="text-accent hover:underline">
                  {row.title}
                </Link>
              ),
            },
            { key: 'blueprintName', label: 'Blueprint' },
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
                <Button variant="secondary" size="sm" onClick={() => remove(row)}>
                  Delete
                </Button>
              ),
            },
          ]}
          rows={instances}
          emptyMessage="No documents yet — click 'New document' to start one from a blueprint."
          onRowClick={(row) => navigate(`/creation/documents/${row.instanceId}`)}
        />
      )}
    </div>
  );
}
