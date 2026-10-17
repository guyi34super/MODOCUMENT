import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deleteDataTemplate, listDataTemplates } from '../api/dataTemplates';
import ConfirmDialog from '../components/ConfirmDialog';
import DataTable from '../components/DataTable';
import PageHeader from '../components/PageHeader';
import { CopyFromFlowDialog, NewDataTemplateDialog } from '../components/dataTemplates/DataTemplateDialogs';
import { Button, Callout, ErrorBanner, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

/** How many PDF templates use a data template (the summary may carry a count or a list). */
export function usedByCount(usedBy) {
  if (Array.isArray(usedBy)) return usedBy.length;
  return Number(usedBy) || 0;
}

/**
 * Step 2 of document creation, "Validation" (data-templates contract §6):
 * the user's data templates — the fields a document collects and the rules
 * each must pass — made from scratch or copied from a flow template.
 */
export default function DataTemplatesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState('');
  const [newOpen, setNewOpen] = useState(false);
  const [copyOpen, setCopyOpen] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setTemplates((await listDataTemplates()) || []);
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const open = (dto, message) => {
    toast.success(message);
    navigate(`/creation/data-templates/${dto.id}`);
  };

  const remove = async () => {
    setBusy(true);
    try {
      await deleteDataTemplate(deleting.id);
      toast.success(`Deleted ${deleting.name}`);
      setDeleting(null);
      load();
    } catch (err) {
      setDeleting(null);
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Validation' }]}
        title="Validation"
        subtitle="Step 2 · data templates: the fields a document collects and the rules they must pass"
        actions={
          <>
            <Button variant="secondary" onClick={() => setCopyOpen(true)}>
              Copy from a flow template
            </Button>
            <Button onClick={() => setNewOpen(true)}>New data template</Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="mb-4">
        <Callout variant="info" title="How it fits together">
          Upload a PDF (step 1), describe its data here, then map the PDF’s fields onto the data fields (step 3).
          A document can only be saved as final and downloaded once its data passes these rules.
        </Callout>
      </div>

      {!templates ? (
        <Spinner />
      ) : (
        <DataTable
          caption="Data templates"
          rows={templates}
          emptyMessage="No data templates yet — create one, or copy the fields and rules of a flow template."
          onRowClick={(row) => navigate(`/creation/data-templates/${row.id}`)}
          columns={[
            {
              key: 'name',
              label: 'Name',
              render: (row) => (
                <Link to={`/creation/data-templates/${row.id}`} className="text-accent hover:underline">
                  {row.name}
                </Link>
              ),
            },
            { key: 'fieldCount', label: 'Fields', render: (row) => row.fieldCount ?? 0 },
            { key: 'ruleCount', label: 'Rules', render: (row) => row.ruleCount ?? 0 },
            {
              key: 'source',
              label: 'Based on',
              render: (row) => row.sourceTemplateName || (row.sourceTemplateId != null ? `Template ${row.sourceTemplateId}` : '—'),
            },
            {
              key: 'usedBy',
              label: 'Used by',
              render: (row) => {
                const n = usedByCount(row.usedBy);
                return n ? `${n} PDF template${n === 1 ? '' : 's'}` : 'Not used yet';
              },
            },
            {
              key: 'actions',
              label: '',
              render: (row) => (
                <Button
                  size="sm"
                  variant="danger"
                  aria-label={`Delete ${row.name}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    setDeleting(row);
                  }}
                >
                  Delete
                </Button>
              ),
            },
          ]}
        />
      )}

      <NewDataTemplateDialog
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onCreated={(dto) => {
          setNewOpen(false);
          open(dto, `Created ${dto.name} — now add its fields`);
        }}
      />
      <CopyFromFlowDialog
        open={copyOpen}
        onClose={() => setCopyOpen(false)}
        onCopied={(dto) => {
          setCopyOpen(false);
          open(dto, `Copied ${dto.fields?.length ?? 0} fields into ${dto.name}`);
        }}
      />
      <ConfirmDialog
        open={Boolean(deleting)}
        title="Delete data template"
        confirmLabel="Delete"
        tone="danger"
        busy={busy}
        onConfirm={remove}
        onCancel={() => setDeleting(null)}
      >
        Delete “{deleting?.name}” with its fields and rules? A data template that a PDF template uses cannot be deleted.
      </ConfirmDialog>
    </div>
  );
}
