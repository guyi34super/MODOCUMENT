#!/usr/bin/env node
// MO-019 / SEC-011-T01 live per-role RBAC probe (agent A4, compliance pack).
//
// Runs against a stack started by e2e/run-local.sh (it reuses that run's env
// and the e2e helper library), so the backend runs the production profile on
// PostgreSQL with RLS, as the RLS-subject role modocument_app:
//
//   SKIP_BUILD=1 E2E_RUN_CMD='node scripts/compliance/rbac-probe.mjs' e2e/run-local.sh
//
// What it does (synthetic users and data only):
//   1. builds one account per role the product's own way (USER_CREATE
//      maker/checker for bank users, BANK_CREATE for a second bank); the only
//      SQL seed is the e2e harness's second bank admin (e2e/lib/db.js explains
//      why a fresh install needs it);
//   2. calls a representative route per permission as every role and compares
//      the gate result with docs/security/role-permission-matrix.csv
//      (403 = denied by @RequiresPermission; any other status = the gate let
//      the call through, and the handler answered);
//   3. runs the isolation, SoD and joiner/mover/leaver scenarios.
// Writes docs/compliance/evidence/A4-rbac-probe.json and A4-rbac-probe.md.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ApiClient, approveChange, sessionFor, strongPassword, uploadForIngestion, waitForJob } from '../../e2e/lib/api.js';
import { getAccount, saveAccount } from '../../e2e/lib/accounts.js';
import { bootstrapAdmin, createUserViaMakerChecker, platformAdmin, secondAdmin, uniqueName, BOOTSTRAP } from '../../e2e/lib/seed.js';
import { textPdf } from '../../e2e/fixtures/pdf.js';
import { bearerClient, clientToken, createApiClient } from '../../e2e/lib/integration.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT_JSON = path.join(ROOT, 'docs/compliance/evidence/A4-rbac-probe.json');
const OUT_MD = path.join(ROOT, 'docs/compliance/evidence/A4-rbac-probe.md');
const NONE = 999999; // an id that does not exist: an allowed call answers 404 from inside the handler

// ------------------------------------------------------------------ matrix
function readMatrix() {
  const lines = fs.readFileSync(path.join(ROOT, 'docs/security/role-permission-matrix.csv'), 'utf8').trim().split('\n');
  const roles = lines[0].split(',').slice(2);
  const grants = {};
  for (const line of lines.slice(1)) {
    const cells = line.split(',');
    grants[cells[0]] = new Set(roles.filter((_, i) => cells[i + 2] === 'x'));
  }
  return { roles, grants };
}
const { grants } = readMatrix();

// ------------------------------------------------------------ the probes
// [capability, permission, method, path(ctx), body(ctx)?, kind?]
// kind 'form' sends an empty multipart/query call (the handler answers 400).
const PROBES = [
  ['Templates', 'TEMPLATE_READ', 'GET', () => '/Templates/GetAll'],
  ['Templates', 'TEMPLATE_WRITE', 'POST', () => '/Templates/CreateWithFields', () => ({
    name: uniqueName('A4 probe template'), description: 'rbac probe', fields: [{ fieldName: 'Ref', dataType: 'Text', rules: [], piiKind: null }],
  })],
  ['Templates', 'TEMPLATE_PUBLISH', 'PUT', () => `/Templates/${NONE}/Visibility`, () => ({ visibility: 'BANK' })],
  ['Templates', 'VALIDATION_READ', 'GET', (c) => `/ValidationRules/GetByTemplate/${c.sharedTemplateId}`],
  ['Templates', 'VALIDATION_WRITE', 'DELETE', () => `/ValidationRules/${NONE}/Ref/Required`],
  ['Enter data', 'FILE_UPLOAD', 'POST', () => '/api/fileupload/upload', null, 'form'],
  ['Enter data', 'INGESTION_READ', 'GET', () => '/api/ingestion/jobs'],
  ['Enter data', 'INGESTION_WRITE', 'POST', () => `/api/ingestion/jobs/${NONE}/cancel`],
  ['Enter data', 'IMPORT_READ', 'GET', () => '/ImportHistory/GetAll'],
  ['Enter data', 'IMPORT_WRITE', 'DELETE', () => `/ImportHistory/${NONE}`],
  ['Enter data', 'EXTRACTION_READ', 'GET', () => '/api/extraction/sources'],
  ['Enter data', 'EXTRACTION_WRITE', 'DELETE', () => `/api/extraction/sources/${NONE}`],
  ['Enter data', 'EXTRACTION_RUN', 'POST', () => `/api/extraction/jobs/${NONE}/start`],
  ['Enter data', 'AI_USE', 'POST', () => '/api/automapper/generate', null, 'form'],
  ['Reports', 'DOC_READ', 'GET', () => '/api/doccreation/instances'],
  ['Reports', 'DOC_WRITE', 'DELETE', () => `/api/doccreation/instances/${NONE}`],
  ['Reports', 'DOC_GENERATE', 'POST', () => `/api/doccreation/instances/${NONE}/generate`],
  ['Reports', 'DOC_RELEASE', 'GET', () => '/api/doccreation/instances/pending-release'],
  ['Reports', 'EXPORT_RUN', 'GET', () => `/api/ingestion/jobs/${NONE}/export/file?format=csv`],
  ['Users', 'USER_ADMIN', 'GET', () => '/api/admin/users'],
  ['Users', 'USER_ADMIN', 'GET', () => '/api/admin/users/access-review'],
  ['Users', 'USER_ADMIN', 'GET', () => '/api/admin/users/export'],
  ['Users', 'CHANGE_APPROVE', 'POST', () => `/api/admin/changes/${NONE}/approve`, () => ({ comment: 'probe' })],
  ['Logs', 'AUDIT_READ', 'GET', () => '/api/admin/audit'],
  ['Logs', 'SIEM_READ', 'GET', () => '/api/siem/events'],
  ['Logs', 'METRICS_READ', 'GET', () => '/api/admin/dashboard'],
  ['Settings & integrations', 'SETTINGS_READ', 'GET', () => '/api/admin/settings'],
  // A well-formed body: request validation (@Valid) runs before the permission check (see scenario "validation order").
  ['Settings & integrations', 'SETTINGS_WRITE', 'PUT', () => '/api/admin/settings', () => ({ changes: [{ key: 'a4.probe.unknown', value: true, expectedVersion: 0 }] })],
  ['Settings & integrations', 'INTEGRATION_ADMIN', 'GET', () => '/api/admin/integrations/connections'],
  ['Settings & integrations', 'INTEGRATION_ADMIN', 'GET', () => '/api/admin/integrations/api-clients'],
  ['Settings & integrations', 'INTEGRATION_RUN', 'GET', () => '/api/admin/integrations/runs'],
  ['Reference data', 'LOOKUP_READ', 'GET', () => '/ProcessStep/GetAll'],
  ['Reference data', 'LOOKUP_ADMIN', 'DELETE', () => `/ProcessStep/${NONE}`],
  ['Self-service', 'PROFILE_SELF', 'GET', () => '/api/auth/me'],
  ['Self-service', 'SYSTEM_INFO', 'GET', () => '/api/system/version'],
  ['Platform', 'PLATFORM_READ', 'GET', () => '/api/platform/overview'],
  ['Platform', 'PLATFORM_BANKS', 'POST', () => `/api/platform/banks/${NONE}/suspend`, () => ({ reason: 'probe' })],
  ['Platform', 'PLATFORM_SETTINGS', 'PUT', () => '/api/platform/settings', () => ({ changes: [{ key: 'a4.probe.unknown', value: true, expectedVersion: 0 }] })],
  ['Platform', 'PLATFORM_ANNOUNCE', 'DELETE', () => `/api/platform/announcements/${NONE}`],
  ['Machine scopes', 'INTEGRATION_INGEST', 'GET', () => '/api/integration/v1/templates'],
  ['Machine scopes', 'INTEGRATION_RESULTS', 'GET', () => `/api/integration/v1/jobs/${NONE}`],
  ['Machine scopes', 'INTEGRATION_DOCS', 'GET', () => `/api/integration/v1/documents/${NONE}`],
  ['Machine scopes', 'INTEGRATION_EVENTS', 'GET', () => '/api/integration/v1/events'],
  ['Machine scopes', 'AGENT_CONNECT', 'GET', () => '/api/agent/v1/tasks'],
];

const gate = (status) => (status === 403 ? 'deny' : status === 401 ? 'unauthenticated' : 'allow');

async function call(api, method, url, body, kind) {
  if (kind === 'form') return api.request(method, url, { form: new FormData() });
  return api.request(method, url, body === undefined ? {} : { json: body });
}

function idOf(obj, ...keys) {
  for (const k of keys) if (obj && typeof obj[k] === 'number') return obj[k];
  return null;
}

async function userId(admin, email) {
  const list = await admin.get('/api/admin/users');
  const users = Array.isArray(list) ? list : list.items ?? list.users ?? [];
  const u = users.find((x) => String(x.email).toLowerCase() === email.toLowerCase());
  return u ? idOf(u, 'id', 'userId', 'appUserId') : null;
}

const scenarios = [];
function record(area, name, expected, actual, pass, detail = '') {
  scenarios.push({ area, name, expected, actual, result: pass ? 'PASS' : 'FAIL', detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${area}: ${name} (expected ${expected}, got ${actual}) ${detail}`);
}
async function scenario(area, name, expected, fn) {
  try {
    const { actual, pass, detail } = await fn();
    record(area, name, expected, actual, pass, detail);
  } catch (error) {
    record(area, name, expected, 'error', false, String(error.message).slice(0, 300));
  }
}

function syntheticCsv() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a4-probe-'));
  const file = path.join(dir, `${uniqueName('a4-probe')}.csv`);
  const rows = ['Ref,Amount,Currency'];
  for (let i = 1; i <= 5; i += 1) rows.push(`SYN-${Date.now()}-${i},${i * 10}.00,MUR`);
  fs.writeFileSync(file, rows.join('\n') + '\n');
  return file;
}

// ------------------------------------------------------------------ main
async function main() {
  const started = new Date().toISOString();
  console.log('[a4] building accounts');
  const adminA = await bootstrapAdmin();
  const adminA2 = await secondAdmin();
  const accounts = { ADMIN: { email: BOOTSTRAP(), api: adminA, bank: 'A (DEFAULT)' } };
  for (const role of ['READ_ONLY', 'READ_WRITE', 'APPROVER']) {
    const { email } = await createUserViaMakerChecker({ role, displayName: `A4 probe ${role}` });
    accounts[role] = { email, api: await sessionFor(email), bank: 'A (DEFAULT)' };
  }
  const pa1 = await platformAdmin(1);
  const pa2 = await platformAdmin(2);
  accounts.PLATFORM_ADMIN = { email: getAccount(process.env.E2E_PLATFORM_ADMIN_1_EMAIL.toLowerCase()).email, api: pa1, bank: 'MOI-PLATFORM' };

  // Bank B through BANK_CREATE, with a self-approval attempt first.
  const bankCode = `A4B${Date.now().toString(36).toUpperCase().slice(-6)}`;
  const bankUsers = [1, 2].map((n) => ({
    email: `${uniqueName(`a4-bankb-admin${n}`)}@e2e.modocument.test`.toLowerCase(),
    displayName: `A4 bank B admin ${n}`, role: 'ADMIN', temporaryPassword: strongPassword(),
  }));
  const bankCreate = await pa1.request('POST', '/api/platform/banks', {
    json: { code: bankCode, name: `A4 probe bank ${bankCode}`, users: bankUsers }, expect: 202,
  });
  await scenario('SoD (platform maker/checker)', 'Platform admin 1 approves own BANK_CREATE', '409', async () => {
    const r = await pa1.request('POST', `/api/admin/changes/${bankCreate.body.pendingChangeId}/approve`, { json: { comment: 'self' } });
    return { actual: String(r.status), pass: r.status === 409, detail: r.body?.code ?? '' };
  });
  await approveChange(pa2, bankCreate.body.pendingChangeId, 'a4 probe: second platform admin');
  for (const u of bankUsers) saveAccount(u.email, { password: u.temporaryPassword, role: 'ADMIN', origin: 'onboarding' });
  const adminB = await sessionFor(bankUsers[0].email);
  accounts['ADMIN (bank B)'] = { email: bankUsers[0].email, api: adminB, bank: `B (${bankCode})` };

  // A machine principal of bank A (API_CLIENT): created via API_CLIENT_CREATE maker/checker,
  // OAuth2 client credentials, scopes narrowed to ingest + results.
  const clientScopes = ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'];
  const machine = await createApiClient(adminA, adminA2, { authMethod: 'OAUTH2_CLIENT_CREDENTIALS', scopes: clientScopes });
  const machineToken = await clientToken(machine.clientId, machine.secret);
  accounts.API_CLIENT = { email: '(machine client, scopes ingest+results)', api: bearerClient(machineToken.body.access_token), bank: 'A (DEFAULT)', scopes: new Set(clientScopes) };

  // ----------------------------------------------------- objects in bank A
  console.log('[a4] seeding bank A objects');
  const rw = accounts.READ_WRITE.api;
  const privateTemplate = await rw.post('/Templates/CreateWithFields', {
    name: uniqueName('A4 private template'), description: 'rbac probe',
    fields: ['Ref', 'Amount', 'Currency'].map((f) => ({ fieldName: f, dataType: 'Text', rules: [], piiKind: null })),
  });
  const privateTemplateId = idOf(privateTemplate, 'templateId', 'id');
  const shared = await adminA.post('/Templates/CreateWithFields', {
    name: uniqueName('A4 shared template'), description: 'rbac probe',
    fields: [{ fieldName: 'Ref', dataType: 'Text', rules: [], piiKind: null }],
  });
  const sharedTemplateId = idOf(shared, 'templateId', 'id');
  await adminA.put(`/Templates/${sharedTemplateId}/Visibility`, { visibility: 'BANK' });
  const job = await uploadForIngestion(rw, { filePath: syntheticCsv(), templateId: privateTemplateId, runUntil: 'PARSED', wait: true });
  const jobId = job.ingestionJobId ?? job.id;
  const ctx = { sharedTemplateId, privateTemplateId, jobId };

  // --------------------------------------------------- per-role matrix probe
  console.log('[a4] probing the matrix');
  const probes = [];
  const anon = new ApiClient();
  for (const [label, acc] of Object.entries(accounts)) {
    const role = label.startsWith('ADMIN') ? 'ADMIN' : label;
    for (const [capability, permission, method, urlFn, bodyFn, kind] of PROBES) {
      const url = urlFn(ctx);
      const res = await call(acc.api, method, url, bodyFn ? bodyFn(ctx) : undefined, kind);
      // API_CLIENT: role grant AND the client's scopes (PermissionChecker).
      const expected = grants[permission].has(role) && (!acc.scopes || acc.scopes.has(permission)) ? 'allow' : 'deny';
      const actual = gate(res.status);
      probes.push({ account: label, role, bank: acc.bank, capability, permission, method, url, status: res.status, expected, actual, result: expected === actual ? 'PASS' : 'FAIL' });
    }
  }
  // Anonymous: every guarded probe must be 401.
  for (const [capability, permission, method, urlFn, bodyFn, kind] of PROBES) {
    const url = urlFn(ctx);
    const res = await call(anon, method, url, bodyFn ? bodyFn(ctx) : undefined, kind);
    const actual = gate(res.status);
    probes.push({ account: 'anonymous', role: 'none', bank: '-', capability, permission, method, url, status: res.status, expected: 'unauthenticated', actual, result: actual === 'unauthenticated' ? 'PASS' : 'FAIL' });
  }

  // -------------------------------------------- data separation scenarios
  console.log('[a4] isolation scenarios');
  const notFound = (s) => s === 404 || s === 403;
  const iso = [
    ['Cross-bank', 'Bank B ADMIN reads bank A private template', adminB, 'GET', `/Templates/GetById/${privateTemplateId}`],
    ['Cross-bank', 'Bank B ADMIN reads bank A bank-shared template', adminB, 'GET', `/Templates/GetById/${sharedTemplateId}`],
    ['Cross-bank', 'Bank B ADMIN reads bank A ingestion job', adminB, 'GET', `/api/ingestion/jobs/${jobId}`],
    ['Cross-bank', 'Bank B ADMIN exports bank A ingestion job', adminB, 'GET', `/api/ingestion/jobs/${jobId}/export/file?format=csv`],
    ['Cross-bank', 'Bank B ADMIN uses bank A template for an upload', adminB, 'GET', `/ValidationRules/GetByTemplate/${privateTemplateId}`],
    ['Owner scoping', 'Bank A ADMIN reads READ_WRITE user\'s private template', adminA, 'GET', `/Templates/GetById/${privateTemplateId}`],
    ['Owner scoping', 'Bank A ADMIN reads READ_WRITE user\'s ingestion job', adminA, 'GET', `/api/ingestion/jobs/${jobId}`],
    ['Owner scoping', 'Bank A READ_ONLY reads READ_WRITE user\'s ingestion job', accounts.READ_ONLY.api, 'GET', `/api/ingestion/jobs/${jobId}`],
    ['Platform vs bank data', 'PLATFORM_ADMIN reads bank A template', pa1, 'GET', `/Templates/GetById/${sharedTemplateId}`],
    ['Platform vs bank data', 'PLATFORM_ADMIN reads bank A ingestion job', pa1, 'GET', `/api/ingestion/jobs/${jobId}`],
    ['Platform vs bank data', 'Bank A ADMIN reads the platform audit trail', adminA, 'GET', '/api/platform/audit'],
  ];
  for (const [area, name, api, method, url] of iso) {
    await scenario(area, name, '404 or 403', async () => {
      const r = await api.request(method, url);
      return { actual: String(r.status), pass: notFound(r.status), detail: `${method} ${url.replace(/\d{2,}/g, '{id}')}` };
    });
  }
  await scenario('Owner scoping (positive control)', 'Owner READ_WRITE reads own job', '200', async () => {
    const r = await rw.request('GET', `/api/ingestion/jobs/${jobId}`);
    return { actual: String(r.status), pass: r.status === 200 };
  });
  await scenario('Bank-shared template (positive control)', 'Bank A READ_ONLY reads the bank-shared template', '200', async () => {
    const r = await accounts.READ_ONLY.api.request('GET', `/Templates/GetById/${sharedTemplateId}`);
    return { actual: String(r.status), pass: r.status === 200 };
  });
  await scenario('Cross-bank', 'Bank B user list does not contain bank A users', 'no bank A email', async () => {
    const list = await adminB.get('/api/admin/users');
    const users = Array.isArray(list) ? list : list.items ?? list.users ?? [];
    const leaked = users.filter((u) => String(u.email).toLowerCase() === BOOTSTRAP());
    return { actual: `${users.length} users, ${leaked.length} from bank A`, pass: leaked.length === 0 && users.length >= 2 };
  });
  const roUserId = await userId(adminA, accounts.READ_ONLY.email);
  await scenario('Cross-bank', 'Bank B ADMIN requests a role change for a bank A user', '404 or 400', async () => {
    const r = await adminB.request('PUT', `/api/admin/users/${roUserId}/role`, { json: { role: 'ADMIN' } });
    return { actual: String(r.status), pass: [400, 404].includes(r.status), detail: JSON.stringify(r.body).slice(0, 120) };
  });

  await scenario('Observation (validation order)', 'READ_ONLY sends a malformed body to PUT /api/admin/settings (no settings:write)', '403 (permission checked first)', async () => {
    const r = await accounts.READ_ONLY.api.request('PUT', '/api/admin/settings', { json: { changes: [] } });
    return { actual: String(r.status), pass: r.status === 403, detail: 'request validation answers before @RequiresPermission; nothing is executed' };
  });

  // ---------------------------------------------------- SoD / privilege rules
  console.log('[a4] SoD scenarios');
  const roleChange = await adminA.request('PUT', `/api/admin/users/${roUserId}/role`, { json: { role: 'READ_WRITE' } });
  await scenario('SoD (maker/checker)', 'ADMIN submits a role change', '202 pending', async () => ({
    actual: String(roleChange.status), pass: roleChange.status === 202,
  }));
  await scenario('SoD (maker/checker)', 'Same ADMIN approves own role change', '409 SELF_APPROVAL_BLOCKED', async () => {
    const r = await adminA.request('POST', `/api/admin/changes/${roleChange.body.pendingChangeId}/approve`, { json: { comment: 'self' } });
    return { actual: `${r.status} ${r.body?.code ?? ''}`.trim(), pass: r.status === 409 };
  });
  await scenario('SoD (maker/checker)', 'APPROVER (different user) rejects it', '200', async () => {
    const r = await accounts.APPROVER.api.request('POST', `/api/admin/changes/${roleChange.body.pendingChangeId}/reject`, { json: { comment: 'a4 probe: reject' } });
    return { actual: String(r.status), pass: r.status === 200 };
  });
  const selfId = await userId(adminA, BOOTSTRAP());
  await scenario('Privilege rules', 'ADMIN requests a change of own role', '409', async () => {
    const r = await adminA.request('PUT', `/api/admin/users/${selfId}/role`, { json: { role: 'READ_ONLY' } });
    return { actual: String(r.status), pass: r.status === 409 || r.status === 400, detail: JSON.stringify(r.body).slice(0, 160) };
  });
  await scenario('Privilege rules', 'Bank ADMIN creates a PLATFORM_ADMIN user', '400', async () => {
    const r = await adminA.request('POST', '/api/admin/users', { json: { email: `${uniqueName('a4-esc')}@e2e.modocument.test`, displayName: 'escalation', role: 'PLATFORM_ADMIN', temporaryPassword: strongPassword() } });
    return { actual: String(r.status), pass: r.status === 400, detail: JSON.stringify(r.body).slice(0, 160) };
  });
  await scenario('Privilege rules', 'READ_WRITE submits USER_CREATE through the generic queue', '403', async () => {
    const r = await rw.request('POST', '/api/admin/changes', { json: { changeType: 'USER_CREATE', payload: { email: `${uniqueName('a4-q')}@e2e.modocument.test`, role: 'ADMIN', temporaryPassword: strongPassword() }, comment: 'probe' } });
    return { actual: String(r.status), pass: r.status === 403 };
  });

  // Document release SoD (gate B8): ADMIN authors and submits, tries to release.
  await scenario('SoD (document release)', 'Author (ADMIN) releases own submitted document', '409 DOC_SELF_RELEASE_BLOCKED', async () => {
    const form = new FormData();
    form.append('file', new Blob([textPdf(['A4 probe report', 'Synthetic content only'])]), 'a4-probe.pdf');
    form.append('flow', 'AUTO');
    form.append('name', uniqueName('A4 probe blueprint'));
    const bp = await adminA.request('POST', '/api/doccreation/blueprints', { form });
    if (![200, 201].includes(bp.status)) throw new Error(`blueprint upload ${bp.status}`);
    let blueprint = bp.body;
    const blueprintId = idOf(blueprint, 'id', 'blueprintId');
    blueprint = await adminA.get(`/api/doccreation/blueprints/${blueprintId}`);
    const strip = ({ sampleValue, removedText, ...rest }) => rest;
    await adminA.put(`/api/doccreation/blueprints/${blueprintId}/overlay`, {
      baseVersion: blueprint.currentVersion ?? blueprint.version,
      regions: (blueprint.regions ?? []).map(strip), edits: (blueprint.edits ?? []).map(strip), confirm: true,
    });
    const inst = await adminA.request('POST', '/api/doccreation/instances', { json: { blueprintId, title: uniqueName('A4 probe doc'), validationEnabled: false }, expect: [200, 201] });
    const instanceId = idOf(inst.body, 'id', 'instanceId');
    await adminA.request('POST', `/api/doccreation/instances/${instanceId}/generate`, { expect: 200 });
    await adminA.request('POST', `/api/doccreation/instances/${instanceId}/submit`, { expect: 200 });
    ctx.instanceId = instanceId;
    const r = await adminA.request('POST', `/api/doccreation/instances/${instanceId}/release`, { json: { comment: 'self' } });
    return { actual: `${r.status} ${r.body?.code ?? ''}`.trim(), pass: r.status === 409 };
  });
  if (ctx.instanceId) {
    await scenario('Cross-bank', 'Bank B ADMIN reads bank A document', '404 or 403', async () => {
      const r = await adminB.request('GET', `/api/doccreation/instances/${ctx.instanceId}`);
      return { actual: String(r.status), pass: notFound(r.status) };
    });
    await scenario('Cross-bank', 'Bank B ADMIN releases bank A document', '404 or 403', async () => {
      const r = await adminB.request('POST', `/api/doccreation/instances/${ctx.instanceId}/release`, { json: { comment: 'x' } });
      return { actual: String(r.status), pass: notFound(r.status) };
    });
    await scenario('SoD (document release, positive control)', 'APPROVER (not the author) releases it', '200', async () => {
      const r = await accounts.APPROVER.api.request('POST', `/api/doccreation/instances/${ctx.instanceId}/release`, { json: { comment: 'a4 probe release' } });
      return { actual: String(r.status), pass: r.status === 200 };
    });
  }


  // ------------------------------------------ personal data per role (DPO)
  console.log('[a4] personal-data visibility scenarios');
  const SYN_NIC = 'S0101010000001';
  const pii = { templateId: null, jobId: null };
  await scenario('Personal data', 'READ_WRITE runs a CSV with synthetic NIC/phone/name to VALIDATED on a bank-shared template', 'record indexed', async () => {
    const tpl = await adminA.post('/Templates/CreateWithFields', {
      name: uniqueName('A4 synthetic customers'), description: 'rbac probe (synthetic PII)', visibility: 'BANK',
      fields: [
        { fieldName: 'full_name', dataType: 'Text', rules: [], piiKind: 'NAME' },
        { fieldName: 'nic', dataType: 'Text', rules: [], piiKind: 'NIC' },
        { fieldName: 'phone', dataType: 'Text', rules: [], piiKind: 'PHONE' },
      ],
    });
    pii.templateId = idOf(tpl, 'templateId', 'id');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a4-pii-'));
    const file = path.join(dir, `${uniqueName('a4-customers')}.csv`);
    fs.writeFileSync(file, `full_name,nic,phone\nSynthetic Person One,${SYN_NIC},+230 5000 0001\n`);
    const created = await uploadForIngestion(rw, { filePath: file, templateId: pii.templateId, runUntil: 'VALIDATED', wait: true });
    pii.jobId = created.ingestionJobId;
    await waitForJob(rw, pii.jobId, (j) => j.currentStep === 'VALIDATED');
    let total = 0;
    for (let i = 0; i < 30 && total < 1; i += 1) {
      total = (await rw.get(`/api/doccreation/records?templateId=${pii.templateId}`)).total;
      if (total < 1) await new Promise((r) => setTimeout(r, 1000));
    }
    return { actual: `${total} record(s)`, pass: total >= 1 };
  });
  if (pii.templateId) {
    // MaskingPolicy.screenChannel: ADMIN/APPROVER/READ_WRITE = SCREEN_WORKING (raw unless the bank
    // sets privacy.masking.screenWorking); READ_ONLY = SCREEN_READONLY (masked).
    const viewers = [
      ['READ_WRITE (owner)', rw, 'raw'], ['APPROVER (colleague)', accounts.APPROVER.api, 'raw'],
      ['ADMIN (colleague)', adminA, 'raw'], ['READ_ONLY (colleague)', accounts.READ_ONLY.api, 'masked'],
      ['ADMIN (bank B)', adminB, 'none'], ['PLATFORM_ADMIN', pa1, 'none'],
    ];
    for (const [who, api, expected] of viewers) {
      await scenario('Personal data', `${who} searches the bank-shared template's records`, expected === 'none' ? 'no access (403/404)' : `${expected} NIC`, async () => {
        const r = await api.request('GET', `/api/doccreation/records?templateId=${pii.templateId}`);
        if (r.status !== 200) return { actual: String(r.status), pass: expected === 'none' && [403, 404].includes(r.status) };
        const nic = r.body.items?.[0]?.values?.nic;
        const seen = nic === SYN_NIC ? 'raw' : nic ? 'masked' : 'no rows';
        return { actual: `200, ${seen} NIC${nic && nic !== SYN_NIC ? ` (${nic})` : ''}`, pass: seen === expected };
      });
    }
    await scenario('Personal data', 'READ_WRITE (owner) exports the job as CSV', 'NIC masked in the file', async () => {
      const res = await fetch(`${rw.baseURL}/api/ingestion/jobs/${pii.jobId}/export/file?format=csv`, { headers: { Authorization: `Bearer ${rw.token}` } });
      const text = await res.text();
      return { actual: `${res.status}, raw NIC in file: ${text.includes(SYN_NIC)}`, pass: res.status === 200 && !text.includes(SYN_NIC) && text.length > 0 };
    });
  }

  // ------------------------------------------------- joiner / mover / leaver
  console.log('[a4] JML scenarios');
  await scenario('JML mover', 'Role change applies only after a second admin approves; old token dies', 'pending 202, token 200 before, 401 after, new role after sign-in', async () => {
    const { email } = await createUserViaMakerChecker({ role: 'READ_ONLY', displayName: 'A4 mover' });
    const before = await sessionFor(email);
    const id = await userId(adminA, email);
    const sub = await adminA.request('PUT', `/api/admin/users/${id}/role`, { json: { role: 'READ_WRITE' }, expect: 202 });
    const stillOk = (await before.request('GET', '/api/auth/me')).status;
    await approveChange(adminA2, sub.body.pendingChangeId, 'a4 mover');
    const after = (await before.request('GET', '/api/auth/me')).status;
    const fresh = await sessionFor(email, { refresh: true });
    const me = await fresh.get('/api/auth/me');
    const role = me.role ?? me.user?.role ?? (me.roles ?? [])[0];
    return { actual: `202, ${stillOk}, ${after}, ${role}`, pass: stillOk === 200 && after === 401 && role === 'READ_WRITE' };
  });
  await scenario('JML leaver', 'Disabled account is inert the moment the approval lands', 'token 401, password login 401', async () => {
    const { email } = await createUserViaMakerChecker({ role: 'READ_WRITE', displayName: 'A4 leaver' });
    const session = await sessionFor(email);
    const id = await userId(adminA, email);
    const sub = await adminA.request('PUT', `/api/admin/users/${id}/disabled`, { json: { disabled: true }, expect: 202 });
    await approveChange(adminA2, sub.body.pendingChangeId, 'a4 leaver');
    const tokenStatus = (await session.request('GET', '/api/auth/me')).status;
    const login = await new ApiClient().request('POST', '/api/auth/login', { json: { email, password: getAccount(email).password } });
    return { actual: `token ${tokenStatus}, login ${login.status}`, pass: tokenStatus === 401 && login.status === 401 };
  });
  await scenario('Account review', 'ADMIN exports the user list with last-login dates (CSV)', '200 text/csv with lastLogin column', async () => {
    const res = await fetch(`${adminA.baseURL}/api/admin/users/export`, { headers: { Authorization: `Bearer ${adminA.token}` } });
    const text = await res.text();
    const header = text.split('\n')[0];
    return { actual: `${res.status} ${res.headers.get('content-type')}; header: ${header}`, pass: res.status === 200 && /last/i.test(header) };
  });
  await scenario('Account review', 'Access-review worksheet lists every bank A account with inactive90d', '200, rows >= 6', async () => {
    const r = await adminA.get('/api/admin/users/access-review');
    const rows = Array.isArray(r) ? r : r.rows ?? r.users ?? r.items ?? [];
    const keys = rows[0] ? Object.keys(rows[0]).join(',') : '';
    return { actual: `${rows.length} rows; fields ${keys}`, pass: rows.length >= 6 };
  });

  // ------------------------------------------------------------------ output
  const summary = {
    started, finished: new Date().toISOString(),
    stack: 'e2e/run-local.sh (production profile, PostgreSQL, RLS role modocument_app)',
    accounts: Object.fromEntries(Object.entries(accounts).map(([k, v]) => [k, { bank: v.bank }])),
    probes: { total: probes.length, pass: probes.filter((p) => p.result === 'PASS').length,
      allowed: probes.filter((p) => p.actual === 'allow').length, denied: probes.filter((p) => p.actual === 'deny').length,
      unauthenticated: probes.filter((p) => p.actual === 'unauthenticated').length },
    scenarios: { total: scenarios.length, pass: scenarios.filter((s) => s.result === 'PASS').length },
  };
  fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });
  fs.writeFileSync(OUT_JSON, JSON.stringify({ summary, probes, scenarios }, null, 2) + '\n');
  fs.writeFileSync(OUT_MD, renderMarkdown(summary, probes, scenarios));
  console.log(`[a4] probes ${summary.probes.pass}/${summary.probes.total} pass; scenarios ${summary.scenarios.pass}/${summary.scenarios.total} pass`);
  console.log(`[a4] wrote ${path.relative(ROOT, OUT_JSON)} and ${path.relative(ROOT, OUT_MD)}`);
}

function renderMarkdown(summary, probes, scenarios) {
  const labels = [...new Set(probes.map((p) => p.account))];
  const short = { allow: 'A', deny: 'D', unauthenticated: 'U' };
  const lines = [
    '# A4 evidence: live per-role RBAC probe',
    '',
    'Generated by `scripts/compliance/rbac-probe.mjs`. Do not edit by hand.',
    '',
    `- Command: \`SKIP_BUILD=1 E2E_RUN_CMD='node scripts/compliance/rbac-probe.mjs' e2e/run-local.sh\``,
    `- Run: ${summary.started} to ${summary.finished} (UTC)`,
    `- Stack: ${summary.stack}; backend \`target/modocument-1.2.0.jar\`, fresh database per run, synthetic users and data only.`,
    `- Expected values come from \`docs/security/role-permission-matrix.csv\`. A = the permission gate let the call through (the handler answered: 200/202/400/404/409); D = 403 from \`@RequiresPermission\`; U = 401 (no token).`,
    `- Raw results: \`docs/compliance/evidence/A4-rbac-probe.json\`.`,
    '',
    '## Summary',
    '',
    `| Item | Result |`, `|---|---|`,
    `| Permission probes (role x route) | ${summary.probes.pass}/${summary.probes.total} as expected |`,
    `| of which allowed / denied / unauthenticated | ${summary.probes.allowed} / ${summary.probes.denied} / ${summary.probes.unauthenticated} |`,
    `| Isolation, SoD and JML scenarios | ${summary.scenarios.pass}/${summary.scenarios.total} as expected |`,
    '',
    '## Permission probes',
    '',
    'Cell = actual (status); a mismatch with the matrix is marked **FAIL**.',
    '',
    `| Capability | Permission | Route | ${labels.join(' | ')} |`,
    `|---|---|---|${labels.map(() => '---').join('|')}|`,
  ];
  const keyOf = (p) => `${p.permission}|${p.method} ${p.url}`;
  const rows = [...new Map(probes.map((p) => [keyOf(p), p])).values()];
  for (const r of rows) {
    const cells = labels.map((l) => {
      const p = probes.find((x) => x.account === l && keyOf(x) === keyOf(r));
      return p ? `${short[p.actual]} (${p.status})${p.result === 'FAIL' ? ' **FAIL**' : ''}` : '';
    });
    lines.push(`| ${r.capability} | \`${r.permission}\` | \`${r.method} ${r.url.replace(/\b999999\b/, '{absent-id}')}\` | ${cells.join(' | ')} |`);
  }
  lines.push('', '## Isolation, separation of duties and JML scenarios', '', '| # | Area | Scenario | Expected | Actual | Result |', '|---|---|---|---|---|---|');
  scenarios.forEach((s, i) => lines.push(`| ${i + 1} | ${s.area} | ${s.name}${s.detail ? ` (${s.detail.replace(/\|/g, '/')})` : ''} | ${s.expected} | ${String(s.actual).replace(/\|/g, '/')} | ${s.result} |`));
  return lines.join('\n') + '\n';
}

main().catch((error) => {
  console.error('[a4] probe aborted:', error);
  process.exit(1);
});
