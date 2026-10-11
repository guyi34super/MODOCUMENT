import { api } from './client';

// Bank Integration Hub (phase 2, ws25/ws26). Contract: docs/phase2/contracts.md
// §3 (routes by owner) and §7.3 (REST shapes). Every maker/checker write
// answers 202 with the PendingChange DTO — detect it with isPendingChange()
// from ./changes. Disabling or revoking anything is always direct (200).
//
// Secrets are WRITE-ONLY: views carry `secretSet: true|false`, never a value.
// One-time secrets (API-client secret, agent enrolment token) are returned
// exactly once; callers keep them in component state only — never in
// localStorage/sessionStorage, never in a log.

const BASE = '/api/admin/integrations';
const id = (value) => encodeURIComponent(value);

function query(params = {}) {
  const parts = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// --- SE1: egress destinations (BankEgressRule) ------------------------------
// EgressRuleView = {id, host, cidr, port, status: PENDING|APPROVED|REVOKED,
//   requestedBy, approvedBy, createdAt} (+ reason, bankId/bankCode when present)

/** INTEGRATION_ADMIN: the bank's requested/approved destinations. */
export const listEgressRules = () => api.get(`${BASE}/egress-rules`);

/** INTEGRATION_ADMIN: ask the platform to allow a private destination (host OR cidr). */
export const requestEgressRule = ({ host, cidr, port, reason }) =>
  api.post(`${BASE}/egress-rules`, {
    host: host || null,
    cidr: cidr || null,
    port: port === '' || port === undefined || port === null ? null : Number(port),
    reason: reason || null,
  });

/** INTEGRATION_ADMIN: withdraw a destination — direct (the lower-risk direction). */
export const revokeEgressRule = (ruleId) => api.post(`${BASE}/egress-rules/${id(ruleId)}/revoke`);

/** PLATFORM_READ: every bank's egress requests (optionally by status). */
export const listPlatformEgressRules = (status) =>
  api.get(`/api/platform/egress-rules${query({ status })}`);

/** PLATFORM_BANKS: maker side of EGRESS_RULE_APPROVE → 202 PendingChange. */
export const approveEgressRule = (ruleId) =>
  api.post(`/api/platform/egress-rules/${id(ruleId)}/approve`);

// --- SE2: API clients (machine identity) -------------------------------------
// ApiClientView = {id, name, kind, authMethod, clientId, keyPrefix, certFingerprint,
//   certSubject, scopes, allowedCidrs, expiresAt, status: ACTIVE|REVOKED|EXPIRED,
//   lastUsedAt, createdBy, pendingChangeId}

export const listApiClients = () => api.get(`${BASE}/api-clients`);

/**
 * API_CLIENT_CREATE (202). `certificatePem` only for MTLS; `expiresAt` an
 * ISO-8601 instant or null.
 */
export const createApiClient = ({ name, authMethod, scopes, allowedCidrs, expiresAt, certificatePem }) =>
  api.post(`${BASE}/api-clients`, {
    name,
    authMethod,
    scopes,
    allowedCidrs,
    expiresAt: expiresAt || null,
    ...(authMethod === 'MTLS' ? { certificatePem } : {}),
  });

/** After approval, the maker only: `{clientId, secret}` — readable once, within 24 h. */
export const getApiClientInitialSecret = (clientId) =>
  api.get(`${BASE}/api-clients/${id(clientId)}/initial-secret`);

/** 200 `{clientId, secret}` — shown once; the old secret stays valid for the overlap. */
export const rotateApiClient = (clientId) => api.post(`${BASE}/api-clients/${id(clientId)}/rotate`);

/** Immediate. */
export const revokeApiClient = (clientId) => api.post(`${BASE}/api-clients/${id(clientId)}/revoke`);

// --- SE4: connections --------------------------------------------------------
// ConnectionView = {id, name, type, mode, agentId, config:{…non-secret…}, authType,
//   secretSet, secretRef, status: UNTESTED|OK|FAILED|DISABLED, lastTestAt,
//   lastTestResult, version, pendingChangeId, createdBy, updatedAt}
// ConnectionRequest = {name, type, mode, agentId?, config, authType,
//   secret?: {…SecretMaterial keys…}, secretRef?, expectedVersion?}

export const listConnections = () => api.get(`${BASE}/connections`);

export const getConnection = (connectionId) => api.get(`${BASE}/connections/${id(connectionId)}`);

/** INTEGRATION_CONNECTION_CHANGE → 202 PendingChange. */
export const createConnection = (request) => api.post(`${BASE}/connections`, request);

/** 202 PendingChange (host/credential change), or 200 ConnectionView for a rename only. */
export const updateConnection = (connectionId, request) =>
  api.put(`${BASE}/connections/${id(connectionId)}`, request);

export const deleteConnection = (connectionId) =>
  api.del(`${BASE}/connections/${id(connectionId)}`);

/**
 * DIRECT: 200 `{ok, message, latencyMillis}`. AGENT: 202 `{runId}` — the test
 * runs through the agent; follow it on the run page.
 */
export const testConnection = (connectionId) =>
  api.post(`${BASE}/connections/${id(connectionId)}/test`);

/** True for the AGENT-mode "test queued" answer (202 `{runId}`). */
export const isQueuedTest = (result) =>
  !!result && result.runId !== undefined && result.runId !== null && result.ok === undefined;

// --- SE4: flows ----------------------------------------------------------------
// FlowView = {id, name, direction, connectionId, connectionName, templateId,
//   templateName, trigger, cron, events, payloadKinds, format, sourceSpec, targetSpec,
//   fieldMapping:[{from,to}], maskPii, rowCap, enabled, pendingChangeId, version,
//   lastRun:{id, status, finishedAt}}

export const listFlows = () => api.get(`${BASE}/flows`);

export const getFlow = (flowId) => api.get(`${BASE}/flows/${id(flowId)}`);

/** Flows are created disabled: 200/201 FlowView. */
export const createFlow = (request) => api.post(`${BASE}/flows`, request);

/** 200 FlowView, or 202 PendingChange when the edit widens an enabled OUTBOUND flow. */
export const updateFlow = (flowId, request) => api.put(`${BASE}/flows/${id(flowId)}`, request);

export const deleteFlow = (flowId) => api.del(`${BASE}/flows/${id(flowId)}`);

/** OUTBOUND: 202 PendingChange (INTEGRATION_FLOW_ENABLE). INBOUND: 200 FlowView. */
export const enableFlow = (flowId) => api.post(`${BASE}/flows/${id(flowId)}/enable`);

/** Immediate (200). */
export const disableFlow = (flowId) => api.post(`${BASE}/flows/${id(flowId)}/disable`);

/** Dry run: `{columns, rows (≤20), suggestedMapping:[{from, to, score}]}`. Nothing is written. */
export const previewFlow = (flowId) => api.post(`${BASE}/flows/${id(flowId)}/preview`);

/** INTEGRATION_RUN: run now → the queued RunView. */
export const runFlow = (flowId) => api.post(`${BASE}/flows/${id(flowId)}/run`);

// --- SE4: runs -------------------------------------------------------------------
// RunView = {id, flowId, flowName, direction, status: RunStatus, trigger, scheduledFor,
//   startedAt, finishedAt, attempt, recordsIn, recordsOut, ingestionJobId, error}
// detail adds events:[{at, level, message}], deliveries:[{id, status, attempts,
//   lastError, nextAttemptAt}]

/** INTEGRATION_RUN. Optional filters: `{flowId, status}`. */
export const listRuns = (filters = {}) => api.get(`${BASE}/runs${query(filters)}`);

export const getRun = (runId) => api.get(`${BASE}/runs/${id(runId)}`);

/** INTEGRATION_RUN: replays dead deliveries or re-queues a failed run. */
export const retryRun = (runId) => api.post(`${BASE}/runs/${id(runId)}/retry`);

// --- SE5: connector agents ---------------------------------------------------------
// AgentView = {id, name, status: PENDING_ENROLMENT|ONLINE|OFFLINE|REVOKED, version,
//   latestVersion, updateAvailable, lastHeartbeatAt, apiClientId, connections:[ids]}

export const listAgents = () => api.get(`${BASE}/agents`);

/** AGENT_REGISTER → 202 PendingChange. */
export const registerAgent = (name) => api.post(`${BASE}/agents`, { name });

/** After approval, the maker only: `{token, expiresAt, dockerCommand}` — readable once (15 min). */
export const getAgentEnrolment = (agentId) => api.get(`${BASE}/agents/${id(agentId)}/enrolment`);
