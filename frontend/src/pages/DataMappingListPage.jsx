import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { listBlueprints } from '../api/doccreation';
import DataTable from '../components/DataTable';
import PageHeader from '../components/PageHeader';
import { Button, ErrorBanner, Spinner, Tag } from '../components/ui';

const blueprintIdOf = (b) => b.id ?? b.blueprintId;
const stepLink = (b) => `/creation/blueprints/${blueprintIdOf(b)}?step=mapping`;

/** "x of y mapped" with its tone: all mapped (green), some (amber), no data template yet (grey). */
export function mappingStatus(b) {
  if (b.dataTemplateId == null) return { tone: 'neutral', text: 'No data template yet' };
  const mapped = b.mappedCount ?? 0;
  const total = b.mappableCount ?? 0;
  return { tone: total > 0 && mapped >= total ? 'success' : 'warning', text: `${mapped} of ${total} mapped` };
}

/**
 * Step 3 of document creation, "Map fields" (data-templates contract §6):
 * every PDF template with its data template and how many of its fields are
 * mapped; each opens the template's Map fields step.
 */
export default function DataMappingListPage() {
  const navigate = useNavigate();
  const [blueprints, setBlueprints] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setBlueprints((await listBlueprints()) || []);
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Map fields' }]}
        title="Map fields"
        subtitle="Step 3 · connect each PDF template's fields to the fields of its data template"
        actions={
          <Button variant="secondary" onClick={load}>
            Refresh
          </Button>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      {!blueprints ? (
        <Spinner />
      ) : (
        <DataTable
          caption="PDF templates and their mapping"
          rows={blueprints}
          emptyMessage="No PDF templates yet — upload one first (step 1)."
          onRowClick={(row) => navigate(stepLink(row))}
          columns={[
            {
              key: 'name',
              label: 'PDF template',
              render: (row) => (
                <Link to={stepLink(row)} className="text-accent hover:underline">
                  {row.name}
                </Link>
              ),
            },
            {
              key: 'dataTemplateName',
              label: 'Data template',
              render: (row) =>
                row.dataTemplateName || <span className="text-muted">{row.status === 'DRAFT' ? 'Confirm the PDF first' : 'None chosen'}</span>,
            },
            {
              key: 'mapped',
              label: 'Mapped',
              render: (row) => {
                const status = mappingStatus(row);
                return <Tag tone={status.tone}>{status.text}</Tag>;
              },
            },
            {
              key: 'open',
              label: '',
              srLabel: 'Open',
              render: (row) => (
                <Link
                  to={stepLink(row)}
                  className="text-xs text-accent hover:underline"
                  aria-label={`Map the fields of ${row.name}`}
                  onClick={(e) => e.stopPropagation()}
                >
                  {row.dataTemplateId == null ? 'Choose a data template' : 'Map fields'}
                </Link>
              ),
            },
          ]}
        />
      )}
    </div>
  );
}
