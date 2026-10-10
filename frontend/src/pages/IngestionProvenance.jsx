import { Callout, Tag } from '../components/ui';
import {
  DEFAULT_MIN_CONFIDENCE,
  STRATEGY_LABELS,
  describeProvenance,
  formatConfidence,
  isLowConfidence,
} from '../utils/parseProvenance';

/**
 * The job page's provenance line (ws23 / AC-EXT-02b): who read the
 * document — "Extracted by code — AI unavailable (quota)" — with the
 * strategy and, for code, the confidence. Nothing before the job is parsed.
 */
export function ParseProvenanceBadge({ job }) {
  const provenance = describeProvenance(job);
  if (!provenance) return null;
  const strategy = job.parseStrategy
    ? STRATEGY_LABELS[job.parseStrategy] ?? job.parseStrategy.toLowerCase().replace(/_/g, ' ')
    : null;
  const confidence = job.parsedBy !== 'AI' ? formatConfidence(job.parseConfidence) : null;
  return (
    <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
      <Tag tone={provenance.tone} title={provenance.title}>
        <span data-testid="parse-provenance">{provenance.label}</span>
      </Tag>
      {strategy && <span>Read as {strategy}</span>}
      {confidence && (
        <span data-testid="parse-confidence">
          Confidence {confidence}
        </span>
      )}
      <span className="sr-only">{provenance.title}</span>
    </div>
  );
}

/**
 * AC-EXT-07: a code extraction below the bank's threshold (only code-only
 * mode lets one through, as LOW_CONFIDENCE) — the rows need checking
 * against the document before anyone relies on them.
 */
export function LowConfidenceCallout({ job }) {
  if (!isLowConfidence(job)) return null;
  const score = formatConfidence(job.parseConfidence);
  const threshold = formatConfidence(job.parseMinConfidence ?? DEFAULT_MIN_CONFIDENCE);
  return (
    <div className="mb-5" data-testid="low-confidence" role="note" aria-label="Low-confidence extraction">
      <Callout variant="error" title="Low-confidence extraction — check the rows">
        Code extraction scored {score}, below this bank&apos;s {threshold} threshold.
        {job.parseFallbackReason === 'MODE_CODE_ONLY'
          ? ' It was kept only because this template uses code-only extraction.'
          : ''}{' '}
        Compare the parsed rows with the document before you continue; columns may be
        misaligned or values missing. Adding aliases, patterns or date formats to the
        template&apos;s fields usually raises the score.
      </Callout>
    </div>
  );
}
