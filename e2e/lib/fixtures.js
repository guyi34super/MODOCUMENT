// Playwright fixtures shared by every spec.
import { test as base, expect } from '@playwright/test';
import { getAccount } from './accounts.js';
import { sessionFor } from './api.js';
import { llm } from './llm.js';
import { bootstrapAdmin, operator, secondAdmin, BOOTSTRAP, OPERATOR, SECOND_ADMIN } from './seed.js';

/**
 * Puts an API session into the SPA exactly where the app keeps it
 * (localStorage modoc.token / modoc.user, see frontend/src/api/client.js),
 * then opens `path`. Used by specs whose subject is not the login itself.
 */
export async function openAs(page, email, path = '/') {
  const api = await sessionFor(email);
  const user = getAccount(email)?.user ?? (await api.get('/api/auth/me'));
  await page.goto('/login');
  await page.evaluate(
    ([token, profile]) => {
      localStorage.setItem('modoc.token', token);
      localStorage.setItem('modoc.user', JSON.stringify(profile));
    },
    [api.token, user]
  );
  await page.goto(path);
  return api;
}

/** Reads the session the SPA stored after a UI login. */
export async function sessionFromPage(page) {
  return page.evaluate(() => ({
    token: localStorage.getItem('modoc.token'),
    user: JSON.parse(localStorage.getItem('modoc.user') || 'null'),
  }));
}

export const test = base.extend({
  /** API client for the bootstrap ADMIN (maker). */
  adminApi: async ({}, use) => {
    await use(await bootstrapAdmin());
  },
  /** API client for the second ADMIN (checker). */
  checkerApi: async ({}, use) => {
    await use(await secondAdmin());
  },
  /** API client for the READ_WRITE operator (document flow, no MFA owed). */
  operatorApi: async ({}, use) => {
    await use(await operator());
  },
  /** Fake-LLM control; every test starts (and ends) in 'ok' mode. */
  llm: async ({}, use) => {
    await llm.setMode('ok');
    await use(llm);
    await llm.setMode('ok');
  },
  accounts: async ({}, use) => {
    await use({ bootstrap: BOOTSTRAP(), second: SECOND_ADMIN(), operator: OPERATOR() });
  },
});

export { expect };
