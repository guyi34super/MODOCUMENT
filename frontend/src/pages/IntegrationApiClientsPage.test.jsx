import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationApiClientsPage from './IntegrationApiClientsPage';
import { pendingChange, renderWithProviders } from '../test/renderWithProviders';
import {
  createApiClient,
  getApiClientInitialSecret,
  listApiClients,
  revokeApiClient,
  rotateApiClient,
} from '../api/integrations';

vi.mock('../api/integrations', () => ({
  listApiClients: vi.fn(),
  createApiClient: vi.fn(),
  getApiClientInitialSecret: vi.fn(),
  rotateApiClient: vi.fn(),
  revokeApiClient: vi.fn(),
}));

const LOANS = {
  id: 3,
  name: 'Loans system',
  kind: 'SYSTEM',
  authMethod: 'OAUTH2_CLIENT_CREDENTIALS',
  clientId: 'cl_loans',
  scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'],
  allowedCidrs: ['203.0.113.0/24'],
  expiresAt: null,
  status: 'ACTIVE',
  lastUsedAt: null,
  pendingChangeId: null,
};
const CORE = {
  ...LOANS,
  id: 4,
  name: 'Core mTLS',
  authMethod: 'MTLS',
  clientId: null,
  certSubject: 'CN=core.bank.example',
  allowedCidrs: [],
};

const SECRET = 'mdc_s3cr3t_value_shown_once';

describe('IntegrationApiClientsPage', () => {
  beforeEach(() => {
    listApiClients.mockReset().mockResolvedValue([LOANS, CORE]);
    createApiClient.mockReset().mockResolvedValue(pendingChange(60, 'API_CLIENT_CREATE'));
    getApiClientInitialSecret.mockReset().mockResolvedValue({ clientId: 'cl_loans', secret: SECRET });
    rotateApiClient.mockReset().mockResolvedValue({ clientId: 'cl_loans', secret: 'rotated-secret' });
    revokeApiClient.mockReset().mockResolvedValue({ ...LOANS, status: 'REVOKED' });
  });

  const renderPage = () =>
    renderWithProviders(<IntegrationApiClientsPage />, { path: '/admin/integrations/api-clients' });

  it('lists clients by auth method and identifier', async () => {
    renderPage();
    expect(await screen.findByText('Loans system')).toBeInTheDocument();
    expect(screen.getByText('cl_loans')).toBeInTheDocument();
    expect(screen.getByText('CN=core.bank.example')).toBeInTheDocument();
    expect(screen.getAllByText('Ingest, Results')).toHaveLength(2);
    expect(screen.getByText('203.0.113.0/24')).toBeInTheDocument();
    // mTLS has no secret to reveal or rotate.
    expect(screen.queryByRole('button', { name: 'Rotate Core mTLS' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revoke Core mTLS' })).toBeInTheDocument();
  });

  it('creates a client for approval (202 → "Sent for approval")', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New API client' }));
    const dialog = screen.getByRole('dialog', { name: 'New API client' });

    await userEvent.type(within(dialog).getByLabelText('Name'), 'Treasury');
    await userEvent.click(within(dialog).getByRole('radio', { name: /^API key/ }));
    await userEvent.click(within(dialog).getByRole('checkbox', { name: /^Results/ }));
    await userEvent.click(within(dialog).getByRole('checkbox', { name: /^Events/ }));
    await userEvent.type(within(dialog).getByLabelText(/IP allowlist/), 'not-an-ip');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('"not-an-ip" is not an IP');

    await userEvent.clear(within(dialog).getByLabelText(/IP allowlist/));
    await userEvent.type(within(dialog).getByLabelText(/IP allowlist/), '10.0.0.0/8{enter}192.0.2.7');
    await userEvent.type(within(dialog).getByLabelText(/Expires on/), '2027-03-31');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    await waitFor(() =>
      expect(createApiClient).toHaveBeenCalledWith({
        name: 'Treasury',
        authMethod: 'API_KEY',
        scopes: ['INTEGRATION_INGEST', 'INTEGRATION_EVENTS'],
        allowedCidrs: ['10.0.0.0/8', '192.0.2.7'],
        expiresAt: '2027-03-31T23:59:59Z',
        certificatePem: '',
      })
    );
    expect(await screen.findByText('Sent for approval (change #60)')).toBeInTheDocument();
    expect(screen.getByText(/Creating API client "Treasury" is change #60/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('requires a certificate PEM for mTLS', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New API client' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Core');
    await userEvent.click(within(dialog).getByRole('radio', { name: /^mTLS/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Paste the client certificate');
    expect(createApiClient).not.toHaveBeenCalled();
  });

  it('reveals the initial secret once and forgets it when closed', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Reveal secret (once) for Loans system' })
    );

    const dialog = await screen.findByRole('dialog', { name: 'Secret for "Loans system"' });
    expect(getApiClientInitialSecret).toHaveBeenCalledWith(3);
    expect(within(dialog).getByText("You won't see this again")).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Client secret')).toHaveValue(SECRET);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Copy client secret' }));
    expect(writeText).toHaveBeenCalledWith(SECRET);
    // Never persisted in browser storage.
    expect(JSON.stringify({ ...localStorage })).not.toContain(SECRET);
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(SECRET);

    await userEvent.click(within(dialog).getByRole('button', { name: 'I have stored it' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.body.textContent).not.toContain(SECRET);
  });

  it('explains when the one-time secret is gone', async () => {
    getApiClientInitialSecret.mockRejectedValue(Object.assign(new Error('Not found'), { status: 404 }));
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Reveal secret (once) for Loans system' })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer available');
  });

  it('rotates (new secret shown once) and revokes after confirmation', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Rotate Loans system' }));
    const dialog = await screen.findByRole('dialog', { name: 'New secret for "Loans system"' });
    expect(within(dialog).getByLabelText('Client secret')).toHaveValue('rotated-secret');
    expect(within(dialog).getByText(/previous secret keeps working/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'I have stored it' }));

    await userEvent.click(screen.getByRole('button', { name: 'Revoke Loans system' }));
    const confirm = screen.getByRole('dialog', { name: 'Revoke "Loans system"' });
    // Danger dialogs start on Cancel.
    expect(within(confirm).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(within(confirm).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(revokeApiClient).toHaveBeenCalledWith(3));
    expect(await screen.findByText('API client "Loans system" revoked')).toBeInTheDocument();
  });
});
