// Phase-2 domain helpers for the E2E specs (settings, dashboard, platform,
// announcements, ingestion provenance). Everything goes through the public
// API, exactly as the SPA calls it (contracts: docs/phase2/contracts.md §3).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { approveChange, strongPassword, uploadForIngestion, waitForJob } from './api.js';
import { saveAccount } from './accounts.js';
import { writeTextPdf } from '../fixtures/pdf.js';
import { uniqueName } from './seed.js';

// ---------------------------------------------------------------- settings

/** Every SettingView of a settings page, keyed by setting key. */
export function settingsByKey(page) {
  const byKey = {};
  for (const group of page?.groups ?? []) {
    for (const setting of group.settings ?? []) byKey[setting.key] = setting;
  }
  return byKey;
}

export async function bankSetting(api, key) {
  const view = settingsByKey(await api.get('/api/admin/settings'))[key];
  if (!view) throw new Error(`setting ${key} is not on the bank settings page`);
  return view;
}

export async function platformSetting(api, key) {
  const view = settingsByKey(await api.get('/api/platform/settings'))[key];
  if (!view) throw new Error(`setting ${key} is not on the platform settings page`);
  return view;
}

/** PUT /api/admin/settings with one change at the setting's current version; raw response. */
export async function putBankSetting(api, key, value, { expectedVersion, comment } = {}) {
  const version = expectedVersion ?? (await bankSetting(api, key)).version;
  return api.request('PUT', '/api/admin/settings', {
    json: { changes: [{ key, value, expectedVersion: version }], comment },
  });
}

/**
 * Puts a bank setting back to its inherited value (value null). A SECURITY
 * setting needs a second approver: pass `checker`. Best effort (cleanup).
 */
export async function resetBankSetting(maker, key, checker) {
  const view = await bankSetting(maker, key);
  if (view.pendingChangeId && checker) {
    await checker.request('POST', `/api/admin/changes/${view.pendingChangeId}/reject`, {
      json: { comment: 'e2e cleanup' },
    });
  }
  const fresh = await bankSetting(maker, key);
  if (fresh.source !== 'BANK') return;
  const res = await putBankSetting(maker, key, null, { expectedVersion: fresh.version });
  if (res.status === 202 && checker) {
    await approveChange(checker, res.body.pendingChange.pendingChangeId, 'e2e cleanup');
  }
}

// --------------------------------------------------------------- dashboard

/**
 * GET /api/admin/dashboard until the 30 s per-bank cache has refreshed past
 * `after` (an ISO time or Date) — so counts include what the spec just did.
 */
export async function freshDashboard(api, after, { timeoutMs = 45_000 } = {}) {
  const since = new Date(after).getTime();
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await api.get('/api/admin/dashboard');
    if (new Date(last.generatedAt).getTime() >= since) return last;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`dashboard not refreshed after ${after} (last generatedAt ${last?.generatedAt})`);
}

// ---------------------------------------------------------------- platform

/** Onboards a customer bank (BANK_CREATE, second platform admin approves). */
export async function onboardBank(maker, checker, { code, name } = {}) {
  const tag = uniqueName('b').replace(/[^a-z0-9]/gi, '').slice(-10).toUpperCase();
  const bankCode = code ?? `E2E${tag}`;
  const users = ['ADMIN', 'ADMIN'].map((role, i) => ({
    email: `${uniqueName(`e2e-bank-${i + 1}`)}@e2e.modocument.test`.toLowerCase(),
    displayName: `E2E bank admin ${i + 1}`,
    role,
    temporaryPassword: strongPassword(),
  }));
  const submitted = await maker.request('POST', '/api/platform/banks', {
    json: { code: bankCode, name: name ?? `E2E Bank ${tag}`, users },
    expect: 202,
  });
  await approveChange(checker, submitted.body.pendingChangeId);
  for (const user of users) {
    saveAccount(user.email, { password: user.temporaryPassword, role: user.role, origin: 'onboarding' });
  }
  const banks = await maker.get('/api/platform/banks');
  const list = Array.isArray(banks) ? banks : banks.banks ?? [];
  const bank = list.find((b) => b.code === bankCode);
  return { bank, code: bankCode, admins: users.map((u) => u.email), change: submitted.body };
}

// ----------------------------------------------------------- announcements

/** Publishes a platform announcement visible now; returns its view. */
export async function publishAnnouncement(platformApi, { kind = 'INFO', severity = 'INFO', title, body, audienceBankCodes = null }) {
  const now = Date.now();
  const json = {
    kind,
    severity,
    title,
    body,
    audienceBankCodes,
    startsAt: new Date(now - 60_000).toISOString(),
    endsAt: new Date(now + 3 * 3600_000).toISOString(),
  };
  if (kind === 'MAINTENANCE') {
    json.maintenanceStartsAt = new Date(now + 3600_000).toISOString();
    json.maintenanceEndsAt = new Date(now + 2 * 3600_000).toISOString();
  }
  return (await platformApi.request('POST', '/api/platform/announcements', { json, expect: [200, 201] })).body;
}

/** Polls /api/announcements/active (30 s cache) until `predicate(list)` holds. */
export async function waitForAnnouncements(api, predicate, { timeoutMs = 45_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let list = [];
  while (Date.now() < deadline) {
    list = (await api.get('/api/announcements/active')).announcements ?? [];
    if (predicate(list)) return list;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`announcements never matched (last: ${JSON.stringify(list.map((a) => a.title))})`);
}

// --------------------------------------------------- ingestion provenance

/** A key:value free-text PDF: no deterministic fast path; AI first, code reads it too. */
export function keyValuePdf() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-kv-'));
  return writeTextPdf(path.join(dir, `${uniqueName('remittance')}.pdf`), [
    'Remittance register',
    '',
    `Reference: INV-${Date.now()}-A`,
    'Amount: 125.50',
    'Currency: MUR',
    '',
    `Reference: INV-${Date.now()}-B`,
    'Amount: 80.00',
    'Currency: EUR',
  ]);
}

/** Template for keyValuePdf(), with an optional extraction mode (ws23). */
export async function keyValueTemplate(api, extractionMode = null) {
  return api.post('/Templates/CreateWithFields', {
    name: uniqueName('E2E Remittance'),
    description: 'phase-2 extraction mode e2e',
    extractionMode,
    fields: [
      { fieldName: 'Reference', dataType: 'Text', rules: [], piiKind: null },
      { fieldName: 'Amount', dataType: 'Number', rules: [], piiKind: null },
      { fieldName: 'Currency', dataType: 'Text', rules: [], piiKind: null },
    ],
  });
}

/** Uploads the key:value PDF and waits until the job is VALIDATED or FAILED. */
export async function uploadAndSettle(api, templateId, { timeoutMs = 150_000 } = {}) {
  const created = await uploadForIngestion(api, {
    filePath: keyValuePdf(),
    templateId,
    runUntil: 'VALIDATED',
    wait: false,
  });
  const id = created.ingestionJobId;
  try {
    return await waitForJob(
      api,
      id,
      // status COMPLETED also marks an intermediate stage (e.g. PARSED): wait for the step.
      (job) => job.currentStep === 'VALIDATED',
      { timeoutMs }
    );
  } catch (error) {
    // waitForJob throws on FAILED: hand the failed job back to the spec.
    const job = await api.get(`/api/ingestion/jobs/${id}`);
    if (job.status === 'FAILED') return job;
    throw error;
  }
}
