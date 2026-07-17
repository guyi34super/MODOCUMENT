import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { getTemplates } from '../api/cache';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, ErrorBanner, Spinner, StatCard } from '../components/ui';

function countBy(list, key) {
  return list.reduce((acc, item) => {
    const value = item[key] || 'UNKNOWN';
    acc[value] = (acc[value] || 0) + 1;
    return acc;
  }, {});
}

export default function DashboardPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [templates, fields, ingestionJobs, extractionJobs, imports] = await Promise.all([
        getTemplates(),
        api.get('/TemplateFields/GetAll'),
        api.get('/api/ingestion/jobs'),
        api.get('/api/extraction/jobs'),
        api.get('/ImportHistory/GetAll'),
      ]);
      setData({ templates, fields, ingestionJobs, extractionJobs, imports });
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (loading && !data) return <Spinner label="Loading dashboard…" />;

  const ingestionByStatus = data ? countBy(data.ingestionJobs, 'status') : {};
  const extractionByStatus = data ? countBy(data.extractionJobs, 'status') : {};
  const failedCount = (ingestionByStatus.FAILED || 0) + (extractionByStatus.FAILED || 0);
  const recentImports = data
    ? [...data.imports]
        .sort((a, b) => (b.importDate || '').localeCompare(a.importDate || ''))
        .slice(0, 8)
    : [];

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'Dashboard' }]}
        title="Dashboard"
        subtitle="A single view of templates, uploads and extractions"
        actions={<Button variant="secondary" onClick={load}>Refresh</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {data && (
        <>
          <div className="mb-6 flex flex-wrap gap-4">
            <StatCard label="Templates" value={data.templates.length} hint="Defined document templates" />
            <StatCard label="Template Fields" value={data.fields.length} hint="Across all templates" />
            <StatCard
              label="Ingestion Jobs"
              value={data.ingestionJobs.length}
              hint={`${ingestionByStatus.COMPLETED || 0} completed · ${ingestionByStatus.FAILED || 0} failed`}
            />
            <StatCard
              label="Extraction Jobs"
              value={data.extractionJobs.length}
              hint={`${extractionByStatus.RUNNING || 0} running · ${extractionByStatus.COMPLETED || 0} completed`}
            />
          </div>

          <div className="mb-8">
            {failedCount === 0 ? (
              <Callout variant="success" title="All clear">
                No failed jobs — every ingestion and extraction pipeline is healthy.
              </Callout>
            ) : (
              <Callout variant="error" title={`${failedCount} failed job${failedCount > 1 ? 's' : ''}`}>
                Check the <Link to="/ingestion" className="underline">file uploads</Link> and{' '}
                <Link to="/extraction/jobs" className="underline">extraction jobs</Link> pages to
                resume or re-run them.
              </Callout>
            )}
          </div>

          <h2 className="mb-3 text-xl font-normal">Recent imports</h2>
          <DataTable
            columns={[
              { key: 'importHistoryId', label: 'ID' },
              { key: 'fileName', label: 'File' },
              {
                key: 'templateId',
                label: 'Template',
                render: (row) =>
                  data.templates.find((t) => t.templateId === row.templateId)?.name ??
                  row.templateId,
              },
              { key: 'importDate', label: 'Imported', render: (row) => row.importDate?.replace('T', ' ').slice(0, 19) || '—' },
            ]}
            rows={recentImports}
            emptyMessage="No imports yet — upload a file to get started."
          />
        </>
      )}
    </div>
  );
}
