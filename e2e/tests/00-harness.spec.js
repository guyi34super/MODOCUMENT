// Harness self-checks: if these fail, nothing after them is meaningful.
import { test, expect } from '../lib/fixtures.js';
import { codeAt, hotp, nextCode, counterAt } from '../lib/totp.js';
import { env } from '../lib/env.js';
import { answerFor, classifyPrompt, readTable } from '../stubs/fake-llm.mjs';

test.describe('harness @smoke', () => {
  test('HARNESS-01 TOTP helper reproduces the RFC 6238 SHA-1 test vectors', () => {
    // RFC 6238 Appendix B: ASCII key "12345678901234567890", 8 digits, T0=0, X=30.
    const rfcSeed = Buffer.from('12345678901234567890', 'ascii');
    const vectors = [
      [59, '94287082'],
      [1111111109, '07081804'],
      [1111111111, '14050471'],
      [1234567890, '89005924'],
      [2000000000, '69279037'],
      [20000000000, '65353130'],
    ];
    for (const [seconds, expected] of vectors) {
      expect(hotp(rfcSeed, Math.floor(seconds / 30), 8)).toBe(expected);
    }
    // Same key in Base32, 6 digits (the backend's parameters): last 6 digits.
    expect(codeAt('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1)).toBe('287082');
  });

  test('HARNESS-02 nextCode never reuses a consumed step and needs no wait for the step after', async () => {
    const base32 = 'JBSWY3DPEHPK3PXP';
    const now = counterAt();
    const first = await nextCode(base32, null);
    expect(first.counter).toBeGreaterThanOrEqual(now);
    const started = Date.now();
    const second = await nextCode(base32, first.counter);
    expect(second.counter).toBe(first.counter + 1);
    expect(Date.now() - started).toBeLessThan(1000);
  });

  test('HARNESS-03 fake LLM answers extraction prompts with schema-valid JSON', async () => {
    const prompt = [
      'TASK: Extract the main data table and map columns to template fields.',
      '',
      'TEMPLATE FIELDS (name | type):',
      '1. "Reference" | Text',
      '2. "Amount" | Number',
      '',
      'DOCUMENT:',
      'Reference\tAmount\tNote',
      'INV-1\t10.50\tfirst',
      'INV-2\t7.25\tsecond',
    ].join('\n');
    expect(classifyPrompt(prompt)).toBe('TABLE_EXTRACT_AND_MAP');
    const answer = JSON.parse(answerFor(prompt));
    expect(answer.columns).toEqual(['Reference', 'Amount', 'Note']);
    expect(answer.mapping).toEqual({ Reference: 'Reference', Amount: 'Amount', Note: null });
    expect(answer.rows).toEqual([
      { Reference: 'INV-1', Amount: '10.50', Note: 'first' },
      { Reference: 'INV-2', Amount: '7.25', Note: 'second' },
    ]);
    // key: value documents become one record per block.
    expect(readTable('Reference: A\nAmount: 1\nReference: B\nAmount: 2').rows).toEqual([
      { Reference: 'A', Amount: '1' },
      { Reference: 'B', Amount: '2' },
    ]);
  });

  test('HARNESS-04 fake LLM obeys scripted modes over HTTP and counts calls', async ({ llm }) => {
    await llm.reset();
    const ask = () =>
      fetch(`${env.llmURL}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: 'whatever-the-app-sends',
          messages: [{ role: 'user', content: 'TASK: Return ONLY the column names\n\nHEADER AND SAMPLE ROWS:\na,b\n1,2' }],
        }),
      });

    let res = await ask();
    expect(res.status).toBe(200);
    const ok = await res.json();
    expect(ok.object).toBe('chat.completion');
    expect(ok.model).toBe('whatever-the-app-sends');
    expect(JSON.parse(ok.choices[0].message.content)).toEqual({ columns: ['a', 'b'] });

    await llm.setMode('error500', { count: 1 });
    expect((await ask()).status).toBe(500);
    expect((await ask()).status).toBe(200); // count elapsed -> back to ok

    await llm.setMode('badjson');
    res = await ask();
    expect(res.status).toBe(200);
    const bad = await res.json();
    expect(() => JSON.parse(bad.choices[0].message.content)).toThrow();

    await llm.setMode('timeout');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 1500);
    await expect(
      fetch(`${env.llmURL}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: [{ role: 'user', content: 'x' }] }),
        signal: controller.signal,
      })
    ).rejects.toThrow();
    clearTimeout(timer);
    await llm.setMode('ok');

    const stats = await llm.stats();
    expect(stats.calls).toBe(5);
    expect(stats.byMode).toMatchObject({ ok: 2, error500: 1, badjson: 1, timeout: 1 });
    await llm.reset();
  });

  test('HARNESS-05 the SPA and the API are served through one origin', async ({ page, request }) => {
    const providers = await request.get('/api/auth/providers');
    expect(providers.status()).toBe(200);
    expect(await providers.json()).toEqual([]);
    // Every non-login API route needs a token.
    expect((await request.get('/api/auth/me')).status()).toBe(401);
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });
});
