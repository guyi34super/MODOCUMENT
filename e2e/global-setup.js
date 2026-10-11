// Runs once before the suite: waits until the stack answers through the SPA
// origin (so the proxy, the backend and the database are all up), checks the
// fake LLM, resets its counters and registers the bootstrap admin in the
// run's account book. It deliberately does NOT sign anyone in: the first
// smoke spec drives the bootstrap admin's MFA enrolment through the UI.
import fs from 'node:fs';
import { env } from './lib/env.js';
import { llm } from './lib/llm.js';
import { registerBootstrapAdmin } from './lib/seed.js';

async function waitFor(what, probe, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      if (await probe()) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`E2E stack not ready: ${what} (${lastError?.message ?? 'timeout'})`);
}

export default async function globalSetup() {
  // Fail fast with a clear message when the run was not set up.
  void env.dbURL;
  void env.bootstrapEmail;
  void env.bootstrapPassword;

  await waitFor(`SPA at ${env.baseURL}`, async () => {
    const res = await fetch(env.baseURL + '/');
    return res.ok && (await res.text()).includes('id="root"');
  });
  // A public API route through the same origin proves the proxy -> backend hop.
  await waitFor('API through the SPA proxy', async () => {
    const res = await fetch(env.baseURL + '/api/auth/providers');
    return res.ok;
  });
  await waitFor(`fake LLM at ${env.llmURL}`, async () => (await llm.health()).status === 'UP');
  await llm.reset();

  fs.mkdirSync(env.stateDir, { recursive: true });
  registerBootstrapAdmin();
}
