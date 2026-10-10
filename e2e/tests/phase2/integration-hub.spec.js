// Phase 2 — Bank Integration Hub (SE1 vault/egress, SE2 clients + API v1,
// SE3 pages, SE4 connectors/flows, SE5 agent). Wave 3; the full
// pull -> process -> push loop is Wave 4. Acceptance criteria:
// docs/phase2/test-plan.md §5.6.
//
// Needs the QA "bank simulator" (Wave 3): a bank PostgreSQL (schema
// bank_sim: view v_customers_out, table stg_results), a REST stub (records
// pull + signed-webhook receiver that verifies X-MoDocument-Signature and
// keeps a log at GET /__received) and an SFTP server with a pinned host key;
// started by run-local.sh / docker-compose.e2e.yml.
// UI contract (SE3): /admin/integrations/connections wizard steps as
// role=heading names "Type", "Mode", "Details", "Credentials", "Test";
// role=button name="Test connection" -> role=status containing "Connection OK";
// flow builder checkboxes role=checkbox names "Validated records",
// "Rejected rows", "Job status & errors", "Released documents", "Events";
// role=switch name="Mask personal data" (checked by default);
// role=button name="Dry run" -> table of <= 20 rows; API client secret
// shown once in data-testid="client-secret" with role=button name="Copy".
import { test } from '../../lib/fixtures.js';

test.describe('integration hub — connections and flows @phase2 @wave3', () => {
  test.fixme('AC-INT-01 creating a connection needs approval; disabling is immediate', async () => {});
  test.fixme('AC-INT-02 connection secrets are write-only (never returned, never logged)', async () => {});
  test.fixme('AC-INT-03 test connection succeeds against the simulator DB and fails safely on a wrong password', async () => {});
  test.fixme('AC-INT-09 flow builder dry run previews 20 rows and writes nothing', async () => {});
  test.fixme('AC-INT-10 enabling an outbound flow is impossible without a second admin', async () => {});
  test.fixme('AC-INT-12 an inbound scheduled DB flow turns bank rows into a validated job', async () => {});
  test.fixme('AC-INT-13 an outbound flow writes validated rows to the bank staging table and posts a signed webhook', async () => {});
  test.fixme('AC-INT-14 outbound payloads are PII-masked by default', async () => {});
  test.fixme('AC-INT-15 an SFTP inbound file is processed and the result file dropped back', async () => {});
  test.fixme('AC-INT-17 a DIRECT connection to a private IP without an approved egress rule is refused', async () => {});
  test.fixme('AC-INT-08 a stale webhook timestamp is rejected by the documented verifier', async () => {});
});

test.describe('integration hub — API clients and Integration API v1 @phase2 @wave3', () => {
  test.fixme('AC-INT-18 an API client is created via approval and its secret is shown once', async () => {});
  test.fixme('AC-INT-19 OAuth2 client credentials: token, records upload, results read', async () => {});
  test.fixme('AC-INT-20 API key client (with request signing) sends records and reads results', async () => {});
  test.fixme('AC-INT-21 mTLS client on the local listener sends records and reads results', async () => {});
  test.fixme('AC-INT-23 a bank-B client gets 404 on a bank-A job', async () => {});
  test.fixme('AC-INT-24 replaying an Idempotency-Key returns the same job', async () => {});
  test.fixme('AC-INT-25 a revoked or expired client gets 401 immediately', async () => {});
});

test.describe('integration hub — on-prem agent @phase2 @wave3', () => {
  test.fixme('AC-INT-28 an agent enrols once with its one-time token', async () => {});
  test.fixme('AC-INT-29 the agent long-polls, moves data and reports heartbeat/version', async () => {});
  test.fixme('AC-INT-30 the cloud never sees the bank DB password; the agent allow-list refuses other tables', async () => {});
});
