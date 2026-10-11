// Phase 2 — Bank Integration Hub (SE1 vault/egress, SE2 clients + API v1,
// SE3 pages, SE4 connectors/flows, SE5 agent). Acceptance criteria:
// docs/phase2/test-plan.md §5.6; security cases: docs/qa/integration-security-tests.md.
//
// Written ahead of the merge against docs/phase2/contracts.md §3, §7.3, §7.4
// and aligned with the merged API in Wave 4 (QA): every group is LIVE. The API
// clients group needs only the stack; the connections/flows group skips
// without the bank simulator (E2E_BANK_SIM=1), the agent group also without
// the agent jar (E2E_AGENT_JAR; run-local.sh finds agent/target/*.jar). Fix
// shape drift in lib/integration.js, not here.
//
// What still needs more than run-local.sh: AC-INT-15 (SFTP: the atmoz/sftp
// container of the compose bank simulator) and AC-INT-21 (the nginx mTLS
// listener: E2E_MTLS_URL + E2E_MTLS_CA). Both skip with a reason here.
//
// Needs the bank simulator (e2e/bank-simulator, README): E2E_BANK_SIM=1 for
// run-local.sh (bank DB + REST stub; SFTP needs the compose stack), which
// exports E2E_BANK_SIM_{REST_URL,DB_URL,SFTP_HOST,SFTP_PORT,WEBHOOK_SECRET,…}.
//   bank DB:  view integration.customers (read), table integration.staging_results
//             (write); roles modoc_reader / modoc_writer
//   REST:     /oauth/token, GET /api/customers (paged), POST /api/results,
//             POST /webhooks/modocument (verifies X-MoDocument-Signature)
//   SFTP:     inbound/ (seeded customers-2024-06-01.csv), outbound/, pinned host key
// UI contract (SE3, as merged): connections list row button "Test <name>" ->
// role=status containing "Connection OK"; flow builder role=switch "Mask
// personal data (PII)" (checked by default); role=button "Dry run / preview"
// -> section "Dry run result" with a table of <= 20 rows; API client rows
// carry a status chip ("Active").
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
const ADMIN = '/api/admin/integrations';

/**
 * Bank-side DB coordinates as MoDocument's connection sees them (host from
 * the app's point of view). The user name is a CONFIG value; only the
 * password is a secret (DatabaseIntegrationConnector).
 */
function bankDbConfig(account = READER()) {
  return {
    host: process.env.E2E_BANK_SIM_DB_HOST_FOR_APP ?? '127.0.0.1',
    port: Number(process.env.E2E_BANK_SIM_DB_PORT_FOR_APP ?? 55433),
    vendor: 'POSTGRESQL',
    database: process.env.E2E_BANK_SIM_DB_NAME ?? 'bank',
    schema: 'integration',
    sslmode: 'disable',
    username: account.username,
  };
}

function READER() {
  return {
    username: process.env.E2E_BANK_SIM_DB_READER ?? 'modoc_reader',
    password: process.env.E2E_BANK_SIM_DB_READER_PASSWORD,
  };
}
function WRITER() {
  return {
    username: process.env.E2E_BANK_SIM_DB_WRITER ?? 'modoc_writer',
    password: process.env.E2E_BANK_SIM_DB_WRITER_PASSWORD,
  };
}
/** The connection secret of a DATABASE connection: the password only. */
const dbSecret = (account) => ({ password: account.password });

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

/**
 * The file names in an SFTP folder of the bank simulator, read over SFTP: a
 * throwaway (never enabled) inbound flow's dry run, which lists names and
 * sizes and moves nothing (SftpIntegrationConnector#listFiles). Replaces the
 * former `docker exec … ls`, which needs a Docker CLI the CI runner lacks.
 */
async function sftpListing(adminApi, connectionId, templateId, folder) {
  const probe = await createFlow(adminApi, {
    name: uniqueName(`E2E sftp listing ${folder}`), direction: 'INBOUND', connectionId, templateId,
    trigger: 'MANUAL', payloadKinds: ['VALIDATED_RECORDS'], format: 'CSV', sourceSpec: { folder, glob: '*' },
  });
  try {
    const preview = await adminApi.post(`${ADMIN}/flows/${probe.id}/preview`);
    return (preview.rows ?? []).map((row) => row.fileName);
  } finally {
    await adminApi.request('DELETE', `${ADMIN}/flows/${probe.id}`);
  }
}

// ===========================================================================
test.describe('integration hub — connections and flows @phase2 @wave3', () => {
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

  test('AC-INT-01 creating a connection needs approval; deleting an unused one is immediate', async ({ adminApi, checkerApi }) => {
    const name = uniqueName('E2E bank DB');
    const submitted = await adminApi.request('POST', `${ADMIN}/connections`, {
      json: { name, type: 'DATABASE', mode: 'DIRECT', config: bankDbConfig(), authType: 'BASIC', secret: dbSecret(READER()) },
    });
    expect(submitted.status).toBe(202);
    expect(submitted.body.changeType).toBe('INTEGRATION_CONNECTION_CHANGE');
    const self = await adminApi.request('POST', `/api/admin/changes/${submitted.body.pendingChangeId}/approve`, { json: {} });
    expect(self.status).toBe(409);
    expect((await adminApi.get(`${ADMIN}/connections`)).find((c) => c.name === name)?.status ?? 'absent').not.toBe('OK');

    // The checker applies the change that is already waiting.
    await checkerApi.post(`/api/admin/changes/${submitted.body.pendingChangeId}/approve`, { comment: 'e2e' });
    dbConnection = (await adminApi.get(`${ADMIN}/connections`)).find((c) => c.name === name);
    expect(dbConnection).toMatchObject({ type: 'DATABASE', mode: 'DIRECT', secretSet: true, status: 'UNTESTED' });

    // Renaming is direct (200); a host or credential change is maker/checker (202).
    const renamed = await adminApi.request('PUT', `${ADMIN}/connections/${dbConnection.id}`, {
      json: { ...dbConnection, name: `${name} (renamed)`, expectedVersion: dbConnection.version },
    });
    expect(renamed.status).toBe(200);
    expect(renamed.body.name).toBe(`${name} (renamed)`);
    // BUG-07: the 200 carries the version from BEFORE the rename (JPA bumps
    // it at commit), so re-read the connection for the next edit.
    const current = await adminApi.get(`${ADMIN}/connections/${dbConnection.id}`);
    const moved = await adminApi.request('PUT', `${ADMIN}/connections/${dbConnection.id}`, {
      json: { ...current, config: { ...current.config, port: 1 }, expectedVersion: current.version },
    });
    expect(moved.status).toBe(202);
    expect(moved.body.changeType).toBe('INTEGRATION_CONNECTION_CHANGE');
    await adminApi.post(`/api/admin/changes/${moved.body.pendingChangeId}/cancel`, {}).catch(() => {});
    dbConnection = await adminApi.get(`${ADMIN}/connections/${dbConnection.id}`);
    expect(dbConnection.config.port).not.toBe('1');

    // Deleting a (throwaway) connection no flow uses is direct: 204, no pending change.
    const { view: throwaway } = await createConnection(adminApi, checkerApi, {
      type: 'REST_API', config: { baseUrl: env.bankSim.restURL }, authType: 'NONE',
    });
    expect((await adminApi.request('DELETE', `${ADMIN}/connections/${throwaway.id}`)).status).toBe(204);
    expect((await adminApi.request('GET', `${ADMIN}/connections/${throwaway.id}`)).status).toBe(404);
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
    // S48: what IS stored is ciphertext (base64, key version set), never the password.
    const sealed = await withDb(async (db) => (await db.query(
      'SELECT SecretCiphertext AS c, SecretKeyVersion AS v FROM IntegrationConnection WHERE IntegrationConnectionId = $1',
      [dbConnection.id]
    )).rows[0]);
    expect(sealed.c).toMatch(/^[A-Za-z0-9+/=]{40,}$/);
    expect(Buffer.from(sealed.c, 'base64').toString('latin1')).not.toContain(password);
    expect(sealed.v).toBeGreaterThanOrEqual(1);
    if (process.env.E2E_BACKEND_LOG) {
      expect(fs.readFileSync(process.env.E2E_BACKEND_LOG, 'utf8')).not.toContain(password);
    }
  });

  test('AC-INT-03 test connection succeeds against the simulator DB and fails safely on a wrong password', async ({
    page,
    adminApi,
    checkerApi,
  }) => {
    const ok = await testConnection(adminApi, dbConnection.id);
    expect(ok).toMatchObject({ ok: true });
    // Wrong credentials. The local scratch cluster trusts loopback logins (no
    // password check), so the bad credential here is an unknown ROLE with a
    // wrong password: the server refuses it on any cluster.
    const { view: wrong } = await createConnection(adminApi, checkerApi, {
      type: 'DATABASE', config: { ...bankDbConfig(), username: 'e2e_no_such_role' }, authType: 'BASIC',
      secret: { password: 'not-the-password' },
    });
    const failed = await testConnection(adminApi, wrong.id);
    expect(failed.ok).toBe(false);
    expect(failed.message).toBeTruthy();
    expect(failed.message).not.toMatch(/not-the-password|at org\.|at com\.|Exception|\.java:/i);

    await openAs(page, BOOTSTRAP(), `/admin/integrations/connections`);
    await page.getByRole('button', { name: `Test ${dbConnection.name}`, exact: true }).click();
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
    const rows = page.getByRole('region', { name: 'Dry run result' }).getByRole('row');
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
      type: 'DATABASE', config: bankDbConfig(WRITER()), authType: 'BASIC', secret: dbSecret(WRITER()),
    });
    const { view: hook } = await createConnection(adminApi, checkerApi, {
      type: 'WEBHOOK', config: { url: env.bankSim.restURL }, authType: 'HMAC_SIGNATURE', secret: { hmacSecret: env.bankSim.webhookSecret },
    });
    const table = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: writer.id, templateId: template.templateId, trigger: 'EVENT',
      events: ['INGESTION_VALIDATED'], payloadKinds: ['VALIDATED_RECORDS'], format: 'JSON', maskPii: false,
      targetSpec: { table: 'staging_results', mode: 'UPSERT', keyColumns: ['external_ref'] },
      fieldMapping: [
        { from: 'CustomerId', to: 'external_ref' }, { from: 'CustomerId', to: 'customer_id' },
        { from: 'FullName', to: 'full_name' }, { from: 'Email', to: 'email' },
      ],
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
      type: 'REST_API', config: { baseUrl: env.bankSim.restURL, apiKeyHeader: 'X-Api-Key' }, authType: 'API_KEY_HEADER',
      secret: { apiKey: process.env.E2E_BANK_SIM_API_KEY },
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
    // Turning masking off is SECURITY tier: maker/checker (the enabled flow is
    // disabled until a second admin approves).
    const enabled = await adminApi.get(`${ADMIN}/flows/${flow.id}`);
    expect(enabled.enabled).toBe(true);
    const unmask = await adminApi.request('PUT', `${ADMIN}/flows/${flow.id}`, { json: { ...enabled, maskPii: false, expectedVersion: enabled.version } });
    expect(unmask.status).toBe(202);
    expect(unmask.body.changeType).toBe('INTEGRATION_FLOW_ENABLE');
    expect((await adminApi.get(`${ADMIN}/flows/${flow.id}`)).enabled).toBe(false);
  });

  test('AC-INT-15 an SFTP inbound file is processed and the result file dropped back', async ({ adminApi, checkerApi }) => {
    test.skip(!process.env.E2E_BANK_SIM_SFTP_PASSWORD, 'SFTP needs the compose bank simulator');
    const fingerprint = fs.readFileSync(new URL('../../bank-simulator/sftp/host-keys/host-key-fingerprints.TEST-ONLY.txt', import.meta.url), 'utf8')
      .split('\n').find((l) => l.startsWith('ssh-ed25519')).split(' ')[1];
    const { view: sftp } = await createConnection(adminApi, checkerApi, {
      type: 'SFTP',
      config: {
        host: env.bankSim.sftpHost, port: env.bankSim.sftpPort, hostKeyFingerprint: fingerprint, folder: '/',
        username: process.env.E2E_BANK_SIM_SFTP_USER ?? 'bank', sftpAuth: 'PASSWORD',
      },
      authType: 'BASIC', secret: { password: process.env.E2E_BANK_SIM_SFTP_PASSWORD },
    });
    expect((await testConnection(adminApi, sftp.id)).ok).toBe(true);

    // The result flow is enabled FIRST, so the job the inbound pull validates
    // fans out to it.
    const outbound = await createFlow(adminApi, {
      direction: 'OUTBOUND', connectionId: sftp.id, templateId: template.templateId, trigger: 'EVENT',
      events: ['INGESTION_VALIDATED'], payloadKinds: ['VALIDATED_RECORDS'], format: 'CSV',
      targetSpec: { folder: 'outbound', fileNamePattern: 'results-{base}.csv' }, // {base} = validated-records-job-<id>
    });
    await enableFlow(adminApi, checkerApi, outbound.id);

    const inbound = await createFlow(adminApi, {
      direction: 'INBOUND', connectionId: sftp.id, templateId: template.templateId, trigger: 'MANUAL',
      payloadKinds: ['VALIDATED_RECORDS'], format: 'CSV', sourceSpec: { folder: 'inbound', glob: '*.csv', afterRead: 'MOVE' },
    });
    await enableFlow(adminApi, checkerApi, inbound.id);
    const pulled = await waitForRun(adminApi, (await runFlow(adminApi, inbound.id)).id);
    expect(pulled.status, `${pulled.error ?? ''} ${JSON.stringify(pulled.events ?? [])}`).toBe('SUCCEEDED');
    expect(pulled.recordsIn).toBeGreaterThan(0); // the seeded file (one file = one ingestion)

    // Bank side, read over SFTP without Docker (the CI runner has no docker
    // CLI): a list-only dry run of a throwaway inbound flow on the folder —
    // the connector's preview lists names and sizes and moves nothing.
    const listing = (folder) => sftpListing(adminApi, sftp.id, template.templateId, folder);
    await eventually(async () => (await listing('outbound')).some((name) => /^results-validated-records-job-\d+\.csv$/.test(name)), {
      what: 'result file in outbound/', timeoutMs: 120_000,
    });
    expect(await listing('inbound/processed')).toContain('customers-2024-06-01.csv');
    expect(await listing('inbound')).not.toContain('customers-2024-06-01.csv');
  });

  test('AC-INT-17 a DIRECT connection to a private IP without an approved egress rule is refused', async ({ adminApi, checkerApi }) => {
    // S1: 10.20.30.40 has no approved rule. The merged API validates the URL
    // shape at submit and refuses the destination at connect time
    // (BankEgressGuard, right before any socket is opened).
    const { view: privateIp } = await createConnection(adminApi, checkerApi, {
      type: 'REST_API', config: { baseUrl: 'http://10.20.30.40:8080' }, authType: 'NONE',
    });
    const refused = await testConnection(adminApi, privateIp.id);
    expect(refused.ok).toBe(false);
    expect(refused.message).toMatch(/not an allowed egress target/i);
    expect(refused.message).toMatch(/no approved egress rule/i);

    // S2: the cloud metadata endpoint is refused ALWAYS (no rule can open it),
    // and an egress rule for it cannot even be requested.
    const imdsRule = await adminApi.request('POST', `${ADMIN}/egress-rules`, {
      json: { host: '169.254.169.254', port: 80, reason: 'e2e: must be refused' },
    });
    expect(imdsRule.status).toBe(400);
    const { view: imds } = await createConnection(adminApi, checkerApi, {
      type: 'REST_API', config: { baseUrl: 'http://169.254.169.254/latest/meta-data' }, authType: 'NONE',
    });
    const metadata = await testConnection(adminApi, imds.id);
    expect(metadata.ok).toBe(false);
    expect(metadata.message).toMatch(/cloud metadata endpoint/i);
    // BUG-06 (P4): the metadata URL is accepted at submit (202) and only
    // refused at connect; the security plan (S2) wants 400 at submit. The
    // refusal above is what protects the instance; see
    // IntegrationSecurityQaTest#bug06MetadataDestinationRefusedAtSubmit.
    for (const id of [privateIp.id, imds.id]) await adminApi.request('DELETE', `${ADMIN}/connections/${id}`);
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
    await expect(page.getByRole('row').filter({ hasText: oauth.view.name })).toContainText(/active/i);
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
    const wrong = await clientToken(oauth.clientId, 'wrong-secret', { expect: 401 });
    expect(wrong.body).toMatchObject({ error: 'invalid_client' });
    const audited = await withDb(async (db) => (await db.query(
      "SELECT COUNT(*)::int AS n FROM AuditEvent WHERE Action = 'API_CLIENT_AUTH_FAILED' AND EntityId = $1",
      [String(oauth.view.id)]
    )).rows[0].n);
    expect(audited).toBeGreaterThanOrEqual(1);
    // A client token never reaches user routes.
    expect((await client.request('GET', '/Templates/GetAll')).status).toBe(403);
  });

  test('AC-INT-20 API key client sends records and reads results', async ({ adminApi, checkerApi }) => {
    apiKey = await createApiClient(adminApi, checkerApi, { authMethod: 'API_KEY', scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'] });
    // The one-time secret of an API-key client is the whole key, <keyPrefix>.<secret>.
    expect(apiKey.secret.startsWith(`${apiKey.view.keyPrefix}.`)).toBe(true);
    const client = apiKeyClient(apiKey.secret);
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
    expect((await apiKeyClient(apiKey.secret).request('GET', '/api/integration/v1/templates')).status).toBe(200);

    const expiring = await createApiClient(adminApi, checkerApi, {
      authMethod: 'API_KEY', scopes: ['INTEGRATION_RESULTS'], expiresAt: new Date(Date.now() + 30_000).toISOString(),
    });
    const short = apiKeyClient(expiring.secret);
    await eventually(async () => (await short.request('GET', '/api/integration/v1/templates')).status === 401, {
      what: 'expired client refused', timeoutMs: 60_000,
    });
  });
});

// ===========================================================================
test.describe('integration hub — on-prem agent @phase2 @wave3', () => {
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
  // The agent's own log is the first thing to read when an agent test fails.
  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status !== testInfo.expectedStatus && agentLog.length) {
      await testInfo.attach('agent.log', { body: agentLog.join(''), contentType: 'text/plain' });
      console.log(`--- agent log (last 4 KB) ---\n${agentLog.join('').slice(-4096)}`);
    }
  });

  test('AC-INT-28 an agent enrols once with its one-time token', async ({ adminApi, checkerApi }) => {
    const { view, enrolment } = await registerAgent(adminApi, checkerApi);
    expect(enrolment.token).toBeTruthy();
    expect(enrolment.dockerCommand).toContain('docker run');
    expect(new Date(enrolment.expiresAt).getTime() - Date.now()).toBeLessThanOrEqual(15 * 60_000);
    // The enrolment token is readable once.
    expect([404, 410]).toContain((await adminApi.request('GET', `${ADMIN}/agents/${view.id}/enrolment`)).status);

    // The agent process (a local java process here, the container elsewhere):
    // outbound calls only; the bank DB password lives in ITS environment
    // (MODOCUMENT_SECRET_<REF>, agent/README.md), the bank's allow-list in a
    // file only the "bank" writes. Plain http to MoDocument is allowed for
    // this loopback stack only (MODOCUMENT_ALLOW_INSECURE_HTTP).
    const db = bankDbConfig();
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-agent-'));
    const allowList = path.join(home, 'allowlist.yml');
    fs.writeFileSync(allowList, [
      'destinations:',
      `  - host: ${db.host}`,
      `    ports: [${db.port}]`,
      'database:',
      '  tables:',
      '    - integration.customers',
      '',
    ].join('\n'));
    const childEnv = { ...process.env };
    for (const key of Object.keys(childEnv)) if (key.startsWith('E2E_')) delete childEnv[key]; // the agent knows nothing of the suite
    agentProcess = spawn('java', ['-Xmx256m', '-jar', process.env.E2E_AGENT_JAR], {
      env: {
        ...childEnv,
        MODOCUMENT_URL: process.env.E2E_API_URL ?? env.baseURL,
        MODOCUMENT_ALLOW_INSECURE_HTTP: 'true',
        MODOCUMENT_ENROLMENT_TOKEN: enrolment.token,
        MODOCUMENT_AGENT_NAME: view.name,
        MODOCUMENT_AGENT_STATE_DIR: path.join(home, 'state'),
        MODOCUMENT_AGENT_ALLOWLIST: allowList,
        MODOCUMENT_SECRETS_DIR: path.join(home, 'secrets'),
        MODOCUMENT_SECRET_BANK_DB_PASSWORD: READER().password,
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
      type: 'DATABASE', mode: 'AGENT', agentId: agent.id, config: bankDbConfig(), authType: 'BASIC', secretRef: 'BANK_DB_PASSWORD',
    });
    // secretSet is true for an AGENT connection too (it means "credentials are
    // configured"); what matters is that only the NAME is stored, no ciphertext.
    expect(viaAgent).toMatchObject({ mode: 'AGENT', agentId: agent.id, secretRef: 'BANK_DB_PASSWORD' });
    const stored = await withDb(async (db) => (await db.query(
      'SELECT SecretCiphertext FROM IntegrationConnection WHERE IntegrationConnectionId = $1', [viaAgent.id]
    )).rows[0]);
    expect(stored.secretciphertext).toBeNull();
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
      type: 'DATABASE', mode: 'AGENT', agentId: agent.id, config: bankDbConfig(), authType: 'BASIC', secretRef: 'BANK_DB_PASSWORD',
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
