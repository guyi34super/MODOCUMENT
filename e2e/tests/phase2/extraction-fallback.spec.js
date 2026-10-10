// Phase 2 — AI first, automatic code fallback (SE4). Driven by the fake LLM
// modes (lib/llm.js): ok | error500 | error429 | timeout | badjson | badenvelope.
// Acceptance criteria: docs/phase2/test-plan.md §5.4.
//
// Input: a key:value free-text PDF (lib/phase2.js keyValuePdf) — no
// deterministic fast path reads it, so the parse step always asks the model
// first, and the code extractor's KEY_VALUE strategy can stand in for it.
// API contract (SE4, live): job DTO parsedBy, parseStrategy, parseConfidence,
// parseFallbackReason; template DTO extractionMode.
// UI contract (SE4, Wave 4, live): IngestionJobPage badge
// data-testid="parse-provenance" ("Extracted by code — AI unavailable
// (error)"), low-confidence callout data-testid="low-confidence"; job DTO
// parseMinConfidence / parseLowConfidence; TemplateEditorPage combobox
// "Extraction mode" with "Inherit bank default (…)", button "Save mode", and
// per-field hints behind "Edit hints for <field>" (Label pattern, …, "Save
// hints"); TemplateDto.defaultExtractionMode.
//
// Stack config (run-local.sh / compose): the AI read timeout is 5 s, so the
// `timeout` mode reaches the fallback inside a test. ORDER MATTERS: the
// circuit-breaker test (AC-EXT-11) runs last because it leaves the breaker
// OPEN for 120 s.
import { test, expect, openAs } from '../../lib/fixtures.js';
import { bankSetting, keyValueTemplate, putBankSetting, resetBankSetting, uploadAndSettle } from '../../lib/phase2.js';
import { BOOTSTRAP, OPERATOR } from '../../lib/seed.js';

const MIN_CONFIDENCE = 'extraction.code.minConfidence';

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

  test('AC-EXT-02b the job page shows the "Extracted by code" badge', async ({ page, operatorApi, llm }) => {
    const template = await keyValueTemplate(operatorApi);
    await llm.reset();
    await llm.setMode('error500');
    const job = await uploadAndSettle(operatorApi, template.templateId);
    expectExtractedByCode(job, 'ERROR');

    await openAs(page, OPERATOR(), `/ingestion/${job.ingestionJobId}`);
    await expect(page.getByTestId('parse-provenance')).toHaveText('Extracted by code — AI unavailable (error)');
    await expect(page.getByTestId('parse-confidence')).toContainText('%');
    await expect(page.getByTestId('low-confidence')).toHaveCount(0);
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

  test('AC-EXT-07 a low-confidence code result in CODE_ONLY is kept, flagged and called out', async ({
    page,
    adminApi,
    operatorApi,
  }) => {
    // Raise the bank's threshold (OPERATIONAL setting: applies at once) above
    // what the key:value strategy scores, so the CODE_ONLY result is "low".
    const before = await bankSetting(adminApi, MIN_CONFIDENCE);
    const raised = await putBankSetting(adminApi, MIN_CONFIDENCE, 0.95, { expectedVersion: before.version });
    expect(raised.status).toBe(200);
    try {
      const template = await keyValueTemplate(operatorApi, 'CODE_ONLY');
      const job = await uploadAndSettle(operatorApi, template.templateId);
      expect(job.currentStep).toBe('VALIDATED');
      expect(job.parsedBy).toBe('CODE');
      expect(job.parseFallbackReason).toBe('MODE_CODE_ONLY');
      expect(job.parseMinConfidence).toBeCloseTo(0.95, 5);
      expect(job.parseConfidence).toBeLessThan(0.95);
      expect(job.parseLowConfidence).toBe(true);

      await openAs(page, OPERATOR(), `/ingestion/${job.ingestionJobId}`);
      const callout = page.getByTestId('low-confidence');
      await expect(callout).toBeVisible();
      await expect(callout).toContainText('below this bank');
      await expect(callout).toContainText('95%');
    } finally {
      await resetBankSetting(adminApi, MIN_CONFIDENCE);
    }
  });

  test.fixme('AC-EXT-13 AI_PARTIAL: a failed batch is re-parsed by code, the rest stays AI (Wave 4)', async () => {
    // multi-batch document; llm.setMode('error500', { count: 1 }) -> parsedBy AI_PARTIAL.
  });

  test('AC-EXT-16 template extraction section: mode under maker/checker, per-field hints with field-level errors', async ({
    page,
    adminApi,
  }) => {
    const template = await keyValueTemplate(adminApi);
    const view = await adminApi.get(`/Templates/GetById/${template.templateId}`);
    expect(view.extractionMode ?? null).toBeNull();
    expect(view.defaultExtractionMode).toBe('AI_WITH_CODE_FALLBACK'); // the inherited bank default

    await openAs(page, BOOTSTRAP(), `/templates/${template.templateId}`);
    const section = page.getByTestId('template-extraction');
    const mode = section.getByRole('combobox', { name: 'Extraction mode' });
    await expect(mode).toBeVisible();
    await expect(mode.locator('option').first()).toHaveText(/^Inherit bank default \(.+\)$/);
    await mode.selectOption('CODE_ONLY');
    await section.getByRole('button', { name: 'Save mode' }).click();
    // Template changes are maker/checker in production: 202 -> "Pending approval".
    await expect(section.getByText('Pending approval')).toBeVisible();

    // Per-field hints: a broken pattern is refused with the field's own message.
    await section.getByRole('button', { name: 'Edit hints for Reference' }).click();
    const labelPattern = section.getByLabel('Label pattern');
    await labelPattern.fill('Ref([');
    await section.getByRole('button', { name: 'Save hints' }).click();
    await expect(labelPattern).toHaveAttribute('aria-invalid', 'true');
    await expect(section.getByRole('alert').filter({ hasText: /pattern/i }).first()).toBeVisible();

    await labelPattern.fill('Ref(erence)?');
    await section.getByLabel('Aliases').fill('Ref No\nInvoice');
    await section.getByRole('button', { name: 'Save hints' }).click();
    await expect(page.getByText('Extraction hints of "Reference" saved')).toBeVisible();
  });

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
