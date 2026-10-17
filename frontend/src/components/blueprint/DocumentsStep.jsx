import { useCallback, useEffect, useId, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createInstance, listInstances } from '../../api/doccreation';
import { useToast } from '../../context/ToastContext';
import DataTable from '../DataTable';
import { Badge, Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../ui';
import { ValidationStateTag } from '../doc/validationState';

const instanceIdOf = (row) => row.instanceId ?? row.id;

/**
 * A PDF template's Documents step: "Create a document" (it opens on the
 * builder's first step, Choose data) and the documents made from this
 * template, with their validation state.
 */
export default function DocumentsStep({ blueprintId, blueprintName, readOnly = false }) {
  const navigate = useNavigate();
  const toast = useToast();
  const formId = useId();
  const [docs, setDocs] = useState(null);
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const list = await listInstances({ blueprintId });
      setDocs((list || []).filter((d) => d.blueprintId == null || String(d.blueprintId) === String(blueprintId)));
    } catch (err) {
      setDocs([]);
      setError(err.message);
    }
  }, [blueprintId]);

  useEffect(() => {
    load();
  }, [load]);

  const create = async (event) => {
    event.preventDefault();
    if (!title.trim()) return;
    setCreating(true);
    try {
      const created = await toast.promise(() => createInstance(Number(blueprintId), title.trim()), {
        loading: 'Creating document…',
        success: 'Document created',
        error: (err) => err.message,
      });
      navigate(`/creation/documents/${instanceIdOf(created)}?step=data`);
    } catch {
      // The error toast is already shown.
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="space-y-4" data-testid="documents-step">
      {!readOnly && (
        <Card padding="compact">
          <h2 className="mb-2 text-base font-medium">Create a document</h2>
          <form id={formId} onSubmit={create} className="flex flex-wrap items-end gap-2">
            <div className="min-w-60 flex-1">
              <Field label="Title" hint="Also the generated file's name">
                <input
                  className={inputClass}
                  value={title}
                  placeholder={`e.g. ${blueprintName || 'Form'} — Jane Doe`}
                  onChange={(e) => setTitle(e.target.value)}
                />
              </Field>
            </div>
            <Button type="submit" disabled={creating || !title.trim()}>
              {creating ? 'Creating…' : 'Create a document'}
            </Button>
          </form>
        </Card>
      )}
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <section aria-labelledby={`${formId}-docs`}>
        <h2 id={`${formId}-docs`} className="mb-2 text-base font-medium">
          Documents from this template
        </h2>
        {!docs ? (
          <Spinner />
        ) : (
          <DataTable
            caption="Documents from this template"
            rows={docs}
            emptyMessage="No documents from this template yet."
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
              { key: 'status', label: 'Status', render: (row) => <Badge status={row.status} /> },
              { key: 'validation', label: 'Validation', render: (row) => <ValidationStateTag row={row} /> },
            ]}
          />
        )}
      </section>
    </div>
  );
}
