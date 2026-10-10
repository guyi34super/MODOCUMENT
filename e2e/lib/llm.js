// Control plane of the fake LLM (e2e/stubs/fake-llm.mjs).
import { env } from './env.js';

async function call(method, path, body) {
  const res = await fetch(env.llmURL + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`fake-llm ${method} ${path} -> ${res.status} ${JSON.stringify(json)}`);
  return json;
}

export const llm = {
  /** mode: ok | error500 | error429 | timeout | badjson | badenvelope; count = next N calls only. */
  setMode: (mode, { count, delayMs } = {}) => call('POST', '/__mode', { mode, count, delayMs }),
  mode: () => call('GET', '/__mode'),
  stats: () => call('GET', '/__stats'),
  reset: () => call('POST', '/__reset'),
  health: () => call('GET', '/health'),
};
