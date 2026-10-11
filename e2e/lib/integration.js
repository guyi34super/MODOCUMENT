// Bank Integration Hub helpers (Wave 3) — first written against the
// Wave-0/3 contracts (docs/phase2/contracts.md §3, §7.3, §7.4), aligned with
// the merged API in Wave 4 (api/controller/Integration*, EgressRuleController,
// ApiClientAdminController, IntegrationAgentAdminController,
// AgentProtocolController; docs/integration/developer-guide.md). When a shape
// moves again, fix it HERE, once, and keep the specs as they are.
import { ApiClient, approveChange } from './api.js';
import { uniqueName } from './seed.js';

const ADMIN = '/api/admin/integrations';

// ------------------------------------------------------------- egress rules

/**
 * A DIRECT connection to a private address (the simulator on 127.0.0.1)
 * needs an APPROVED BankEgressRule (SE1): the bank admin requests it
 * (200, PENDING; `reason` is required), a platform admin submits the
 * approval (202 EGRESS_RULE_APPROVE) and the second platform admin applies
 * it. A destination that is already requested or approved answers 409: the
 * existing rule is reused. Returns the EgressRuleView.
 */
export async function approvedEgressRule(bankAdmin, platformMaker, platformChecker, { host, port }) {
  const requested = await bankAdmin.request('POST', `${ADMIN}/egress-rules`, {
    json: { host, port, reason: 'E2E bank simulator' },
    expect: [200, 201, 202, 409],
  });
  const existing = async () => (await bankAdmin.get(`${ADMIN}/egress-rules`))
    .find((r) => r.host === host && r.port === port && ['PENDING', 'APPROVED'].includes(r.status));
  const rule = requested.status === 409 ? await existing() : requested.body;
  if (rule.status === 'APPROVED') return rule;
  const approval = await platformMaker.request('POST', `/api/platform/egress-rules/${rule.id}/approve`, {
    expect: 202,
  });
  await approveChange(platformChecker, approval.body.pendingChangeId);
  return (await bankAdmin.get(`${ADMIN}/egress-rules`)).find((r) => r.id === rule.id);
}

// --------------------------------------------------------------- connections

/**
 * Creates a connection through maker/checker (202 INTEGRATION_CONNECTION_CHANGE,
 * the checker applies it) and returns its ConnectionView.
 * @param {{name?, type, mode?, agentId?, config, authType?, secret?, secretRef?}} request
 */
export async function createConnection(maker, checker, request) {
  const name = request.name ?? uniqueName(`E2E ${request.type}`);
  const submitted = await maker.request('POST', `${ADMIN}/connections`, {
    json: { mode: 'DIRECT', ...request, name },
    expect: 202,
  });
  await approveChange(checker, submitted.body.pendingChangeId);
  const list = await maker.get(`${ADMIN}/connections`);
  const view = list.find((c) => c.name === name);
  if (!view) throw new Error(`connection ${name} not listed after approval`);
  return { view, change: submitted.body };
}

export async function testConnection(api, id) {
  return (await api.request('POST', `${ADMIN}/connections/${id}/test`, { expect: [200, 202] })).body;
}

// --------------------------------------------------------------------- flows

/** Creates a flow (disabled until enabled) and returns its FlowView. */
export async function createFlow(api, request) {
  const res = await api.request('POST', `${ADMIN}/flows`, {
    json: { name: uniqueName(`E2E flow ${request.direction}`), maskPii: true, ...request },
    expect: [200, 201],
  });
  return res.body;
}

/**
 * Enables a flow. INBOUND: direct (200). OUTBOUND: 202 INTEGRATION_FLOW_ENABLE,
 * which `checker` approves (pass null to leave it pending).
 */
export async function enableFlow(maker, checker, flowId) {
  const res = await maker.request('POST', `${ADMIN}/flows/${flowId}/enable`, { expect: [200, 202] });
  if (res.status === 202 && checker) await approveChange(checker, res.body.pendingChangeId);
  return res;
}

export async function runFlow(api, flowId) {
  return (await api.request('POST', `${ADMIN}/flows/${flowId}/run`, { expect: [200, 201, 202] })).body;
}

export async function getRun(api, runId) {
  return api.get(`${ADMIN}/runs/${runId}`);
}

/** Polls a run until it is finished (SUCCEEDED/FAILED/DEAD…); returns the run detail. */
export async function waitForRun(api, runId, { timeoutMs = 120_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let run;
  while (Date.now() < deadline) {
    run = await getRun(api, runId);
    if (run.finishedAt) return run;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`run ${runId} did not finish (last status ${run?.status})`);
}

/** Polls the run history of a flow until `predicate(run)` matches one. */
export async function waitForFlowRun(api, flowId, predicate, { timeoutMs = 120_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const runs = await api.get(`${ADMIN}/runs?flowId=${flowId}`);
    const list = Array.isArray(runs) ? runs : runs.runs ?? runs.items ?? [];
    const hit = list.find((run) => run.flowId === flowId && predicate(run));
    if (hit) return hit;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`no matching run for flow ${flowId} in ${timeoutMs} ms`);
}

// ---------------------------------------------------------------- API clients

/**
 * Creates an API client through maker/checker (API_CLIENT_CREATE); the MAKER
 * then reads the one-time secret. Returns {view, clientId, secret}. For an
 * API_KEY client the "secret" IS the whole key the system presents,
 * `mdk_<prefix>.<secret>` (ApiClientProvisioning), so use it as is.
 */
export async function createApiClient(maker, checker, request) {
  const name = request.name ?? uniqueName(`E2E client ${request.authMethod}`);
  const submitted = await maker.request('POST', `${ADMIN}/api-clients`, {
    json: { allowedCidrs: [], expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString(), ...request, name },
    expect: 202,
  });
  await approveChange(checker, submitted.body.pendingChangeId);
  const view = (await maker.get(`${ADMIN}/api-clients`)).find((c) => c.name === name);
  if (!view) throw new Error(`API client ${name} not listed after approval`);
  let secret = null;
  let clientId = view.clientId;
  if (request.authMethod !== 'MTLS') {
    const initial = await maker.get(`${ADMIN}/api-clients/${view.id}/initial-secret`);
    secret = initial.secret;
    clientId = initial.clientId ?? clientId;
  }
  return { view, clientId, secret };
}

/** OAuth2 client credentials (client_secret_basic) -> access token response. */
export async function clientToken(clientId, secret, { expect = 200 } = {}) {
  const res = await fetch(`${new ApiClient().baseURL}/api/integration/oauth/token`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(`${clientId}:${secret}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
    },
    body: 'grant_type=client_credentials',
  });
  const body = await res.json().catch(() => null);
  if (expect !== undefined && res.status !== expect) {
    throw new Error(`token endpoint -> ${res.status}: ${JSON.stringify(body)}`);
  }
  return { status: res.status, body };
}

/** An ApiClient for the Integration API v1 with a bearer token (OAuth2 clients). */
export function bearerClient(accessToken) {
  return new ApiClient({ token: accessToken, label: 'api-client' });
}

/** Calls the Integration API with `Authorization: ApiKey <prefix>.<secret>`. */
export function apiKeyClient(key) {
  const client = new ApiClient({ label: 'api-key-client' });
  const request = client.request.bind(client);
  client.request = (method, url, opts = {}) =>
    request(method, url, { ...opts, headers: { ...(opts.headers ?? {}), Authorization: `ApiKey ${key}` } });
  return client;
}

/**
 * POST /api/integration/v1/records; raw response. Every POST of the v1 API
 * needs an Idempotency-Key (400 IDEMPOTENCY_KEY_REQUIRED otherwise): a fresh
 * one is generated unless the caller passes its own, or `null` to send none.
 */
export async function postRecords(client, { templateId, records, idempotencyKey, externalBatchId }) {
  const key = idempotencyKey === undefined ? uniqueName('idem') : idempotencyKey;
  return client.request('POST', '/api/integration/v1/records', {
    json: { templateId, externalBatchId: externalBatchId ?? uniqueName('batch'), records },
    headers: key ? { 'Idempotency-Key': key } : {},
  });
}

/** Polls GET /api/integration/v1/jobs/{id} until it is done. */
export async function waitForClientJob(client, jobId, { timeoutMs = 120_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let job;
  while (Date.now() < deadline) {
    job = (await client.request('GET', `/api/integration/v1/jobs/${jobId}`, { expect: 200 })).body;
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status) || job.currentStep === 'VALIDATED') return job;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`integration job ${jobId} not done (last ${JSON.stringify(job)})`);
}

// --------------------------------------------------------------------- agents

/** Registers an agent (AGENT_REGISTER) and returns {view, enrolment:{token, expiresAt, dockerCommand}}. */
export async function registerAgent(maker, checker, name = uniqueName('e2e-agent')) {
  const submitted = await maker.request('POST', `${ADMIN}/agents`, { json: { name }, expect: 202 });
  await approveChange(checker, submitted.body.pendingChangeId);
  const view = (await maker.get(`${ADMIN}/agents`)).find((a) => a.name === name);
  const enrolment = await maker.get(`${ADMIN}/agents/${view.id}/enrolment`);
  return { view, enrolment };
}
