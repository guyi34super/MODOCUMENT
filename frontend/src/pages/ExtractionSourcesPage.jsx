import { useCallback, useEffect, useState } from 'react';
import { getTemplates } from '../api/cache';
import { SOURCE_TYPES, createSource, deleteSource, listSources } from '../api/extraction';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const CRAWL_DEPTHS = [
  { value: 0, label: 'Seed pages only' },
  { value: 1, label: 'Seeds + pages they link to' },
  { value: 2, label: 'Seeds + 2 link levels' },
  { value: 3, label: 'Seeds + 3 link levels' },
];

const emptyForm = {
  name: '',
  type: 'WEBSITE',
  dataSpec: '',
  templateId: '',
  termsAcknowledged: false,
  seedUrls: '',
  allowedDomains: '',
  maxDepth: 2,
  maxPages: 50,
  renderJs: false,
  baseUrl: '',
  path: '',
  recordsJsonPath: '',
  jdbcUrl: '',
  table: '',
  columns: '',
};

function buildPayload(form) {
  const payload = {
    name: form.name,
    type: form.type,
    termsAcknowledged: form.termsAcknowledged,
    dataSpec: form.dataSpec || null,
    templateId: form.templateId ? Number(form.templateId) : null,
  };
  if (form.type === 'WEBSITE') {
    payload.web = {
      seedUrls: form.seedUrls.split('\n').map((s) => s.trim()).filter(Boolean),
      allowedDomains: form.allowedDomains.split(',').map((s) => s.trim()).filter(Boolean),
      maxDepth: Number(form.maxDepth),
      maxPages: Number(form.maxPages),
      renderJs: form.renderJs,
    };
  } else if (form.type === 'REST_API') {
    payload.api = {
      baseUrl: form.baseUrl,
      path: form.path,
      recordsJsonPath: form.recordsJsonPath || null,
    };
  } else {
    payload.db = {
      jdbcUrl: form.jdbcUrl,
      table: form.table,
      columns: form.columns.split(',').map((s) => s.trim()).filter(Boolean),
    };
  }
  return payload;
}

export default function ExtractionSourcesPage() {
  const toast = useToast();
  const [sources, setSources] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [sourceList, templateList] = await Promise.all([listSources(), getTemplates()]);
      setSources(sourceList);
      setTemplates(templateList);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const set = (key) => (e) =>
    setForm((f) => ({
      ...f,
      [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value,
    }));

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(createSource(buildPayload(form)), {
        loading: 'Creating source…',
        success: `Source "${form.name}" created`,
      });
      setForm(emptyForm);
      setShowForm(false);
      await load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const remove = async (source) => {
    if (!window.confirm(`Delete source "${source.name}"?`)) return;
    try {
      await toast.promise(deleteSource(source.id), {
        loading: 'Deleting source…',
        success: `Source "${source.name}" deleted`,
      });
      await load();
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Extraction' }, { label: 'Sources' }]}
        title="Extraction Sources"
        subtitle="Websites, REST APIs and databases you extract data from"
        actions={
          <>
            <Button variant="secondary" onClick={load}>Refresh</Button>
            <Button onClick={() => setShowForm((v) => !v)}>
              {showForm ? 'Close form' : 'New source'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {showForm && (
        <Card className="mb-6">
          <h2 className="mb-3 text-lg font-normal">New source</h2>
          <form onSubmit={submit} className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Name">
                <input required className={inputClass} value={form.name} onChange={set('name')} />
              </Field>
              <Field label="Type">
                <select className={inputClass} value={form.type} onChange={set('type')}>
                  {SOURCE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            {form.type === 'WEBSITE' && (
              <>
                <Field label="Seed URLs" hint="One URL per line. Crawling honors robots.txt.">
                  <textarea
                    required
                    rows={3}
                    className={inputClass}
                    value={form.seedUrls}
                    onChange={set('seedUrls')}
                  />
                </Field>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <Field label="Allowed domains" hint="Comma-separated allow-list.">
                    <input
                      required
                      className={inputClass}
                      placeholder="example.com"
                      value={form.allowedDomains}
                      onChange={set('allowedDomains')}
                    />
                  </Field>
                  <Field label="Crawl depth" hint="How far links are followed from the seed pages.">
                    <select className={inputClass} value={form.maxDepth} onChange={set('maxDepth')}>
                      {CRAWL_DEPTHS.map((d) => (
                        <option key={d.value} value={d.value}>
                          {d.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Max pages">
                    <input type="number" min="1" className={inputClass} value={form.maxPages} onChange={set('maxPages')} />
                  </Field>
                </div>
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={form.renderJs} onChange={set('renderJs')} />
                  Render JavaScript (needs Playwright browsers on the server)
                </label>
              </>
            )}

            {form.type === 'REST_API' && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <Field label="Base URL">
                  <input required className={inputClass} value={form.baseUrl} onChange={set('baseUrl')} />
                </Field>
                <Field label="Path">
                  <input required className={inputClass} value={form.path} onChange={set('path')} />
                </Field>
                <Field label="Records JSON path" hint="e.g. $.data.items">
                  <input className={inputClass} value={form.recordsJsonPath} onChange={set('recordsJsonPath')} />
                </Field>
              </div>
            )}

            {form.type === 'DATABASE' && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <Field label="JDBC URL" hint="Without credentials — passed per run.">
                  <input required className={inputClass} value={form.jdbcUrl} onChange={set('jdbcUrl')} />
                </Field>
                <Field label="Table">
                  <input required className={inputClass} value={form.table} onChange={set('table')} />
                </Field>
                <Field label="Columns" hint="Comma-separated.">
                  <input className={inputClass} value={form.columns} onChange={set('columns')} />
                </Field>
              </div>
            )}

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Template (optional)" hint="Its fields become the data spec when the text below is blank.">
                <select className={inputClass} value={form.templateId} onChange={set('templateId')}>
                  <option value="">None</option>
                  {templates.map((t) => (
                    <option key={t.templateId} value={t.templateId}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Data spec (optional)" hint="Describe the data you want, e.g. 'product name, price, SKU'. Leave blank to derive it from the selected template's fields.">
                <input className={inputClass} value={form.dataSpec} onChange={set('dataSpec')} />
              </Field>
            </div>

            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                required
                checked={form.termsAcknowledged}
                onChange={set('termsAcknowledged')}
                className="mt-0.5"
              />
              <span>
                I confirm I'm allowed to extract from this source and accept its terms of use.
                <span className="text-danger-ink"> (required)</span>
              </span>
            </label>

            <Button type="submit" disabled={busy}>
              {busy ? 'Creating…' : 'Create source'}
            </Button>
          </form>
        </Card>
      )}

      {!sources ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            { key: 'name', label: 'Name' },
            { key: 'type', label: 'Type' },
            {
              key: 'detail',
              label: 'Target',
              render: (row) =>
                row.web?.seedUrls?.join(', ') || row.api?.baseUrl || row.db?.jdbcUrl || '—',
            },
            {
              key: 'dataSpec',
              label: 'Data spec',
              render: (row) =>
                row.dataSpec ||
                (row.templateId != null
                  ? `From template: ${templates.find((t) => t.templateId === row.templateId)?.name ?? row.templateId}`
                  : '—'),
            },
            {
              key: 'actions',
              label: '',
              render: (row) => (
                <div className="text-right">
                  <Button variant="danger" onClick={() => remove(row)}>
                    Delete
                  </Button>
                </div>
              ),
            },
          ]}
          rows={sources}
          emptyMessage="No sources yet — create one to start extracting."
        />
      )}
    </div>
  );
}
