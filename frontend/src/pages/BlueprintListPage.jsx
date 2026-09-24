import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deleteBlueprint, listBlueprints, uploadBlueprint } from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function BlueprintListPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [blueprints, setBlueprints] = useState(null);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const [file, setFile] = useState(null);
  const [name, setName] = useState('');
  const [companyId, setCompanyId] = useState('');
  const [uploading, setUploading] = useState(false);
  // null = "not decided by the user yet": auto-open only for first-time users.
  const [showUpload, setShowUpload] = useState(null);
  const uploadOpen = showUpload ?? (blueprints !== null && blueprints.length === 0);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setBlueprints(await listBlueprints());
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

  const upload = async (e) => {
    e.preventDefault();
    if (!file) return;
    setUploading(true);
    try {
      const created = await toast.promise(
        () => uploadBlueprint(file, name.trim() || undefined, companyId.trim() || undefined),
        {
          loading: 'Uploading & breaking the document into sections…',
          success: (b) =>
            `Blueprint ready — ${b.sections.length} section${b.sections.length === 1 ? '' : 's'} found${
              b.sectionSource === 'AI' ? ' by AI' : ''
            }`,
          error: (err) => err.message,
        }
      );
      navigate(`/creation/blueprints/${created.blueprintId}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setUploading(false);
    }
  };

  const remove = async (blueprint) => {
    if (!window.confirm(`Delete blueprint "${blueprint.name}"?`)) return;
    try {
      await toast.promise(() => deleteBlueprint(blueprint.blueprintId), {
        loading: 'Deleting…',
        success: 'Blueprint deleted',
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
        crumbs={[{ label: 'Blueprints' }]}
        title="Document Blueprints"
        subtitle="Upload your company's report — it becomes the fill-in template for new documents"
        actions={
          <>
            <Button variant="secondary" onClick={load} disabled={refreshing}>
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button onClick={() => setShowUpload(!uploadOpen)}>
              {uploadOpen ? 'Close upload' : 'Upload a blueprint'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {uploadOpen && (
        <Card className="mb-6">
          <h2 className="mb-1 text-lg font-normal">Upload a blueprint</h2>
          <p className="mb-4 text-sm text-muted">
            A Word document (.docx). The engine breaks it into sections by its headings; each
            section becomes a place to add paragraphs and pictures, and generation reproduces the
            document exactly with your additions in place.
          </p>
          <form onSubmit={upload} className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label="File">
                <input
                  type="file"
                  required
                  accept=".docx"
                  className="block w-full text-sm text-muted file:mr-3 file:cursor-pointer file:rounded file:border-0 file:bg-pill file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-accent"
                  onChange={(e) => setFile(e.target.files[0] || null)}
                />
              </Field>
              <Field label="Name" hint="Defaults to the file name">
                <input
                  className={inputClass}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. PV Survey Report"
                />
              </Field>
              <Field label="Company id" hint="Optional — selects a company-specific flow">
                <input
                  className={inputClass}
                  value={companyId}
                  onChange={(e) => setCompanyId(e.target.value)}
                  placeholder="Leave empty for the default flow"
                />
              </Field>
            </div>
            <Button type="submit" disabled={uploading || !file}>
              {uploading ? 'Uploading…' : 'Upload & break into sections'}
            </Button>
          </form>
        </Card>
      )}

      <h2 className="mb-3 text-base font-medium">Blueprints</h2>
      {!blueprints ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            {
              key: 'name',
              label: 'Name',
              render: (row) => (
                <Link to={`/creation/blueprints/${row.blueprintId}`} className="text-accent hover:underline">
                  {row.name}
                </Link>
              ),
            },
            { key: 'sectionCount', label: 'Sections' },
            { key: 'instanceCount', label: 'Documents' },
            { key: 'companyId', label: 'Company', render: (row) => row.companyId || '—' },
            { key: 'flowKey', label: 'Flow' },
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
          rows={blueprints}
          emptyMessage="No blueprints yet — upload your company's report to get started."
          onRowClick={(row) => navigate(`/creation/blueprints/${row.blueprintId}`)}
        />
      )}
    </div>
  );
}
