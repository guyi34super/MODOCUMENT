// Phase 2 — AI first, automatic code fallback (SE4). Driven by the fake LLM
// modes (lib/llm.js): ok | error500 | error429 | timeout | badjson | badenvelope.
// Acceptance criteria: docs/phase2/test-plan.md §5.4.
//
// Ready-made inputs: fixtures/pdf.js writes free-text PDFs (the deterministic
// materializer cannot read them, so they reach the parse step's AI path —
// see SMK-ING-02 for the working ok-mode baseline).
// API contract (SE4): job DTO gains parsedBy, parseStrategy, parseConfidence,
// parseFallbackReason; template DTO gains extractionMode.
// UI contract (SE4/SE3): IngestionJobPage badge data-testid="parse-provenance"
// with text like "Extracted by code — AI unavailable (error)"; low-confidence
// callout data-testid="low-confidence"; TemplateEditorPage section
// role=combobox name="Extraction mode".
//
// NOTE for SE4: the AI client has no explicit retry/timeout configuration, so
// Spring AI 1.0 defaults apply (10 attempts, 2 s x5 backoff up to 3 min on
// 5xx; no read timeout). With those, error500 reaches the fallback only after
// ~19 min and `timeout` never does — set spring.ai.retry.* and a read timeout
// (or bypass the RetryTemplate) as part of the circuit-breaker work.
import { test } from '../../lib/fixtures.js';

test.describe('code extraction fallback @phase2 @wave2', () => {
  test.fixme('AC-EXT-02 AI_WITH_CODE_FALLBACK: LLM 500 -> job VALIDATED, parsedBy CODE, reason ERROR, badge shown', async () => {
    // llm.setMode('error500'); upload key:value PDF -> waitForJob VALIDATED;
    // job.parsedBy === 'CODE', job.parseFallbackReason === 'ERROR'; badge visible;
    // audit INGESTION_PARSE_FALLBACK.
  });

  test.fixme('AC-EXT-03 timeout and invalid JSON map to TIMEOUT and INVALID_OUTPUT', async () => {
    // llm.setMode('timeout') -> reason TIMEOUT; llm.setMode('badjson') -> reason INVALID_OUTPUT.
  });

  test.fixme('AC-EXT-04 CODE_ONLY never calls the LLM', async () => {
    // template extractionMode CODE_ONLY; llm.reset(); upload -> VALIDATED;
    // (await llm.stats()).calls === 0; reason MODE_CODE_ONLY.
  });

  test.fixme('AC-EXT-05 AI_ONLY fails as today when the LLM fails', async () => {
    // extractionMode AI_ONLY + error500 -> job FAILED, parsedBy null, no fallback audit.
  });

  test.fixme('AC-EXT-07 a low-confidence code result in CODE_ONLY is flagged LOW_CONFIDENCE', async () => {});

  test.fixme('AC-EXT-11 five LLM failures open the circuit breaker; later uploads fall back without calling the LLM', async () => {
    // error500 x5 -> AI_CIRCUIT_OPENED audit; next upload: stats.calls unchanged, reason CIRCUIT_OPEN.
  });

  test.fixme('AC-EXT-13 AI_PARTIAL: a failed batch is re-parsed by code, the rest stays AI', async () => {
    // multi-batch document; llm.setMode('error500', { count: 1 }) -> parsedBy AI_PARTIAL.
  });

  test.fixme('AC-EXT-16 template extraction section saves mode and per-field aliases/patterns/date formats', async () => {});
});
