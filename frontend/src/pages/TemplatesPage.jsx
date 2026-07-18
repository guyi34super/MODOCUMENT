import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates, invalidateTemplates } from '../api/cache';
import { deleteTemplate } from '../api/templates';
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
        success: `Template "${template.name}" deleted`,
      });
      setTemplates(await getTemplates());
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'Templates' }]}
        title="Templates & Fields"
        subtitle="Define the shape of the documents you ingest"
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
        <DataTable
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
                  <Button variant="secondary" onClick={() => navigate(`/templates/${row.templateId}`)}>
                    Edit
                  </Button>
                  <Button variant="danger" onClick={() => remove(row)}>
                    Delete
                  </Button>
                </div>
              ),
            },
          ]}
          rows={templates}
          emptyMessage="No templates yet — create one to start ingesting documents."
        />
      )}
    </div>
  );
}
