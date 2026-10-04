import { useCallback, useEffect, useState } from 'react';
import * as retentionApi from '../api/retention';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import { Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';

const CATEGORY_LABELS = {
  uploadedFiles: 'Uploaded files',
  ingestionJobs: 'Ingestion job row data',
  exportArchives: 'Export archive copies',
  importDetailRows: 'Import step payloads',
  extractionRawBodies: 'Extraction raw bodies',
  extractionProcessedFields: 'Extraction processed fields',
  docInstances: 'Document instances',
  docContentBlocks: 'Document content blocks',
  docImageAssets: 'Document image assets',
};

const STORE_LABELS = {
  ingestionJobs: 'Ingestion job rows',
  importDetails: 'Import step payloads',
  extractionRawRecords: 'Extraction raw captures',
  extractionProcessedRecords: 'Extraction processed records',
  uploadedFiles: 'Uploaded files',
  docContentBlocks: 'Document content',
};

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** The ws14 retention panel: policy, last run evidence, manual trigger. */
export function RetentionPanel({ onError }) {
  const toast = useToast();
  const [status, setStatus] = useState(null);
  const [running, setRunning] = useState(false);

  const load = useCallback(() => {
    retentionApi
      .retentionStatus()
      .then(setStatus)
      .catch((err) => onError(err.message));
  }, [onError]);

  useEffect(() => {
    load();
  }, [load]);

  const runNow = async () => {
    if (
      !window.confirm(
        'Run the retention purge now? Customer data past the retention period ' +
          'will be permanently deleted for every bank (same as the nightly run).'
      )
    ) {
      return;
    }
    setRunning(true);
    try {
      await toast.promise(retentionApi.runRetention(), {
        loading: 'Purging…',
        success: 'Retention purge completed',
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setRunning(false);
    }
  };

  return (
    <Card className="mb-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-normal">Retention purge</h2>
        <Button onClick={runNow} disabled={running}>
          {running ? 'Running…' : 'Run purge now'}
        </Button>
      </div>
      {!status && <Spinner label="Loading retention status…" />}
      {status && (
        <div className="space-y-3 text-sm">
          <p className="text-muted">
            Customer data is deleted automatically after{' '}
            <span className="font-medium text-ink">{status.retentionDays} days</span>. Scheduled
            run: {status.enabled ? 'enabled (daily)' : 'disabled'}. Full schedule:
            docs/security/retention-and-dsr.md.
          </p>
          {!status.lastRun && <p className="text-muted">No purge has run for this bank yet.</p>}
          {status.lastRun && (
            <div>
              <p className="mb-2">
                Last run: <span className="font-medium">{formatDate(status.lastRun.occurredAt)}</span>
              </p>
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-2 py-1.5">Category</th>
                    <th className="px-2 py-1.5 text-right">Purged</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(CATEGORY_LABELS).map(([key, label]) => (
                    <tr key={key} className="border-b border-line/60 last:border-0">
                      <td className="px-2 py-1.5">{label}</td>
                      <td className="px-2 py-1.5 text-right">
                        {status.lastRun.counts?.[key] ?? 0}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}

/** The ws14 DSR panel: search → results → export ZIP → type-to-confirm erase. */
export function DsrPanel({ onError }) {
  const toast = useToast();
  const [identifier, setIdentifier] = useState('');
  const [result, setResult] = useState(null);
  const [searched, setSearched] = useState('');
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);

  const search = async (e) => {
    e?.preventDefault();
    const subject = identifier.trim();
    if (!subject) return;
    setBusy(true);
    setResult(null);
    try {
      const found = await retentionApi.dsrSearch(subject);
      setResult(found);
      setSearched(subject);
      setConfirmText('');
    } catch (err) {
      onError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const exportZip = async () => {
    try {
      await toast.promise(retentionApi.dsrExport(searched), {
        loading: 'Assembling export…',
        success: 'DSR export downloaded',
      });
    } catch {
      // The error toast is already shown.
    }
  };

  const erase = async () => {
    setBusy(true);
    try {
      const outcome = await toast.promise(retentionApi.dsrErase(searched), {
        loading: 'Erasing…',
        success: 'Subject data erased',
      });
      setConfirmText('');
      // Re-run the search: zero hits is the closure evidence.
      const found = await retentionApi.dsrSearch(searched);
      setResult(found);
      return outcome;
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const eraseArmed = result && result.totalMatches > 0 && confirmText === searched;

  return (
    <Card>
      <h2 className="mb-1 text-lg font-normal">Data subject requests</h2>
      <p className="mb-4 text-sm text-muted">
        Search by a value the subject is known by (NIC, account number, email, full name), export
        everything found as a ZIP (JSON + readable text), then erase. Every step is audited.
      </p>
      <form onSubmit={search} className="mb-4 flex items-end gap-3">
        <div className="grow">
          <Field label="Subject identifier">
            <input
              className={inputClass}
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              placeholder="e.g. A1234567890123"
            />
          </Field>
        </div>
        <Button type="submit" disabled={busy || identifier.trim().length < 3}>
          Search
        </Button>
      </form>

      {result && (
        <div className="space-y-4">
          <p className="text-sm">
            <span className="font-medium">{result.totalMatches}</span> occurrence(s) of{' '}
            <span className="font-mono">{searched}</span> across the bank&apos;s stores.
          </p>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th className="px-2 py-1.5">Store</th>
                <th className="px-2 py-1.5 text-right">Matches</th>
                <th className="px-2 py-1.5">References</th>
              </tr>
            </thead>
            <tbody>
              {result.stores.map((store) => (
                <tr key={store.store} className="border-b border-line/60 last:border-0">
                  <td className="px-2 py-1.5">{STORE_LABELS[store.store] || store.store}</td>
                  <td className="px-2 py-1.5 text-right">{store.matches}</td>
                  <td className="px-2 py-1.5 text-muted">
                    {store.hits
                      .slice(0, 5)
                      .map((hit) => `${hit.ref}${hit.label ? ` (${hit.label})` : ''}`)
                      .join(', ')}
                    {store.hits.length > 5 && ` +${store.hits.length - 5} more`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {result.totalMatches > 0 && (
            <div className="flex items-center gap-3">
              <Button variant="secondary" onClick={exportZip}>
                Export ZIP
              </Button>
            </div>
          )}

          {result.totalMatches > 0 && (
            <div className="rounded border border-line p-3">
              <p className="mb-2 text-sm font-medium">Erase this subject&apos;s data</p>
              <p className="mb-3 text-sm text-muted">
                Removes the matching rows from every store and deletes the original uploads that
                contain them. This cannot be undone — export first. Type the identifier to confirm.
              </p>
              <div className="flex items-end gap-3">
                <div className="grow">
                  <Field label={`Type "${searched}" to confirm`}>
                    <input
                      className={inputClass}
                      value={confirmText}
                      onChange={(e) => setConfirmText(e.target.value)}
                      aria-label="Erase confirmation"
                    />
                  </Field>
                </div>
                <Button variant="danger" onClick={erase} disabled={!eraseArmed || busy}>
                  {busy ? 'Working…' : 'Erase permanently'}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}

/**
 * ws14 privacy administration: the retention purge evidence/trigger and the
 * data-subject request workflow. The server enforces user:admin on every
 * call; this page is rendered for ADMINs of the bank only.
 */
export default function AdminRetentionPage() {
  const [error, setError] = useState('');

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Privacy & retention' }]}
        title="Privacy & retention"
        subtitle="30-day retention purge and data-subject requests for your bank"
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <RetentionPanel onError={setError} />
      <DsrPanel onError={setError} />
    </div>
  );
}
