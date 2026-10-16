import { useCallback, useEffect, useId, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deleteBlueprint, listBlueprints, uploadBlueprint } from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Card, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

const FLOWS = [
  {
    value: 'BLANK',
    label: 'Blank template',
    hint: 'The PDF is empty — it becomes the template exactly as it is.',
  },
  {
    value: 'WITH_DATA',
    label: 'Filled document — remove the data',
    hint: 'The PDF is a filled-in example — its data is removed and the rest becomes the template. You review every removal.',
  },
  {
    value: 'AUTO',
    label: 'Detect automatically',
    hint: 'MoDocument checks whether the PDF carries filled-in data.',
  },
];

/** File names that usually mean a filled-in example rather than a blank form. */
const FILLED_NAME = /(_filled|specimen|_sample|-sample|_mock)/i;

export function suggestedFlow(fileName) {
  return FILLED_NAME.test(fileName || '') ? 'WITH_DATA' : 'AUTO';
}

function isPdf(file) {
  return /\.pdf$/i.test(file?.name || '') && (!file.type || /pdf/i.test(file.type));
}

const blueprintId = (row) => row.id ?? row.blueprintId;

export default function BlueprintListPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const fileErrorId = useId();
  const [blueprints, setBlueprints] = useState(null);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const [file, setFile] = useState(null);
  const [fileError, setFileError] = useState('');
  const [name, setName] = useState('');
  const [companyId, setCompanyId] = useState('');
  const [flow, setFlow] = useState('AUTO');
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

  const chooseFile = (event) => {
    const chosen = event.target.files?.[0] || null;
    if (chosen && !isPdf(chosen)) {
      setFile(null);
      setFileError(`“${chosen.name}” is not a PDF. Templates are made from PDF files only.`);
      event.target.value = '';
      return;
    }
    setFileError('');
    setFile(chosen);
    if (chosen) setFlow(suggestedFlow(chosen.name));
  };

  const upload = async (e) => {
    e.preventDefault();
    if (!file) return;
    setUploading(true);
    try {
      const created = await toast.promise(
        () =>
          uploadBlueprint(file, {
            name: name.trim() || undefined,
            companyId: companyId.trim() || undefined,
            flow,
          }),
        {
          loading: 'Reading your PDF…',
          success: (b) => {
            const fields = b.regions?.length ?? 0;
            const removed = b.edits?.filter((edit) => edit.kind === 'REMOVE').length ?? 0;
            return `Template draft ready — ${fields} field${fields === 1 ? '' : 's'} found${
              b.flow === 'WITH_DATA' ? `, ${removed} value${removed === 1 ? '' : 's'} to remove` : ''
            }. Review it before saving.`;
          },
          error: (err) => err.message,
        }
      );
      navigate(`/creation/blueprints/${blueprintId(created)}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setUploading(false);
    }
  };

  const remove = async (blueprint) => {
    if (!window.confirm(`Delete template "${blueprint.name}"?`)) return;
    try {
      await toast.promise(() => deleteBlueprint(blueprintId(blueprint)), {
        loading: 'Deleting…',
        success: 'Template deleted',
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
        subtitle="Upload a PDF — it becomes an exact, fill-in template for new documents"
        actions={
          <>
            <Button variant="secondary" onClick={load} disabled={refreshing}>
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button onClick={() => setShowUpload(!uploadOpen)}>
              {uploadOpen ? 'Close upload' : 'Upload a template'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {uploadOpen && (
        <Card className="mb-6">
          <h2 className="mb-1 text-lg font-normal">Upload a template</h2>
          <p className="mb-4 text-sm text-muted">
            PDF only. The PDF is kept exactly as it is — logo, fonts and layout — and the places to
            fill in are marked on top of it. You review the fields (and any removed data) before
            the template is saved.
          </p>
          <form onSubmit={upload} className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label="PDF file">
                <input
                  type="file"
                  required
                  accept=".pdf,application/pdf"
                  aria-invalid={fileError ? true : undefined}
                  aria-describedby={fileError ? fileErrorId : undefined}
                  className="block w-full text-sm text-muted file:mr-3 file:cursor-pointer file:rounded file:border-0 file:bg-pill file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-accent"
                  onChange={chooseFile}
                />
              </Field>
              <Field label="Name" hint="Defaults to the file name">
                <input
                  className={inputClass}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Loan application"
                />
              </Field>
              <Field label="Company id" hint="Optional">
                <input
                  className={inputClass}
                  value={companyId}
                  onChange={(e) => setCompanyId(e.target.value)}
                  placeholder="Leave empty for the default"
                />
              </Field>
            </div>
            {fileError && (
              <p id={fileErrorId} role="alert" className="text-sm text-danger-ink">
                {fileError}
              </p>
            )}
            <fieldset>
              <legend className="mb-2 text-sm font-medium">What kind of PDF is it?</legend>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                {FLOWS.map((option) => (
                  <label
                    key={option.value}
                    className={`flex cursor-pointer gap-2 rounded border p-3 text-sm ${
                      flow === option.value ? 'border-accent bg-pill/40' : 'border-line'
                    }`}
                  >
                    <input
                      type="radio"
                      name="flow"
                      value={option.value}
                      checked={flow === option.value}
                      onChange={() => setFlow(option.value)}
                      className="mt-0.5"
                    />
                    <span>
                      <span className="block font-medium">{option.label}</span>
                      <span className="block text-xs text-muted">{option.hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <Button type="submit" disabled={uploading || !file}>
              {uploading ? 'Reading the PDF…' : 'Upload and review'}
            </Button>
          </form>
        </Card>
      )}

      <h2 className="mb-3 text-base font-medium">Templates</h2>
      {!blueprints ? (
        <Spinner />
      ) : (
        <DataTable
          caption="Document templates"
          columns={[
            {
              key: 'name',
              label: 'Name',
              render: (row) => (
                <Link to={`/creation/blueprints/${blueprintId(row)}`} className="text-accent hover:underline">
                  {row.name}
                </Link>
              ),
            },
            {
              key: 'status',
              label: 'Status',
              render: (row) =>
                row.status === 'READY' ? (
                  <Tag tone="success">Ready</Tag>
                ) : (
                  <Tag tone="warning" title="Review and confirm it before creating documents">
                    Draft
                  </Tag>
                ),
            },
            {
              key: 'currentVersion',
              label: 'Version',
              render: (row) => `v${row.currentVersion ?? 1}`,
            },
            { key: 'pageCount', label: 'Pages', render: (row) => row.pageCount ?? '—' },
            { key: 'documentCount', label: 'Documents', render: (row) => row.documentCount ?? 0 },
            {
              key: 'rulesCount',
              label: 'Rules',
              // The template's field rules: a link to its Field rules tab, also when there are none yet.
              render: (row) => (
                <Link
                  to={`/creation/blueprints/${blueprintId(row)}?tab=rules`}
                  className="text-accent hover:underline"
                  aria-label={`Field rules of ${row.name}: ${row.rulesCount ? row.rulesCount : 'none'}`}
                >
                  {row.rulesCount ? row.rulesCount : 'None'}
                </Link>
              ),
            },
            {
              key: 'actions',
              label: '',
              render: (row) => (
                <Button
                  variant="secondary"
                  size="sm"
                  aria-label={`Delete ${row.name}`}
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
          rows={blueprints}
          emptyMessage="No templates yet — upload a PDF to get started."
          onRowClick={(row) => navigate(`/creation/blueprints/${blueprintId(row)}`)}
        />
      )}
    </div>
  );
}
