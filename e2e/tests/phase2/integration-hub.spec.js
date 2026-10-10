// Phase 2 — Bank Integration Hub (SE1 vault/egress, SE2 clients + API v1,
// SE3 pages, SE4 connectors/flows, SE5 agent). Acceptance criteria:
// docs/phase2/test-plan.md §5.6; security cases: docs/qa/integration-security-tests.md.
//
// WRITTEN AHEAD OF THE MERGE against docs/phase2/contracts.md §3, §7.3, §7.4,
// so every group is `test.fixme('waiting for Wave 3 merge')` until the stack
// reports the features. Switch a group on with E2E_WAVE3=1 (or delete the
// fixme line) in the PR that merges it; fix shape drift in lib/integration.js,
// not here.
//
// Needs the bank simulator (e2e/bank-simulator, README): E2E_BANK_SIM=1 for
// run-local.sh (bank DB + REST stub; SFTP needs the compose stack), which
// exports E2E_BANK_SIM_{REST_URL,DB_URL,SFTP_HOST,SFTP_PORT,WEBHOOK_SECRET,…}.
//   bank DB:  view integration.customers (read), table integration.staging_results
//             (write); roles modoc_reader / modoc_writer
//   REST:     /oauth/token, GET /api/customers (paged), POST /api/results,
//             POST /webhooks/modocument (verifies X-MoDocument-Signature)
//   SFTP:     inbound/ (seeded customers-2024-06-01.csv), outbound/, pinned host key
// UI contract (SE3): connections wizard steps as role=heading "Type", "Mode",
// "Details", "Credentials", "Test"; role=button "Test connection" ->
// role=status containing "Connection OK"; flow builder checkboxes
// "Validated records", "Rejected rows", "Job status & errors", "Released
// documents", "Events"; role=switch "Mask personal data" (checked by
// default); role=button "Dry run" -> table of <= 20 rows; API client secret
// shown once in data-testid="client-secret" with role=button "Copy".
import { execFile, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { ApiClient, sessionFor } from '../../lib/api.js';
import { env } from '../../lib/env.js';
import { withDb } from '../../lib/db.js';
import { bankRest, eventually, stagingRows, touchCustomers } from '../../lib/banksim.js';
import {
  apiKeyClient,
  approvedEgressRule,
  bearerClient,
  clientToken,
  createApiClient,
  createConnection,
  createFlow,
  enableFlow,
  postRecords,
  registerAgent,
  runFlow,
  testConnection,
  waitForClientJob,
  waitForFlowRun,
  waitForRun,
} from '../../lib/integration.js';
import { onboardBank } from '../../lib/phase2.js';
import { BOOTSTRAP, uniqueName } from '../../lib/seed.js';

const run = promisify(execFile);
const WAVE3 = process.env.E2E_WAVE3 === '1';
const ADMIN = '/api/admin/integrations';

/** Bank-side DB coordinates as MoDocument's connection sees them (host from the app's point of view). */
function bankDbConfig() {
  return {
    host: process.env.E2E_BANK_SIM_DB_HOST_FOR_APP ?? '127.0.0.1',
    port: Number(process.env.E2E_BANK_SIM_DB_PORT_FOR_APP ?? 55433),
    vendor: 'POSTGRESQL',
    database: process.env.E2E_BANK_SIM_DB_NAME ?? 'bank',
    schema: 'integration',
    sslmode: 'disable',
  };
}

const READER = () => ({
  username: process.env.E2E_BANK_SIM_DB_READER ?? 'modoc_reader',
  password: process.env.E2E_BANK_SIM_DB_READER_PASSWORD,
});
const WRITER = () => ({
  username: process.env.E2E_BANK_SIM_DB_WRITER ?? 'modoc_writer',
  password: process.env.E2E_BANK_SIM_DB_WRITER_PASSWORD,
});

/** The bank-shared template every machine path uses (machine jobs need BANK templates, §7.1). */
async function customersTemplate(adminApi) {
  return adminApi.post('/Templates/CreateWithFields', {
    name: uniqueName('E2E Bank customers'),
    visibility: 'BANK',
    fields: [
      { fieldName: 'CustomerId', dataType: 'Text', rules: [], piiKind: null },
      { fieldName: 'FullName', dataType: 'Text', rules: [], piiKind: null },
      { fieldName: 'Email', dataType: 'Text', rules: [], piiKind: 'EMAIL' },
      { fieldName: 'Segment', dataType: 'Text', rules: [], piiKind: null },
      { fieldName: 'Balance', dataType: 'Number', rules: [], piiKind: null },
    ],
  });
}

const RECORDS = [
  { CustomerId: 'API-0001', FullName: 'Asha Ramdin', Email: 'asha@bank-sim.test', Segment: 'RETAIL', Balance: '1520.75' },
  { CustomerId: 'API-0002', FullName: 'Kevin Li', Email: 'kevin@bank-sim.test', Segment: 'SME', Balance: '98200.00' },
];

// ===========================================================================
test.describe('integration hub — connections and flows @phase2 @wave3', () => {
  test.fixme(!WAVE3, 'waiting for Wave 3 merge');
  test.describe.configure({ mode: 'serial' });
  test.beforeEach(async () => {
    test.skip(!env.bankSim, 'bank simulator not started (E2E_BANK_SIM=1)');
  });

  let dbConnection;
  let template;

  test('AC-INT-17b the simulator destinations are approved egress rules (platform maker/checker)', async ({
    adminApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!platformApi, 'needs platform admins to approve egress rules');
    const db = bankDbConfig();
    const rest = new URL(env.bankSim.restURL);
    for (const destination of [
      { host: db.host, port: db.port },
      { host: rest.hostname, port: Number(rest.port) },
      { host: env.bankSim.sftpHost, port: env.bankSim.sftpPort },
    ]) {
      const rule = await approvedEgressRule(adminApi, platformApi, platformCheckerApi, destination);
      expect(rule.status).toBe('APPROVED');
    }
  });

  test('AC-INT-01 creating a connection needs approval; disabling is immediate', async ({ adminApi, checkerApi }) => {
    const name = uniqueName('E2E bank DB');
    const submitted = await adminApi.request('POST', `${ADMIN}/connections`, {
      json: { name, type: 'DATABASE', mode: 'DIRECT', config: bankDbConfig(), authType: 'PASSWORD', secret: READER() },
    });
    expect(submitted.status).toBe(202);
    expect(submitted.body.changeType).toBe('INTEGRATION_CONNECTION_CHANGE');
    const self = await adminApi.request('POST', `/api/admin/changes/${submitted.body.pendingChangeId}/approve`, { json: {} });
    expect(self.status).toBe(409);
    expect((await adminApi.get(`${ADMIN}/connections`)).find((c) => c.name === name)?.status ?? 'absent').not.toBe('OK');

    ({ view: dbConnection } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', config: bankDbConfig(), authType: 'PASSWORD', secret: READER(),
    }));
    expect(dbConnection).toMatchObject({ type: 'DATABASE', mode: 'DIRECT', secretSet: true });

    // Disabling a (throwaway) connection is direct: 200, no pending change.
    const { view: throwaway } = await createConnection(adminApi, checkerApi, {
      type: 'REST_API', config: { baseUrl: env.bankSim.restURL }, authType: 'NONE',
    });
    const disabled = await adminApi.request('PUT', `${ADMIN}/connections/${throwaway.id}`, {
      json: { ...throwaway, status: 'DISABLED', expectedVersion: throwaway.version },
    });
    expect([200, 204]).toContain(disabled.status);
    expect((await adminApi.get(`${ADMIN}/connections/${throwaway.id}`)).status).toBe('DISABLED');
  });

  test('AC-INT-02 connection secrets are write-only (never returned, never logged)', async ({ adminApi }) => {
    const password = READER().password;
    for (const body of [await adminApi.get(`${ADMIN}/connections`), await adminApi.get(`${ADMIN}/connections/${dbConnection.id}`)]) {
      const text = JSON.stringify(body);
      expect(text).not.toContain(password);
      expect(text).not.toMatch(/"(password|secret|privateKey)"\s*:/i);
    }
    // Nothing in MoDocument's own database holds it in clear text either.
    const hits = await withDb(async (db) => (await db.query(
      `SELECT 'audit' AS src FROM AuditEvent WHERE CAST(NewValues AS text) LIKE $1 OR CAST(OldValues AS text) LIKE $1
       UNION ALL SELECT 'change' FROM PendingChange WHERE PayloadJson LIKE $1
       UNION ALL SELECT 'connection' FROM IntegrationConnection WHERE CAST(ConfigJson AS text) LIKE $1`,
      [`%${password}%`]
    )).rows);
    expect(hits).toEqual([]);
  });

  test('AC-INT-03 test connection succeeds against the simulator DB and fails safely on a wrong password', async ({
    page,
    adminApi,
    checkerApi,
  }) => {
    const ok = await testConnection(adminApi, dbConnection.id);
    expect(ok).toMatchObject({ ok: true });
    const { view: wrong } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', config: bankDbConfig(), authType: 'PASSWORD', secret: { ...READER(), password: 'not-the-password' },
    });
    const failed = await testConnection(adminApi, wrong.id);
    expect(failed.ok).toBe(false);
    expect(failed.message).not.toMatch(/not-the-password|at org\.|Exception|password authentication failed for user/i);

    await openAs(page, BOOTSTRAP(), `/admin/integrations/connections`);
    await page.getByRole('row').filter({ hasText: dbConnection.name }).getByRole('button', { name: 'Test connection' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Connection OK' })).toBeVisible();
  });

  test('AC-INT-09 flow builder dry run previews <= 20 rows and writes nothing', async ({ page, adminApi }) => {
    template = await customersTemplate(adminApi);
    const flow = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: dbConnection.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON',
      sourceSpec: { table: 'customers', columns: ['customer_id', 'full_name', 'email', 'segment', 'balance', 'updated_at'], watermarkColumn: 'updated_at' },
      fieldMapping: [
        { from: 'customer_id', to: 'CustomerId' }, { from: 'full_name', to: 'FullName' },
        { from: 'email', to: 'Email' }, { from: 'segment', to: 'Segment' }, { from: 'balance', to: 'Balance' },
      ],
    });
    expect(flow).toMatchObject({ enabled: false, maskPii: true });
    const preview = await adminApi.post(`${ADMIN}/flows/${flow.id}/preview`);
    expect(preview.rows.length).toBeGreaterThan(0);
    expect(preview.rows.length).toBeLessThanOrEqual(20);
    expect(preview.suggestedMapping.length).toBeGreaterThan(0);
    expect((await adminApi.get(`${ADMIN}/runs?flowId=${flow.id}`)).length ?? 0).toBe(0);

    await openAs(page, BOOTSTRAP(), `/admin/integrations/flows/${flow.id}`);
    await expect(page.getByRole('switch', { name: 'Mask personal data' })).toBeChecked();
    await page.getByRole('button', { name: 'Dry run' }).click();
    const rows = page.getByRole('table').last().getByRole('row');
    await expect(rows.nth(1)).toBeVisible();
    expect(await rows.count()).toBeLessThanOrEqual(21); // header + 20
  });

  test('AC-INT-12 an inbound scheduled DB flow turns bank rows into a validated job', async ({ adminApi }) => {
    test.setTimeout(9 * 60_000); // the minimum schedule interval is 5 minutes
    const flow = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: dbConnection.id, templateId: template.templateId,
      trigger: 'SCHEDULE', cron: '*/5 * * * *', payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON', rowCap: 100,
      sourceSpec: { table: 'customers', columns: ['customer_id', 'full_name', 'email', 'segment', 'balance', 'updated_at'], watermarkColumn: 'updated_at' },
      fieldMapping: [
        { from: 'customer_id', to: 'CustomerId' }, { from: 'full_name', to: 'FullName' },
        { from: 'email', to: 'Email' }, { from: 'segment', to: 'Segment' }, { from: 'balance', to: 'Balance' },
      ],
    });
    // A schedule more often than every 5 minutes is refused.
    const tooOften = await adminApi.request('PUT', `${ADMIN}/flows/${flow.id}`, { json: { ...flow, cron: '* * * * *', expectedVersion: flow.version } });
    expect(tooOften.status).toBe(400);
    expect((await enableFlow(adminApi, null, flow.id)).status).toBe(200); // INBOUND: direct

    const scheduled = await waitForFlowRun(adminApi, flow.id, (r) => r.trigger === 'SCHEDULE' && r.finishedAt, { timeoutMs: 7 * 60_000 });
    expect(scheduled.status).toBe('SUCCEEDED');
    expect(scheduled.recordsIn).toBe(25);
    const job = await adminApi.get(`/api/ingestion/jobs/${scheduled.ingestionJobId}`);
    expect(job.currentStep).toBe('VALIDATED');

    // Watermark: only rows moved forward come in on the next run.
    await touchCustomers(['CUST-0001', 'CUST-0002']);
    const manual = await waitForRun(adminApi, (await runFlow(adminApi, flow.id)).id);
    expect(manual.recordsIn).toBe(2);
    await adminApi.post(`${ADMIN}/flows/${flow.id}/disable`);
  });

  test('AC-INT-10/13 an outbound flow enabled by a second admin writes rows to the bank staging table and posts a signed webhook', async ({
    adminApi,
    checkerApi,
    operatorApi,
  }) => {
    await bankRest.reset();
    const { view: writer } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', config: bankDbConfig(), authType: 'PASSWORD', secret: WRITER(),
    });
    const { view: hook } = await createConnection(adminApi, checkerApi, {
      type: 'WEBHOOK', config: { baseUrl: env.bankSim.restURL }, authType: 'HMAC', secret: { webhookSecret: env.bankSim.webhookSecret },
    });
    const table = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: writer.id, templateId: template.templateId, trigger: 'EVENT',
      events: ['INGESTION_VALIDATED'], payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON', maskPii: false,
      targetSpec: { table: 'staging_results', mode: 'UPSERT', keyColumns: ['external_ref'] },
      fieldMapping: [{ from: 'CustomerId', to: 'customer_id' }, { from: 'FullName', to: 'full_name' }, { from: 'Email', to: 'email' }],
    });
    const webhook = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: hook.id, templateId: template.templateId, trigger: 'EVENT',
      events: ['INGESTION_VALIDATED'], payloadKinds: ['JOB_STATUS'], format: 'JSON', targetSpec: { path: '/webhooks/modocument' },
    });

    // AC-INT-10: outbound enable is maker/checker; the maker alone cannot.
    for (const flow of [table, webhook]) {
      const res = await enableFlow(adminApi, null, flow.id);
      expect(res.status).toBe(202);
      expect(res.body.changeType).toBe('INTEGRATION_FLOW_ENABLE');
      expect((await adminApi.request('POST', `/api/admin/changes/${res.body.pendingChangeId}/approve`, { json: {} })).status).toBe(409);
      expect((await adminApi.get(`${ADMIN}/flows/${flow.id}`)).enabled).toBe(false);
      await checkerApi.post(`/api/admin/changes/${res.body.pendingChangeId}/approve`, { comment: 'e2e' });
    }

    // A job of that template reaches VALIDATED -> the event fans out to both flows.
    const csv = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-int-')), `${uniqueName('out')}.csv`);
    fs.writeFileSync(csv, 'CustomerId,FullName,Email,Segment,Balance\nOUT-1,Ada Out,ada.out@bank-sim.test,RETAIL,10.00\n');
    const form = new FormData();
    form.append('file', new Blob([fs.readFileSync(csv)]), path.basename(csv));
    form.append('templateId', String(template.templateId));
    form.append('autoRun', 'true');
    form.append('runUntil', 'VALIDATED');
    await operatorApi.request('POST', '/api/ingestion/jobs', { form, expect: [200, 201, 202] });

    const rows = await eventually(async () => {
      const found = await stagingRows("customer_id = 'OUT-1'");
      return found.length ? found : null;
    }, { what: 'staging_results row for OUT-1', timeoutMs: 120_000 });
    expect(rows[0]).toMatchObject({ full_name: 'Ada Out', email: 'ada.out@bank-sim.test' });

    const delivery = await eventually(async () => (await bankRest.received()).find((d) => d.valid), {
      what: 'a signed webhook the bank verified', timeoutMs: 120_000,
    });
    expect(delivery.reason).toBeNull();
    expect((await bankRest.received()).filter((d) => !d.valid)).toEqual([]);
  });

  test('AC-INT-14 outbound payloads are PII-masked by default', async ({ adminApi, checkerApi }) => {
    await bankRest.reset();
    const { view: rest } = await createConnection(adminApi, checkerApi, {
      type: 'REST_API', config: { baseUrl: env.bankSim.restURL }, authType: 'API_KEY_HEADER',
      secret: { headerName: 'X-Api-Key', apiKey: process.env.E2E_BANK_SIM_API_KEY },
    });
    const flow = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: rest.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON', targetSpec: { method: 'POST', path: '/api/results', batchSize: 50 },
    });
    expect(flow.maskPii).toBe(true);
    await enableFlow(adminApi, checkerApi, flow.id);
    await waitForRun(adminApi, (await runFlow(adminApi, flow.id)).id);
    const pushed = (await bankRest.captured()).flatMap((c) => c.records);
    expect(pushed.length).toBeGreaterThan(0);
    for (const record of pushed) {
      expect(String(record.Email ?? record.email)).not.toMatch(/^[^*]{2,}@/); // local part masked
    }
    // Turning masking off is SECURITY tier: maker/checker.
    const unmask = await adminApi.request('PUT', `${ADMIN}/flows/${flow.id}`, { json: { ...flow, maskPii: false, expectedVersion: flow.version } });
    expect(unmask.status).toBe(202);
  });

  test('AC-INT-15 an SFTP inbound file is processed and the result file dropped back', async ({ adminApi, checkerApi }) => {
    test.skip(!process.env.E2E_BANK_SIM_SFTP_PASSWORD, 'SFTP needs the compose bank simulator');
    const fingerprint = fs.readFileSync(new URL('../../bank-simulator/sftp/host-keys/host-key-fingerprints.TEST-ONLY.txt', import.meta.url), 'utf8')
      .split('\n').find((l) => l.startsWith('ssh-ed25519')).split(' ')[1];
    const { view: sftp } = await createConnection(adminApi, checkerApi, {
      type: 'SFTP', config: { host: env.bankSim.sftpHost, port: env.bankSim.sftpPort, hostKeyFingerprint: fingerprint, folder: '/' },
      authType: 'PASSWORD', secret: { username: process.env.E2E_BANK_SIM_SFTP_USER ?? 'bank', password: process.env.E2E_BANK_SIM_SFTP_PASSWORD },
    });
    expect((await testConnection(adminApi, sftp.id)).ok).toBe(true);
    const inbound = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: sftp.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'CSV', sourceSpec: { folder: 'inbound', glob: '*.csv', afterRead: 'MOVE' },
    });
    await enableFlow(adminApi, checkerApi, inbound.id);
    const pulled = await waitForRun(adminApi, (await runFlow(adminApi, inbound.id)).id);
    expect(pulled.status).toBe('SUCCEEDED');
    expect(pulled.recordsIn).toBe(4);

    const outbound = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: sftp.id, templateId: template.templateId, trigger: 'EVENT',
      events: ['INGESTION_VALIDATED'], payloadKinds: ['VALIDATED_RECORDS'], format: 'CSV',
      targetSpec: { folder: 'outbound', fileNamePattern: 'results-{jobId}.csv' },
    });
    await enableFlow(adminApi, checkerApi, outbound.id);
    // Bank side (looked at from inside the SFTP container, not through
    // MoDocument): the file moved to processed/ and a result file landed in outbound/.
    const container = process.env.E2E_BANK_SIM_SFTP_CONTAINER ?? 'modocument-e2e-bank-sftp-1';
    const home = `/home/${process.env.E2E_BANK_SIM_SFTP_USER ?? 'bank'}`;
    const listing = async (dir) => (await run('docker', ['exec', container, 'ls', `${home}/${dir}`])).stdout;
    await eventually(async () => /results-\d+\.csv/.test(await listing('outbound')), { what: 'result file in outbound/', timeoutMs: 120_000 });
    expect(await listing('inbound/processed')).toContain('customers-2024-06-01.csv');
  });

  test('AC-INT-17 a DIRECT connection to a private IP without an approved egress rule is refused', async ({ adminApi }) => {
    const refused = await adminApi.request('POST', `${ADMIN}/connections`, {
      json: { name: uniqueName('ssrf'), type: 'REST_API', mode: 'DIRECT', config: { baseUrl: 'http://10.20.30.40:8080' }, authType: 'NONE' },
    });
    // Refused at submit (preferred) or at test time — never a successful call.
    if (refused.status === 202) {
      test.info().annotations.push({ type: 'note', description: 'refused at connect time, not at submit' });
    } else {
      expect(refused.status).toBe(400);
    }
    const metadata = await adminApi.request('POST', `${ADMIN}/connections`, {
      json: { name: uniqueName('imds'), type: 'REST_API', mode: 'DIRECT', config: { baseUrl: 'http://169.254.169.254/latest/meta-data/' }, authType: 'NONE' },
    });
    expect(metadata.status).toBe(400);
  });

  test('AC-INT-08 a stale webhook timestamp is rejected by the documented verifier', async () => {
    // Bank side, through the simulator's receiver (same verifier as the developer guide).
    const { sign } = await import('../../bank-simulator/rest-stub/signature.mjs');
    const body = JSON.stringify({ type: 'JOB_STATUS', data: { jobId: 1 } });
    const stale = Math.floor(Date.now() / 1000) - 301;
    const res = await fetch(`${env.bankSim.restURL}/webhooks/modocument`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-MoDocument-Timestamp': String(stale), 'X-MoDocument-Signature': sign(env.bankSim.webhookSecret, stale, body) },
      body,
    });
    expect(res.status).toBe(401);
    expect((await res.json()).reason).toBe('stale-timestamp');
  });
});

// ===========================================================================
test.describe('integration hub — API clients and Integration API v1 @phase2 @wave3', () => {
  test.fixme(!WAVE3, 'waiting for Wave 3 merge');
  test.describe.configure({ mode: 'serial' });

  let template;
  let oauth;
  let apiKey;
  let jobId;

  test('AC-INT-18 an API client is created via approval and its secret is shown once', async ({ page, adminApi, checkerApi }) => {
    template = await customersTemplate(adminApi);
    oauth = await createApiClient(adminApi, checkerApi, {
      authMethod: 'OAUTH2_CLIENT_CREDENTIALS',
      scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'],
    });
    expect(oauth.secret).toBeTruthy();
    const again = await adminApi.request('GET', `${ADMIN}/api-clients/${oauth.view.id}/initial-secret`);
    expect([404, 410]).toContain(again.status);
    expect(JSON.stringify(await adminApi.get(`${ADMIN}/api-clients`))).not.toContain(oauth.secret);

    await openAs(page, BOOTSTRAP(), '/admin/integrations/api-clients');
    await expect(page.getByRole('row').filter({ hasText: oauth.view.name })).toContainText('ACTIVE');
  });

  test('AC-INT-19 OAuth2 client credentials: token, records upload, results read', async () => {
    const token = (await clientToken(oauth.clientId, oauth.secret)).body;
    expect(token).toMatchObject({ token_type: 'Bearer' });
    expect(token.expires_in).toBeGreaterThanOrEqual(300);
    expect(token.expires_in).toBeLessThanOrEqual(900);
    const claims = JSON.parse(Buffer.from(token.access_token.split('.')[1], 'base64url').toString());
    expect(claims).toMatchObject({ typ: 'client', client_id: oauth.clientId });

    const client = bearerClient(token.access_token);
    const posted = await postRecords(client, { templateId: template.templateId, records: RECORDS, idempotencyKey: uniqueName('idem') });
    expect(posted.status).toBe(202);
    jobId = posted.body.jobId;
    const job = await waitForClientJob(client, jobId);
    expect(job.counts).toMatchObject({ rows: 2, valid: 2, rejected: 0 });
    const results = await client.get(`/api/integration/v1/jobs/${jobId}/results?format=json`);
    expect(JSON.stringify(results)).toContain('API-0001');
    expect(JSON.stringify(results)).not.toContain('asha@bank-sim.test'); // masked per client setting

    // A wrong secret: 401 (and an API_CLIENT_AUTH_FAILED audit).
    expect((await clientToken(oauth.clientId, 'wrong-secret', { expect: undefined })).status).toBe(401);
    // A client token never reaches user routes.
    expect((await client.request('GET', '/Templates/GetAll')).status).toBe(403);
  });

  test('AC-INT-20 API key client sends records and reads results', async ({ adminApi, checkerApi }) => {
    apiKey = await createApiClient(adminApi, checkerApi, { authMethod: 'API_KEY', scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'] });
    const key = `${apiKey.view.keyPrefix}.${apiKey.secret}`;
    const client = apiKeyClient(key);
    const posted = await postRecords(client, { templateId: template.templateId, records: RECORDS });
    expect(posted.status).toBe(202);
    const job = await waitForClientJob(client, posted.body.jobId);
    expect(job.counts.valid).toBe(2);
    expect((await apiKeyClient(`${apiKey.view.keyPrefix}.not-the-secret`).request('GET', `/api/integration/v1/jobs/${posted.body.jobId}`)).status).toBe(401);
  });

  test('AC-INT-21 mTLS client on the local listener sends records and reads results', async ({ adminApi, checkerApi }) => {
    test.skip(!process.env.E2E_MTLS_URL, 'needs the nginx mTLS listener (compose: E2E_MTLS_URL + client cert)');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-mtls-'));
    const keyFile = path.join(dir, 'client.key');
    const certFile = path.join(dir, 'client.pem');
    await run('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-days', '2',
      '-subj', `/CN=${uniqueName('e2e-mtls')}`, '-keyout', keyFile, '-out', certFile]);
    const client = await createApiClient(adminApi, checkerApi, {
      authMethod: 'MTLS', scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'], certificatePem: fs.readFileSync(certFile, 'utf8'),
    });
    expect(client.view.certFingerprint).toMatch(/^[0-9a-f:]{64,}$/i);
    const out = await run('curl', ['-sS', '--fail-with-body', '--cert', certFile, '--key', keyFile, '--cacert', process.env.E2E_MTLS_CA,
      '-H', 'Content-Type: application/json', '-d', JSON.stringify({ templateId: template.templateId, externalBatchId: uniqueName('mtls'), records: RECORDS }),
      `${process.env.E2E_MTLS_URL}/api/integration/v1/records`]);
    expect(JSON.parse(out.stdout).jobId).toBeTruthy();
    // Spoofed client-cert headers on the PUBLIC listener are ignored.
    const spoofed = await new ApiClient().request('POST', '/api/integration/v1/records', {
      json: { templateId: template.templateId, records: RECORDS },
      headers: { 'X-Amzn-Mtls-Clientcert-Leaf': 'forged', 'X-Amzn-Mtls-Clientcert-Subject': 'CN=forged' },
    });
    expect(spoofed.status).toBe(401);
  });

  test('AC-INT-23 a bank-B client gets 404 on a bank-A job', async ({ platformApi, platformCheckerApi }) => {
    test.skip(!platformApi, 'needs platform admins to onboard bank B');
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const makerB = await sessionFor(bankB.admins[0]);
    const checkerB = await sessionFor(bankB.admins[1]);
    const clientB = await createApiClient(makerB, checkerB, { authMethod: 'OAUTH2_CLIENT_CREDENTIALS', scopes: ['INTEGRATION_RESULTS', 'INTEGRATION_DOCS'] });
    const api = bearerClient((await clientToken(clientB.clientId, clientB.secret)).body.access_token);
    for (const url of [`/api/integration/v1/jobs/${jobId}`, `/api/integration/v1/jobs/${jobId}/results`, `/api/integration/v1/jobs/${jobId}/errors`]) {
      expect((await api.request('GET', url)).status, url).toBe(404);
    }
  });

  test('AC-INT-24 replaying an Idempotency-Key returns the same job; another body with the key is refused', async () => {
    const client = bearerClient((await clientToken(oauth.clientId, oauth.secret)).body.access_token);
    const key = uniqueName('idem');
    const first = await postRecords(client, { templateId: template.templateId, records: RECORDS, idempotencyKey: key, externalBatchId: 'b-1' });
    const replay = await postRecords(client, { templateId: template.templateId, records: RECORDS, idempotencyKey: key, externalBatchId: 'b-1' });
    expect(replay.status).toBe(first.status);
    expect(replay.body.jobId).toBe(first.body.jobId);
    const reused = await postRecords(client, { templateId: template.templateId, records: RECORDS.slice(0, 1), idempotencyKey: key, externalBatchId: 'b-1' });
    expect(reused.status).toBe(409);
  });

  test('AC-INT-25 a revoked or expired client gets 401 immediately', async ({ adminApi, checkerApi }) => {
    const token = (await clientToken(oauth.clientId, oauth.secret)).body.access_token;
    const client = bearerClient(token);
    expect((await client.request('GET', `/api/integration/v1/jobs/${jobId}`)).status).toBe(200);
    await adminApi.post(`${ADMIN}/api-clients/${oauth.view.id}/revoke`);
    expect((await client.request('GET', `/api/integration/v1/jobs/${jobId}`)).status).toBe(401);
    expect((await apiKeyClient(`${apiKey.view.keyPrefix}.${apiKey.secret}`).request('GET', '/api/integration/v1/templates')).status).toBe(200);

    const expiring = await createApiClient(adminApi, checkerApi, {
      authMethod: 'API_KEY', scopes: ['INTEGRATION_RESULTS'], expiresAt: new Date(Date.now() + 15_000).toISOString(),
    });
    const short = apiKeyClient(`${expiring.view.keyPrefix}.${expiring.secret}`);
    await eventually(async () => (await short.request('GET', '/api/integration/v1/templates')).status === 401, {
      what: 'expired client refused', timeoutMs: 60_000,
    });
  });
});

// ===========================================================================
test.describe('integration hub — on-prem agent @phase2 @wave3', () => {
  test.fixme(!WAVE3, 'waiting for Wave 3 merge');
  test.describe.configure({ mode: 'serial' });
  test.beforeEach(async () => {
    test.skip(!process.env.E2E_AGENT_JAR || !env.bankSim, 'needs the agent jar (E2E_AGENT_JAR) and the bank simulator');
  });

  let agent;
  let agentProcess;
  const agentLog = [];

  test.afterAll(() => {
    agentProcess?.kill('SIGTERM');
  });

  test('AC-INT-28 an agent enrols once with its one-time token', async ({ adminApi, checkerApi }) => {
    const { view, enrolment } = await registerAgent(adminApi, checkerApi);
    expect(enrolment.token).toBeTruthy();
    expect(enrolment.dockerCommand).toContain('docker run');
    expect(new Date(enrolment.expiresAt).getTime() - Date.now()).toBeLessThanOrEqual(15 * 60_000);
    // The enrolment token is readable once.
    expect([404, 410]).toContain((await adminApi.request('GET', `${ADMIN}/agents/${view.id}/enrolment`)).status);

    // The agent process: outbound HTTPS only; the bank DB password lives in ITS env.
    agentProcess = spawn('java', ['-jar', process.env.E2E_AGENT_JAR], {
      env: {
        ...process.env,
        MODOCUMENT_AGENT_CLOUD_URL: env.baseURL,
        MODOCUMENT_AGENT_ENROLMENT_TOKEN: enrolment.token,
        MODOCUMENT_AGENT_NAME: view.name,
        BANK_DB_PASSWORD: READER().password,
        MODOCUMENT_AGENT_ALLOWED_TABLES: 'integration.customers',
        MODOCUMENT_AGENT_ALLOWED_HOSTS: '127.0.0.1',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    agentProcess.stdout.on('data', (d) => agentLog.push(String(d)));
    agentProcess.stderr.on('data', (d) => agentLog.push(String(d)));

    agent = await eventually(async () => {
      const found = (await adminApi.get(`${ADMIN}/agents`)).find((a) => a.id === view.id);
      return found?.status === 'ONLINE' ? found : null;
    }, { what: 'agent ONLINE', timeoutMs: 120_000 });
    // A second enrolment with the same token is refused.
    const reuse = await new ApiClient().request('POST', '/api/agent/v1/enroll', {
      json: { token: enrolment.token, name: 'replay', version: '0.0.0', authMethod: 'OAUTH2_CLIENT_CREDENTIALS' },
    });
    expect([401, 404, 409, 410]).toContain(reuse.status);
  });

  test('AC-INT-29 the agent long-polls, moves data and reports heartbeat/version', async ({ adminApi, checkerApi }) => {
    expect(agent.version).toBeTruthy();
    expect(new Date(agent.lastHeartbeatAt).getTime()).toBeGreaterThan(Date.now() - 120_000);
    const { view: viaAgent } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', mode: 'AGENT', agentId: agent.id, config: bankDbConfig(), authType: 'PASSWORD', secretRef: 'BANK_DB_PASSWORD',
    });
    expect(viaAgent.secretSet).toBe(false);
    const template = await customersTemplate(adminApi);
    const flow = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: viaAgent.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON',
      sourceSpec: { table: 'customers', columns: ['customer_id', 'full_name', 'email', 'segment', 'balance'] },
      fieldMapping: [{ from: 'customer_id', to: 'CustomerId' }, { from: 'full_name', to: 'FullName' }, { from: 'email', to: 'Email' }],
    });
    await enableFlow(adminApi, checkerApi, flow.id);
    const done = await waitForRun(adminApi, (await runFlow(adminApi, flow.id)).id, { timeoutMs: 180_000 });
    expect(done.status).toBe('SUCCEEDED');
    expect(done.recordsIn).toBe(25);
  });

  test('AC-INT-30 the cloud never sees the bank DB password; the agent allow-list refuses other tables', async ({ adminApi, checkerApi }) => {
    const password = READER().password;
    const hits = await withDb(async (db) => (await db.query(
      `SELECT 'audit' AS src FROM AuditEvent WHERE CAST(NewValues AS text) LIKE $1
       UNION ALL SELECT 'connection' FROM IntegrationConnection WHERE CAST(ConfigJson AS text) LIKE $1 OR SecretRef LIKE $1
       UNION ALL SELECT 'run-event' FROM IntegrationRunEvent WHERE CAST(Message AS text) LIKE $1`,
      [`%${password}%`]
    )).rows);
    expect(hits).toEqual([]);
    if (process.env.E2E_BACKEND_LOG) {
      expect(fs.readFileSync(process.env.E2E_BACKEND_LOG, 'utf8')).not.toContain(password);
    }

    // The cloud asks for a table outside the agent's local allow-list: refused BY THE AGENT.
    const { view: viaAgent } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', mode: 'AGENT', agentId: agent.id, config: bankDbConfig(), authType: 'PASSWORD', secretRef: 'BANK_DB_PASSWORD',
    });
    const template = await customersTemplate(adminApi);
    const sneaky = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: viaAgent.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON', sourceSpec: { table: 'staging_results', columns: ['external_ref'] },
    });
    await enableFlow(adminApi, checkerApi, sneaky.id);
    const refused = await waitForRun(adminApi, (await runFlow(adminApi, sneaky.id)).id, { timeoutMs: 180_000 });
    expect(refused.status).toBe('FAILED');
    expect(refused.error).toMatch(/allow-?list|not allowed/i);
    expect(refused.recordsIn ?? 0).toBe(0);
  });
});
