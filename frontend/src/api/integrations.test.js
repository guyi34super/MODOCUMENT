import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import * as integrations from './integrations';

vi.mock('./client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), del: vi.fn() },
}));

const BASE = '/api/admin/integrations';

describe('api/integrations (contracts §3 / §7.3)', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset().mockResolvedValue({}));
  });

  it('routes the SE1 egress calls', async () => {
    await integrations.listEgressRules();
    expect(api.get).toHaveBeenCalledWith(`${BASE}/egress-rules`);

    await integrations.requestEgressRule({ host: 'db.internal', port: '5432', reason: 'Core DB' });
    expect(api.post).toHaveBeenCalledWith(`${BASE}/egress-rules`, {
      host: 'db.internal',
      cidr: null,
      port: 5432,
      reason: 'Core DB',
    });

    await integrations.revokeEgressRule(4);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/egress-rules/4/revoke`);

    await integrations.listPlatformEgressRules('PENDING');
    expect(api.get).toHaveBeenCalledWith('/api/platform/egress-rules?status=PENDING');

    await integrations.approveEgressRule(4);
    expect(api.post).toHaveBeenCalledWith('/api/platform/egress-rules/4/approve');
  });

  it('routes the SE2 API-client calls and sends the PEM only for mTLS', async () => {
    await integrations.createApiClient({
      name: 'Loans',
      authMethod: 'API_KEY',
      scopes: ['INTEGRATION_INGEST'],
      allowedCidrs: [],
      expiresAt: '',
      certificatePem: 'ignored',
    });
    expect(api.post).toHaveBeenCalledWith(`${BASE}/api-clients`, {
      name: 'Loans',
      authMethod: 'API_KEY',
      scopes: ['INTEGRATION_INGEST'],
      allowedCidrs: [],
      expiresAt: null,
    });

    await integrations.createApiClient({
      name: 'Core',
      authMethod: 'MTLS',
      scopes: [],
      allowedCidrs: [],
      certificatePem: '-----BEGIN CERTIFICATE-----',
    });
    expect(api.post).toHaveBeenLastCalledWith(
      `${BASE}/api-clients`,
      expect.objectContaining({ certificatePem: '-----BEGIN CERTIFICATE-----' })
    );

    await integrations.getApiClientInitialSecret(3);
    expect(api.get).toHaveBeenCalledWith(`${BASE}/api-clients/3/initial-secret`);
    await integrations.rotateApiClient(3);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/api-clients/3/rotate`);
    await integrations.revokeApiClient(3);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/api-clients/3/revoke`);
  });

  it('routes the SE4 connection, flow and run calls', async () => {
    await integrations.createConnection({ name: 'x' });
    expect(api.post).toHaveBeenCalledWith(`${BASE}/connections`, { name: 'x' });
    await integrations.updateConnection(7, { name: 'y' });
    expect(api.put).toHaveBeenCalledWith(`${BASE}/connections/7`, { name: 'y' });
    await integrations.deleteConnection(7);
    expect(api.del).toHaveBeenCalledWith(`${BASE}/connections/7`);
    await integrations.testConnection(7);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/connections/7/test`);

    await integrations.enableFlow(2);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/flows/2/enable`);
    await integrations.disableFlow(2);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/flows/2/disable`);
    await integrations.previewFlow(2);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/flows/2/preview`);
    await integrations.runFlow(2);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/flows/2/run`);

    await integrations.listRuns({ status: 'FAILED', flowId: undefined });
    expect(api.get).toHaveBeenCalledWith(`${BASE}/runs?status=FAILED`);
    await integrations.listRuns();
    expect(api.get).toHaveBeenCalledWith(`${BASE}/runs`);
    await integrations.retryRun(9);
    expect(api.post).toHaveBeenCalledWith(`${BASE}/runs/9/retry`);
  });

  it('routes the SE5 agent calls', async () => {
    await integrations.registerAgent('dc1');
    expect(api.post).toHaveBeenCalledWith(`${BASE}/agents`, { name: 'dc1' });
    await integrations.getAgentEnrolment(5);
    expect(api.get).toHaveBeenCalledWith(`${BASE}/agents/5/enrolment`);
  });

  it('tells a queued agent test from a direct result', () => {
    expect(integrations.isQueuedTest({ runId: 4 })).toBe(true);
    expect(integrations.isQueuedTest({ ok: true, message: 'fine', latencyMillis: 3 })).toBe(false);
    expect(integrations.isQueuedTest(null)).toBe(false);
  });
});
