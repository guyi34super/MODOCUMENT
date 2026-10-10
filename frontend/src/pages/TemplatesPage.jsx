import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates, invalidateTemplates } from '../api/cache';
import { deleteTemplate, setTemplateVisibility } from '../api/templates';
import { isPendingChange } from '../api/changes';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, Spinner, Switch, Tag } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';

/** template:publish is a bank ADMIN permission (the server enforces it). */
const canPublish = (user) => user?.role === 'ADMIN';

/** Older payloads (before ws22) carry no visibility: they are private. */
const isShared = (template) => template.visibility === 'BANK';

/**
 * The template list (ws22): the caller's own templates plus those shared
 * with the whole bank. A Shared/Private badge says which; a shared template
 * names its owner. Bank administrators (template:publish) get a
 * "Share with bank" switch per row; templates the caller cannot change
 * (another user's shared template, without template:publish) offer no
 * Delete and open read-only.
 */
export default function TemplatesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const user = useAuth()?.user;
  const publisher = canPublish(user);
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState('');
  const [switching, setSwitching] = useState(null);

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

  const toggleShared = async (template, share) => {
    setSwitching(template.templateId);
    try {
      await toast.promise(setTemplateVisibility(template.templateId, share ? 'BANK' : 'PRIVATE'), {
        loading: share ? 'Sharing with the bank…' : 'Making private…',
        success: share
          ? `"${template.name}" is now shared with everyone in your bank`
          : `"${template.name}" is private again`,
      });
      setTemplates(await getTemplates());
    } catch {
      // The error toast is already shown (e.g. 409: colleagues still use it).
    } finally {
      setSwitching(null);
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
            {publisher && (
              <Button variant="secondary" onClick={() => navigate('/templates/new?visibility=BANK')}>
                New shared template
              </Button>
            )}
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
                key: 'visibility',
                label: 'Visibility',
                render: (row) =>
                  isShared(row) ? (
                    <Tag tone="info" title="Every user of your bank can use it">
                      Shared
                    </Tag>
                  ) : (
                    <Tag title="Only its owner can see and use it">Private</Tag>
                  ),
              },
              {
                key: 'owner',
                label: 'Owner',
                render: (row) =>
                  row.ownedByMe === false ? (
                    row.ownerName || 'A colleague'
                  ) : (
                    <span className="text-muted">You</span>
                  ),
              },
              ...(publisher
                ? [
                    {
                      key: 'share',
                      label: 'Share with bank',
                      render: (row) => (
                        <Switch
                          label="Share with bank"
                          checked={isShared(row)}
                          disabled={switching === row.templateId || row.editable === false}
                          onChange={(share) => toggleShared(row, share)}
                        />
                      ),
                    },
                  ]
                : []),
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
                    {row.editable !== false && (
                      <Button size="sm" variant="danger" onClick={() => remove(row)}>
                        Delete
                      </Button>
                    )}
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
