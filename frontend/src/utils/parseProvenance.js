/**
 * How a job's parsed table was produced (ws23 provenance on the job DTO:
 * parsedBy, parseStrategy, parseConfidence, parseFallbackReason, plus the
 * Wave 4 parseMinConfidence / parseLowConfidence verdict) in the words the
 * job page and the job list show. Vocabularies: docs/phase2/contracts.md §4.
 */

/**
 * FallbackReason → a short tag for the badge, and the server's own
 * description (domain/model/FallbackReason#description, kept in sync).
 */
export const FALLBACK_REASONS = {
  AI_DISABLED: { short: 'turned off', description: 'AI features are turned off' },
  NOT_CONFIGURED: { short: 'not configured', description: 'AI is not configured' },
  CIRCUIT_OPEN: { short: 'circuit open', description: 'AI temporarily unavailable' },
  QUOTA: { short: 'quota', description: 'AI quota reached' },
  ERROR: { short: 'error', description: 'AI error' },
  TIMEOUT: { short: 'timeout', description: 'AI did not answer in time' },
  OUTPUT_REJECTED: {
    short: 'answer withheld',
    description: 'AI answer withheld by the output scanner',
  },
  INVALID_OUTPUT: { short: 'unusable answer', description: 'AI answer was not a usable table' },
  PROMPT_REFUSED: {
    short: 'prompt refused',
    description: 'AI prompt refused by the privacy checks',
  },
  MODE_CODE_ONLY: { short: 'code only', description: 'code-only extraction' },
};

/** ParseStrategy → what it read, for the detail line. */
export const STRATEGY_LABELS = {
  NATIVE_SHEET: 'spreadsheet cells',
  STRUCTURED: 'structured file',
  MARKDOWN_TABLE: 'markdown table',
  ROW_LINE: 'row lines',
  DELIMITED: 'delimited text',
  FIXED_WIDTH: 'fixed-width columns',
  KEY_VALUE: 'label: value blocks',
  FIELD_REGEX: 'field patterns',
  LLM_SINGLE_PASS: 'single pass',
  LLM_BATCHED: 'batched',
};

/** The shipped extraction.code.minConfidence, for views that predate the verdict. */
export const DEFAULT_MIN_CONFIDENCE = 0.75;

/** "86%" for 0.857; null when there is no score. */
export function formatConfidence(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return null;
  return `${Math.round(Number(value) * 100)}%`;
}

/**
 * The badge for a parsed job, or null before parsing (and for jobs parsed
 * before provenance existed).
 *
 * @returns {{ label, short, tone, title }} — `label` is the full badge text
 *   ("Extracted by code — AI unavailable (quota)"), `short` the list-row tag
 *   ("Code"), `tone` a Tag tone, `title` the explanation.
 */
export function describeProvenance(job) {
  const parsedBy = job?.parsedBy;
  if (!parsedBy) return null;
  const reason = job.parseFallbackReason ? FALLBACK_REASONS[job.parseFallbackReason] : null;
  const reasonShort = reason?.short ?? job.parseFallbackReason?.toLowerCase().replace(/_/g, ' ');
  const reasonText = reason?.description ?? job.parseFallbackReason;
  const low = isLowConfidence(job);

  if (parsedBy === 'AI') {
    return {
      label: 'Extracted by AI',
      short: 'AI',
      tone: 'info',
      title: 'The AI read this document.',
    };
  }
  if (parsedBy === 'AI_PARTIAL') {
    return {
      label: reasonShort
        ? `Extracted by AI and code — AI unavailable for part (${reasonShort})`
        : 'Extracted by AI and code',
      short: low ? 'AI + code · low confidence' : 'AI + code',
      tone: low ? 'danger' : 'warning',
      title: `Part of this document was read by code${reasonText ? ` (${reasonText})` : ''}.`,
    };
  }
  // CODE
  if (job.parseFallbackReason === 'MODE_CODE_ONLY') {
    return {
      label: 'Extracted by code — code-only extraction',
      short: low ? 'Code · low confidence' : 'Code',
      tone: low ? 'danger' : 'neutral',
      title: 'The template (or the bank default) uses code-only extraction; the AI was not used.',
    };
  }
  if (reasonShort) {
    return {
      label: `Extracted by code — AI unavailable (${reasonShort})`,
      short: low ? 'Code · low confidence' : 'Code',
      tone: low ? 'danger' : 'warning',
      title: `Code extraction stood in for the AI: ${reasonText}.`,
    };
  }
  return {
    label: 'Extracted by code — structured file',
    short: 'Code',
    tone: 'neutral',
    title: 'The file was structured enough to read without the AI.',
  };
}

/**
 * A code-scored table below the bank's threshold — the server's verdict
 * when it gave one, else judged here against parseMinConfidence (or the
 * shipped 0.75).
 */
export function isLowConfidence(job) {
  if (!job || job.parsedBy === 'AI') return false;
  if (job.parseLowConfidence === true || job.parseLowConfidence === false) {
    return job.parseLowConfidence;
  }
  if (job.parseConfidence === null || job.parseConfidence === undefined) return false;
  const threshold = job.parseMinConfidence ?? DEFAULT_MIN_CONFIDENCE;
  return Number(job.parseConfidence) < Number(threshold);
}
