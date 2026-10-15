import { useCallback, useEffect, useState } from 'react';
import {
  downloadReviewFile,
  listPendingReleases,
  rejectInstance,
  releaseInstance,
} from '../api/doccreation';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import { Button, Card, ErrorBanner, Spinner, inputClass } from '../components/ui';

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/**
 * The document release queue (ws11, gate B8 / C10). The server enforces
 * everything — this page renders the bank's SUBMITTED documents for holders
 * of doc:release (ADMIN, APPROVER) WITH their content blocks and an
 * "AI-composed" marker: releasing an AI-composed document is the recorded
 * human review of what the model wrote. Releasing your own document (as
 * author or submitter) is a hard 409 server-side; the error surfaces here.
 */
export default function PendingReleasesPage() {
  const toast = useToast();
  const [entries, setEntries] = useState(null);
  const [error, setError] = useState('');
  const [comments, setComments] = useState({});

  const load = useCallback(() => {
    setEntries(null);
    listPendingReleases()
      .then(setEntries)
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const commentFor = (id) => comments[id] || '';

  const decide = async (entry, decision) => {
    const call =
      decision === 'release'
        ? releaseInstance(entry.instanceId, commentFor(entry.instanceId))
        : rejectInstance(entry.instanceId, commentFor(entry.instanceId));
    try {
      await toast.promise(call, {
        loading: decision === 'release' ? 'Releasing…' : 'Rejecting…',
        success:
          decision === 'release'
            ? `"${entry.title}" released`
            : `"${entry.title}" returned to its author`,
      });
      load();
    } catch (err) {
      // The toast already shows it; keep the SoD refusal visible on the page
      // too — "you cannot release your own report" should not scroll away.
      setError(err.message);
    }
  };

  const download = async (entry) => {
    try {
      await toast.promise(
        () => downloadReviewFile(entry.instanceId, 'pdf', `${entry.title}.pdf`),
        { loading: 'Preparing download…', success: 'Download started', error: (err) => err.message }
      );
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Releases' }]}
        title="Pending releases"
        subtitle="Documents awaiting release — a report's author cannot release it, review is yours"
        actions={
          <Button variant="secondary" onClick={load}>
            Refresh
          </Button>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!entries && !error && <Spinner label="Loading pending releases…" />}
      {entries && entries.length === 0 && (
        <Card>
          <p className="text-sm text-muted">Nothing is awaiting release.</p>
        </Card>
      )}
      <div className="space-y-3">
        {entries &&
          entries.map((entry) => (
            <Card key={entry.instanceId}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-ink">
                    #{entry.instanceId} · {entry.title}
                    {entry.aiComposed && (
                      <span className="inline-block rounded-full bg-violet-100 px-2.5 py-0.5 text-xs font-medium text-violet-800">
                        AI-composed
                      </span>
                    )}
                  </p>
                  <p className="mt-1 text-xs text-muted">
                    Blueprint {entry.blueprintName || '—'} · by{' '}
                    {entry.ownerEmail || `user ${entry.ownerUserId}`} · submitted{' '}
                    {formatDate(entry.submittedAt)}
                  </p>
                  {entry.aiComposed && (
                    <p className="mt-1 text-xs text-muted">
                      The content below was composed by AI — releasing it records your human
                      review.
                    </p>
                  )}
                </div>
                <Button size="sm" variant="secondary" onClick={() => download(entry)}>
                  Download PDF
                </Button>
              </div>

              {/* C10: the approver sees the content itself, per section. */}
              <div className="mt-3 space-y-2 border-t border-line/60 pt-3">
                {entry.sections
                  .filter((section) => section.blocks.length > 0)
                  .map((section) => (
                    <div key={section.sectionId}>
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted">
                        {section.title}
                      </p>
                      <ul className="mt-1 space-y-1">
                        {section.blocks.map((block) => (
                          <li key={block.blockId} className="text-sm whitespace-pre-wrap">
                            {block.blockType === 'PARAGRAPH'
                              ? block.text
                              : `[image] ${block.assetFileName || ''}${
                                  block.caption ? ` — ${block.caption}` : ''
                                }`}
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                {entry.sections.every((section) => section.blocks.length === 0) && (
                  <p className="text-sm text-muted">No content blocks were added.</p>
                )}
              </div>

              <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line/60 pt-3">
                <input
                  aria-label={`Review comment for document ${entry.instanceId}`}
                  className={`${inputClass} max-w-72`}
                  placeholder="Review comment (recorded)"
                  value={commentFor(entry.instanceId)}
                  onChange={(e) =>
                    setComments({ ...comments, [entry.instanceId]: e.target.value })
                  }
                />
                <Button size="sm" onClick={() => decide(entry, 'release')}>
                  Release
                </Button>
                <Button size="sm" variant="danger" onClick={() => decide(entry, 'reject')}>
                  Reject
                </Button>
              </div>
            </Card>
          ))}
      </div>
    </div>
  );
}
