import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import { createJob, listJobs, listSources } from '../api/extraction';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function ExtractionJobsPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const [jobs, setJobs] = useState(null);
  const [sources, setSources] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ sourceId: '', templateId: '', dataSpec: '', autoStart: true });

  const load = useCallback(async () => {
    try {
      const [jobList, sourceList, templateList] = await Promise.all([
        listJobs(),
        listSources(),
        getTemplates(),
      ]);
      setJobs(jobList);
      setSources(sourceList);
      setTemplates(templateList);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const job = await toast.promise(
        createJob({
          sourceId: form.sourceId,
          templateId: form.templateId ? Number(form.templateId) : null,
          dataSpec: form.dataSpec || null,
          autoStart: form.autoStart,
        }),
        { loading: 'Creating extraction job…', success: 'Extraction job created' }
      );
      navigate(`/extraction/jobs/${job.id}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Extraction' }, { label: 'Jobs' }]}
        title="Extraction Jobs"
        subtitle="Crawl a source, collect records, then bridge them into the ingestion pipeline"
        actions={<Button variant="secondary" onClick={load}>Refresh</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Card className="mb-6">
        <h2 className="mb-3 text-lg font-normal">New extraction job</h2>
        <form onSubmit={submit} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label="Source">
              <select required className={inputClass} value={form.sourceId} onChange={(e) => setForm((f) => ({ ...f, sourceId: e.target.value }))}>
                <option value="">Select a source…</option>
                {sources.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.type})
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Template (optional)">
              <select className={inputClass} value={form.templateId} onChange={(e) => setForm((f) => ({ ...f, templateId: e.target.value }))}>
                <option value="">None</option>
                {templates.map((t) => (
                  <option key={t.templateId} value={t.templateId}>
                    {t.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Data spec (optional)">
              <input className={inputClass} value={form.dataSpec} onChange={(e) => setForm((f) => ({ ...f, dataSpec: e.target.value }))} />
            </Field>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={form.autoStart}
              onChange={(e) => setForm((f) => ({ ...f, autoStart: e.target.checked }))}
            />
            Start immediately
          </label>
          <Button type="submit" disabled={busy || sources.length === 0}>
            {busy ? 'Creating…' : 'Create job'}
          </Button>
          {sources.length === 0 && sources && (
            <p className="text-sm text-muted">
              You need an <Link to="/extraction/sources" className="text-accent hover:underline">extraction source</Link> first.
            </p>
          )}
        </form>
      </Card>

      <h2 className="mb-3 text-xl font-normal">Jobs</h2>
      {!jobs ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            {
              key: 'id',
              label: 'ID',
              render: (row) => (
                <Link to={`/extraction/jobs/${row.id}`} className="font-mono text-xs text-accent hover:underline">
                  {row.id.slice(0, 12)}…
                </Link>
              ),
            },
            {
              key: 'sourceId',
              label: 'Source',
              render: (row) => sources.find((s) => s.id === row.sourceId)?.name ?? row.sourceId,
            },
            { key: 'status', label: 'Status', render: (row) => <Badge status={row.status} /> },
            {
              key: 'records',
              label: 'Processed records',
              render: (row) => row.counters?.recordsProcessed ?? 0,
            },
            { key: 'createdDate', label: 'Created', render: (row) => row.createdDate?.replace('T', ' ').slice(0, 19) || '—' },
          ]}
          rows={jobs}
          emptyMessage="No extraction jobs yet."
        />
      )}
    </div>
  );
}
