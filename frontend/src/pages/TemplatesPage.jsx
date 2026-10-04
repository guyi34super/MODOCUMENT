import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates, invalidateTemplates } from '../api/cache';
import { deleteTemplate } from '../api/templates';
import { isPendingChange } from '../api/changes';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function TemplatesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setTemplates(await getTemplates());
      // A banner that survives a successful reload reads as a broken page.
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const remove = async (template) => {
    if (!window.confirm(`Delete template "${template.name}"?`)) return;
    try {
      await toast.promise(deleteTemplate(template.templateId), {
        loading: 'Deleting template…',
        // ws10: in production the delete waits for a second user's approval.
        success: (result) =>
          isPendingChange(result)
            ? `Deleting "${template.name}" was submitted for approval (change #${result.pendingChangeId})`
            : `Template "${template.name}" deleted`,
      });
      setTemplates(await getTemplates());
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Design Templates' }]}
        title="Design Templates"
        subtitle="Define the shape of the documents you process"
        actions={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                invalidateTemplates();
                load();
              }}
            >
              Refresh
            </Button>
            <Button onClick={() => navigate('/templates/new')}>New template</Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      {!templates ? (
        <Spinner />
      ) : (
        <>
          <DataTable
            maxHeight="65vh"
            onRowClick={(row) => navigate(`/templates/${row.templateId}`)}
            columns={[
              { key: 'templateId', label: 'ID' },
              {
                key: 'name',
                label: 'Name',
                render: (row) => (
                  <Link to={`/templates/${row.templateId}`} className="text-accent hover:underline">
                    {row.name}
                  </Link>
                ),
              },
              { key: 'description', label: 'Description' },
              {
                key: 'actions',
                label: '',
                render: (row) => (
                  <div className="flex justify-end gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => navigate(`/validation?templateId=${row.templateId}`)}
                    >
                      Rules
                    </Button>
                    <Button size="sm" variant="danger" onClick={() => remove(row)}>
                      Delete
                    </Button>
                  </div>
                ),
              },
            ]}
            rows={templates}
            emptyMessage={
              <>
                No templates yet —{' '}
                <Link to="/templates/new" className="text-accent hover:underline">
                  design your first template
                </Link>{' '}
                to start processing documents.
              </>
            }
          />
          {templates.length > 0 && (
            <p className="mt-4 text-sm text-muted">
              Next step:{' '}
              <Link to="/validation" className="text-accent hover:underline">
                Set Field Rules →
              </Link>
            </p>
          )}
        </>
      )}
    </div>
  );
}
