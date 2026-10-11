// Optimization round (v1.2.0) — QA's end-to-end checks of the Wave-1 changes
// that only show on the running stack. Test plan:
// docs/qa/optimization-test-plan.md (L1, P7/A18, V9, X3).
//
//   L1  stale-chunk recovery: a lazy route chunk that 404s (an old tab after a
//       deploy) reloads the page ONCE (vite:preloadError handler), never in a
//       loop; when the chunk is still missing the user must see an error,
//       not a blank page (BUG-09, fixme).
//   P7  AI result cache: the same document re-uploaded against the same
//       template makes zero model calls (fake-LLM /__stats). Never audited
//       (A18) and never shared across banks: AiResultCacheRlsPgIT (a template
//       is bank-scoped, so two banks never compute the same key here).
//   V9  audit viewer isolation: bank A's admin cannot see bank B's events,
//       by API or on /admin/audit, even when filtering by bank B's actor.
//   X3  the dashboard's numbers (one /api/dashboard/summary call) equal what
//       the four pre-v1.2.0 lists say.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { sessionFor } from '../../lib/api.js';
import { keyValuePdf, keyValueTemplate, onboardBank, uploadAndSettle } from '../../lib/phase2.js';
import { BOOTSTRAP, OPERATOR } from '../../lib/seed.js';

const AUDIT_CHUNK = '**/assets/AuditLogPage-*.js';

/** Counts full document loads of the main frame from now on. */
function countDocumentLoads(page) {
  const counter = { loads: 0 };
  page.on('request', (request) => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) counter.loads += 1;
  });
  return counter;
}

test.describe('optimization round @v120', () => {
  test('OPT-L1 a lazy chunk that 404s once reloads the page once and the route then works', async ({ page }) => {
    await openAs(page, BOOTSTRAP(), '/');
    await expect(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    let refused = 0;
    await page.route(AUDIT_CHUNK, async (route) => {
      if (refused === 0) {
        refused += 1;
        await route.fulfill({ status: 404, contentType: 'text/plain', body: 'gone' });
      } else {
        await route.continue();
      }
    });
    const documents = countDocumentLoads(page);

    await page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Audit log' }).click();

    await expect(page.getByRole('heading', { name: 'Audit log', level: 1 })).toBeVisible({ timeout: 30_000 });
    await expect(page).toHaveURL(/\/admin\/audit$/);
    expect(refused, 'the old chunk was asked for and refused').toBe(1);
    expect(documents.loads, 'exactly one reload onto the current build').toBe(1);
  });

  test('OPT-L1b a chunk still missing after the reload does not reload in a loop', async ({ page }) => {
    await openAs(page, BOOTSTRAP(), '/');
    await expect(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    await page.evaluate(() => sessionStorage.removeItem('modoc.chunkReloadAt'));
    let refused = 0;
    await page.route(AUDIT_CHUNK, (route) => {
      refused += 1;
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'gone' });
    });
    const documents = countDocumentLoads(page);

    await page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Audit log' }).click();
    await expect.poll(() => refused, { timeout: 30_000 }).toBeGreaterThanOrEqual(2);
    // Give a loop every chance to show itself.
    await page.waitForTimeout(3000);

    expect(documents.loads, 'one reload, then the guard stops').toBe(1);
    expect(refused, 'the chunk was asked for once per document').toBe(2);
  });

  // BUG-09 (P3, SE3): there is no React error boundary. When the chunk is still
  // missing after the one guarded reload, the rejected lazy import unmounts the
  // whole tree: the user is left on a blank white page with no message and no
  // way back but the address bar. Test plan L1 expects the error to surface.
  test.fixme('OPT-L1c BUG-09 a chunk still missing after the reload shows an error, not a blank page', async ({ page }) => {
    await openAs(page, BOOTSTRAP(), '/');
    await expect(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    await page.evaluate(() => sessionStorage.removeItem('modoc.chunkReloadAt'));
    await page.route(AUDIT_CHUNK, (route) => route.fulfill({ status: 404, contentType: 'text/plain', body: 'gone' }));

    await page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Audit log' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('#root')).not.toBeEmpty();
  });

  test('OPT-P7 the same document re-uploaded is served from the AI result cache: no model call', async ({
    operatorApi,
    llm,
  }) => {
    const template = await keyValueTemplate(operatorApi, 'AI_ONLY');
    const pdf = keyValuePdf();
    const copyOf = (tag) => {
      const copy = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-cache-')), `${tag}-${Date.now()}.pdf`);
      fs.copyFileSync(pdf, copy);
      return copy;
    };
    // extraction-fallback.spec.js ends with the AI circuit breaker OPEN (120 s):
    // wait for it to close (an AI_ONLY parse fails fast while it is open).
    test.setTimeout(300_000);
    let first;
    const deadline = Date.now() + 180_000;
    for (;;) {
      await llm.reset();
      first = await uploadAndSettle(operatorApi, template.templateId, { filePath: copyOf('first') });
      const breakerOpen = first.status === 'FAILED' && /temporarily unavailable/i.test(first.errorMessage ?? '');
      if (!breakerOpen || Date.now() > deadline) break;
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
    expect(first.status, first.errorMessage ?? '').not.toBe('FAILED');
    expect(first.parsedBy).toBe('AI');
    const callsAfterFirst = (await llm.stats()).calls;
    expect(callsAfterFirst, 'the first upload asked the model').toBeGreaterThan(0);

    // Same bytes, another file name (the duplicate gate refuses same name + bytes).
    const second = await uploadAndSettle(operatorApi, template.templateId, { filePath: copyOf('again') });

    expect(second.status, second.errorMessage ?? '').not.toBe('FAILED');
    expect(second.parsedBy).toBe('AI');
    expect((await llm.stats()).calls, 'a cache hit makes no model call').toBe(callsAfterFirst);
    const [a, b] = await Promise.all([
      operatorApi.get(`/api/ingestion/jobs/${first.ingestionJobId}`),
      operatorApi.get(`/api/ingestion/jobs/${second.ingestionJobId}`),
    ]);
    expect(b.parsedTable).toEqual(a.parsedTable);
  });

  test('OPT-V9 bank A’s admin never sees bank B’s audit events (API and /admin/audit)', async ({
    page,
    adminApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!platformApi, 'the stack was started without platform admins');
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const adminB = await sessionFor(bankB.admins[0]); // LOGIN_SUCCESS etc. in bank B
    const bOwn = await adminB.get(`/api/admin/audit?actor=${encodeURIComponent(bankB.admins[0])}&limit=50`);
    expect(bOwn.events.length, 'bank B sees its own admin’s events').toBeGreaterThan(0);
    const bIds = new Set(bOwn.events.map((e) => e.id));

    const aByActor = await adminApi.get(`/api/admin/audit?actor=${encodeURIComponent(bankB.admins[0])}&limit=200`);
    expect(aByActor.events, 'filtering by bank B’s actor finds nothing in bank A').toEqual([]);
    const aRecent = await adminApi.get('/api/admin/audit?limit=200');
    expect(aRecent.events.filter((e) => bIds.has(e.id))).toEqual([]);
    expect(aRecent.events.filter((e) => e.actorEmail === bankB.admins[0])).toEqual([]);

    await openAs(page, BOOTSTRAP(), '/admin/audit');
    await expect(page.getByRole('heading', { name: 'Audit log', level: 1 })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Audit events' })).toBeVisible();
    await page.getByLabel('Actor (email or user id)').fill(bankB.admins[0]);
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await expect(page.getByText('No audit events match these filters.')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await expect(page.getByRole('table', { name: 'Audit events' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Audit events' })).not.toContainText(bankB.admins[0]);
  });

  test('OPT-X3 the dashboard shows the same numbers from its one summary call as the four lists', async ({
    page,
    operatorApi,
  }) => {
    const [summary, templates, jobs, extraction, imports] = await Promise.all([
      operatorApi.get('/api/dashboard/summary'),
      operatorApi.get('/Templates/GetAll'),
      operatorApi.get('/api/ingestion/jobs'),
      operatorApi.get('/api/extraction/jobs'),
      operatorApi.get('/ImportHistory/GetAll'),
    ]);
    expect(jobs.length, 'below the 200-job cap of the legacy list').toBeLessThan(200);
    const byStatus = jobs.reduce((m, j) => ({ ...m, [j.status]: (m[j.status] || 0) + 1 }), {});
    expect(summary.templates).toBe(templates.length);
    expect(summary.ingestion.total).toBe(jobs.length);
    expect(summary.ingestion.byStatus).toEqual(byStatus);
    expect(summary.ingestion.recent.length).toBe(Math.min(8, jobs.length));
    expect(summary.extraction.total).toBe(extraction.length);
    expect(summary.imports.total).toBe(imports.length);

    const summaryCalls = [];
    page.on('request', (r) => {
      if (/\/api\/dashboard\/summary$/.test(new URL(r.url()).pathname)) summaryCalls.push(r.url());
    });
    await openAs(page, OPERATOR(), '/');
    await expect(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    const t = templates.length;
    await expect(page.getByText(`${t} template${t === 1 ? '' : 's'}`, { exact: true })).toBeVisible();
    const n = jobs.length;
    await expect(
      page.getByText(`${n} job${n === 1 ? '' : 's'} · ${byStatus.COMPLETED || 0} completed · ${byStatus.FAILED || 0} failed`)
    ).toBeVisible();
    expect(summaryCalls.length, 'the page used the summary endpoint').toBeGreaterThan(0);
  });
});
