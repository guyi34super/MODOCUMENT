// Phase 2 — AI first, automatic code fallback (SE4). Driven by the fake LLM
// modes (lib/llm.js): ok | error500 | error429 | timeout | badjson | badenvelope.
// Acceptance criteria: docs/phase2/test-plan.md §5.4.
//
// Input: a key:value free-text PDF (lib/phase2.js keyValuePdf) — no
// deterministic fast path reads it, so the parse step always asks the model
// first, and the code extractor's KEY_VALUE strategy can stand in for it.
// API contract (SE4, live): job DTO parsedBy, parseStrategy, parseConfidence,
// parseFallbackReason; template DTO extractionMode.
// UI contract (SE4/SE3, Wave 4 — NOT merged yet, so those parts stay fixme):
// IngestionJobPage badge data-testid="parse-provenance" ("Extracted by code —
// AI unavailable (error)"), low-confidence callout data-testid="low-confidence",
// TemplateEditorPage role=combobox name="Extraction mode".
//
// Stack config (run-local.sh / compose): the AI read timeout is 5 s, so the
// `timeout` mode reaches the fallback inside a test. ORDER MATTERS: the
// circuit-breaker test (AC-EXT-11) runs last because it leaves the breaker
// OPEN for 120 s.
import { test, expect } from '../../lib/fixtures.js';
import { keyValueTemplate, uploadAndSettle } from '../../lib/phase2.js';

test.describe.configure({ mode: 'serial' });

function expectExtractedByCode(job, reason) {
  expect(job.status, job.errorMessage ?? '').not.toBe('FAILED');
  expect(job.currentStep).toBe('VALIDATED');
  expect(job.parsedBy).toBe('CODE');
  expect(job.parseStrategy).toBe('KEY_VALUE');
  expect(job.parseConfidence).toBeGreaterThanOrEqual(0.75);
  expect(job.parseFallbackReason).toBe(reason);
}

test.describe('code extraction fallback @phase2 @wave2', () => {
  test('AC-EXT-02 AI_WITH_CODE_FALLBACK: LLM 500 -> job VALIDATED, parsedBy CODE, reason ERROR', async ({
    operatorApi,
    llm,
  }) => {
    const template = await keyValueTemplate(operatorApi);
    await llm.reset(); // resets the mode too: set the mode after it
    await llm.setMode('error500');

    const job = await uploadAndSettle(operatorApi, template.templateId);

    expectExtractedByCode(job, 'ERROR');
    expect((await llm.stats()).byMode.error500, 'the model was tried first').toBeGreaterThan(0);
  });

  test.fixme('AC-EXT-02b the job page shows the "Extracted by code" badge (Wave 4 UI)', async () => {
    // waiting for SE4's IngestionJobPage badge (data-testid="parse-provenance").
  });

  test('AC-EXT-03 timeout and invalid JSON map to TIMEOUT and INVALID_OUTPUT', async ({ operatorApi, llm }) => {
    const template = await keyValueTemplate(operatorApi);

    await llm.setMode('timeout');
    const hung = await uploadAndSettle(operatorApi, template.templateId);
    expectExtractedByCode(hung, 'TIMEOUT');

    await llm.setMode('badjson');
    const garbled = await uploadAndSettle(operatorApi, template.templateId);
    expectExtractedByCode(garbled, 'INVALID_OUTPUT');
  });

  test('AC-EXT-04 CODE_ONLY never calls the LLM', async ({ operatorApi, llm }) => {
    const template = await keyValueTemplate(operatorApi, 'CODE_ONLY');
    expect(template.extractionMode).toBe('CODE_ONLY');
    await llm.reset();

    const job = await uploadAndSettle(operatorApi, template.templateId);

    expectExtractedByCode(job, 'MODE_CODE_ONLY');
    expect((await llm.stats()).calls).toBe(0);
  });

  test('AC-EXT-05 AI_ONLY fails as today when the LLM fails', async ({ operatorApi, llm }) => {
    const template = await keyValueTemplate(operatorApi, 'AI_ONLY');
    await llm.reset(); // resets the mode too: set the mode after it
    await llm.setMode('error500');

    const job = await uploadAndSettle(operatorApi, template.templateId);

    expect(job.status).toBe('FAILED');
    expect(job.parsedBy ?? null).toBeNull();
    expect(job.errorMessage).toBeTruthy();
    expect((await llm.stats()).calls).toBeGreaterThan(0);
  });

  test.fixme('AC-EXT-07 a low-confidence code result in CODE_ONLY is flagged LOW_CONFIDENCE (Wave 4 callout)', async () => {});

  test.fixme('AC-EXT-13 AI_PARTIAL: a failed batch is re-parsed by code, the rest stays AI (Wave 4)', async () => {
    // multi-batch document; llm.setMode('error500', { count: 1 }) -> parsedBy AI_PARTIAL.
  });

  test.fixme('AC-EXT-16 template extraction section saves mode and per-field aliases/patterns/date formats (Wave 4 UI)', async () => {});

  // LAST in the file: leaves the breaker OPEN for its 120 s open duration.
  test('AC-EXT-11 repeated LLM failures open the circuit breaker; later uploads fall back without calling the LLM', async ({
    operatorApi,
    llm,
  }) => {
    test.setTimeout(400_000);
    const template = await keyValueTemplate(operatorApi);
    await llm.setMode('error500');

    // The breaker opens after 5 consecutive transport/5xx failures (earlier
    // tests may already have counted some): keep failing until the parse
    // step reports CIRCUIT_OPEN.
    let opened = false;
    for (let i = 0; i < 7 && !opened; i += 1) {
      const job = await uploadAndSettle(operatorApi, template.templateId);
      expect(job.parsedBy).toBe('CODE');
      opened = job.parseFallbackReason === 'CIRCUIT_OPEN';
    }
    expect(opened, 'the breaker opened within 7 failing uploads').toBe(true);

    await llm.reset();
    await llm.setMode('error500');
    const skipped = await uploadAndSettle(operatorApi, template.templateId);
    expectExtractedByCode(skipped, 'CIRCUIT_OPEN');
    expect((await llm.stats()).calls, 'an open circuit makes no model call').toBe(0);
  });
});
