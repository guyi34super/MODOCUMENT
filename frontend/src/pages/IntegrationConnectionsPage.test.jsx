import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationConnectionsPage from './IntegrationConnectionsPage';
import { WRITER, pendingChange, renderWithProviders } from '../test/renderWithProviders';
import {
  createConnection,
  deleteConnection,
  listAgents,
  listConnections,
  testConnection,
  updateConnection,
} from '../api/integrations';

vi.mock('../api/integrations', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    isQueuedTest: actual.isQueuedTest,
    listConnections: vi.fn(),
    listAgents: vi.fn(),
    createConnection: vi.fn(),
    updateConnection: vi.fn(),
    deleteConnection: vi.fn(),
    testConnection: vi.fn(),
  };
});

const CORE_DB = {
  id: 7,
  name: 'Core DB',
  type: 'DATABASE',
  mode: 'DIRECT',
  agentId: null,
  config: { vendor: 'POSTGRESQL', host: 'db.bank.example', port: '5432', database: 'core', sslmode: 'require', username: 'svc' },
  authType: 'BASIC',
  secretSet: true,
  secretRef: null,
  status: 'OK',
  lastTestAt: '2026-10-09T08:00:00Z',
  version: 3,
  pendingChangeId: null,
};
const FILES = {
  id: 8,
  name: 'Statements SFTP',
  type: 'SFTP',
  mode: 'AGENT',
  agentId: 5,
  config: { host: 'sftp.internal', port: '22', folder: '/out', hostKeyFingerprint: 'SHA256:x' },
  authType: 'BASIC',
  secretSet: false,
  secretRef: 'SFTP_PASSWORD',
  status: 'UNTESTED',
  version: 1,
  pendingChangeId: 44,
};
const AGENT = { id: 5, name: 'dc1-agent', status: 'ONLINE' };
const FINGERPRINT = 'SHA256:' + 'A'.repeat(43);

const renderPage = (user) =>
  renderWithProviders(<IntegrationConnectionsPage />, {
    user,
    path: '/admin/integrations/connections',
  });

const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));

describe('IntegrationConnectionsPage', () => {
  beforeEach(() => {
    listConnections.mockReset().mockResolvedValue([CORE_DB, FILES]);
    listAgents.mockReset().mockResolvedValue([AGENT]);
    createConnection.mockReset().mockResolvedValue(pendingChange(31, 'INTEGRATION_CONNECTION_CHANGE'));
    updateConnection.mockReset().mockResolvedValue(pendingChange(32, 'INTEGRATION_CONNECTION_CHANGE'));
    deleteConnection.mockReset().mockResolvedValue(null);
    testConnection.mockReset();
  });

  it('lists connections without ever showing a secret', async () => {
    renderPage();

    expect(await screen.findByText('Core DB')).toBeInTheDocument();
    expect(screen.getByText('Secret set ✓')).toBeInTheDocument();
    expect(screen.getByText('Agent secret "SFTP_PASSWORD"')).toBeInTheDocument();
    expect(screen.getByText('Agent dc1-agent')).toBeInTheDocument();
    expect(screen.getByText('POSTGRESQL · db.bank.example:5432/core')).toBeInTheDocument();
    // FILES has a change awaiting the checker.
    expect(screen.getByText('Pending approval')).toBeInTheDocument();
  });

  it('shows non-administrators an explanation instead of calling the API', async () => {
    renderPage(WRITER);

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(listConnections).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'New connection' })).not.toBeInTheDocument();
  });

  it('can be completed with the keyboard alone', async () => {
    const user = userEvent.setup();
    renderPage();
    const open = await screen.findByRole('button', { name: 'New connection' });
    open.focus();
    await user.keyboard('{Enter}');
    const dialog = screen.getByRole('dialog', { name: 'New connection' });
    expect(within(dialog).getByRole('heading', { name: 'Type' })).toHaveFocus();
    // The step list says which step is current in words, not only in colour.
    expect(within(dialog).getByRole('list', { name: 'Wizard steps' })).toHaveTextContent(
      '1. Type (current step)'
    );

    // Arrow keys move through the type radios; Tab reaches Next; Enter advances.
    await user.tab();
    expect(within(dialog).getByRole('radio', { name: /^REST API/ })).toHaveFocus();
    await user.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}');
    expect(within(dialog).getByRole('radio', { name: /^Webhook/ })).toBeChecked();
    await user.tab(); // Cancel
    await user.tab(); // Next
    expect(within(dialog).getByRole('button', { name: 'Next' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(within(dialog).getByRole('heading', { name: 'Mode' })).toHaveFocus();
    expect(within(dialog).getByRole('list', { name: 'Wizard steps' })).toHaveTextContent(
      '1. Type (done)'
    );

    // Mode keeps Direct; Enter in a text field submits the step (implicit submission).
    await user.tab();
    await user.keyboard('{Enter}');
    await waitFor(() =>
      expect(within(dialog).getByRole('heading', { name: 'Details' })).toHaveFocus()
    );
    await user.click(within(dialog).getByLabelText('Connection name'));
    await user.keyboard('Bank hooks');
    await user.click(within(dialog).getByLabelText(/Webhook URL/));
    await user.keyboard('https://hooks.bank.example/modoc{Enter}');
    expect(within(dialog).getByRole('heading', { name: 'Credentials' })).toHaveFocus();
    await user.click(within(dialog).getByLabelText('Signing secret (HMAC)'));
    await user.keyboard('hmac-secret{Enter}');
    expect(within(dialog).getByRole('heading', { name: 'Review & test' })).toHaveFocus();

    // Escape closes the dialog and focus returns to the button that opened it.
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(open).toHaveFocus();
  });

  it('walks the wizard for a direct database connection and sends it for approval', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New connection' }));
    const dialog = screen.getByRole('dialog', { name: 'New connection' });
    expect(within(dialog).getByRole('heading', { name: 'Type' })).toHaveFocus();

    // A step cannot be skipped without its answer.
    await next();
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Choose a connection type.');

    await userEvent.click(within(dialog).getByRole('radio', { name: /^Database/ }));
    await next();
    expect(within(dialog).getByRole('heading', { name: 'Mode' })).toBeInTheDocument();
    expect(within(dialog).getByRole('radio', { name: /^Direct/ })).toBeChecked();
    await next();

    await userEvent.type(within(dialog).getByLabelText('Connection name'), 'Core banking');
    await userEvent.selectOptions(within(dialog).getByLabelText('Database vendor'), 'SQLSERVER');
    // The vendor's default port is filled in.
    expect(within(dialog).getByLabelText('Port')).toHaveValue('1433');
    await userEvent.type(within(dialog).getByLabelText('Host'), 'sql.bank.example');
    await userEvent.type(within(dialog).getByLabelText('Database'), 'loans');
    await userEvent.type(within(dialog).getByLabelText('Username'), 'svc_modoc');
    await next();

    expect(within(dialog).getByRole('heading', { name: 'Credentials' })).toBeInTheDocument();
    const password = within(dialog).getByLabelText('Database password');
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('');
    await next();
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Enter every credential field.');
    await userEvent.type(password, 's3cret!');
    await next();

    expect(within(dialog).getByRole('heading', { name: 'Review & test' })).toBeInTheDocument();
    expect(within(dialog).getByText('New secret (write-only)')).toBeInTheDocument();
    expect(within(dialog).queryByText('s3cret!')).not.toBeInTheDocument();
    // A new connection is tested after approval, from the list.
    expect(within(dialog).queryByRole('button', { name: 'Test connection' })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(createConnection).toHaveBeenCalledWith({
        name: 'Core banking',
        type: 'DATABASE',
        mode: 'DIRECT',
        agentId: null,
        config: {
          vendor: 'SQLSERVER',
          host: 'sql.bank.example',
          port: '1433',
          database: 'loans',
          sslmode: 'require',
          username: 'svc_modoc',
        },
        authType: 'BASIC',
        secret: { password: 's3cret!' },
      })
    );
    expect(await screen.findByText('Sent for approval (change #31)')).toBeInTheDocument();
    expect(screen.getByText(/Creating connection "Core banking" is change #31/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(listConnections).toHaveBeenCalledTimes(2);
  });

  it('requires a pinned SHA256 host key for SFTP', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New connection' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('radio', { name: /^SFTP/ }));
    await next();
    await next();
    await userEvent.type(within(dialog).getByLabelText('Connection name'), 'Drop box');
    await userEvent.type(within(dialog).getByLabelText('Host'), 'sftp.bank.example');
    await userEvent.type(within(dialog).getByLabelText('Port'), '22');
    await userEvent.type(within(dialog).getByLabelText(/^Folder/), '/outbound');
    await userEvent.type(within(dialog).getByLabelText(/Pinned host-key fingerprint/), 'ssh-rsa AAAA');
    await next();
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Pin the server host key');

    await userEvent.clear(within(dialog).getByLabelText(/Pinned host-key fingerprint/));
    await userEvent.type(within(dialog).getByLabelText(/Pinned host-key fingerprint/), FINGERPRINT);
    await next();
    expect(within(dialog).getByLabelText('SFTP password')).toBeInTheDocument();
  });

  it('stores only a secretRef name in AGENT mode', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New connection' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('radio', { name: /^Webhook/ }));
    await next();
    await userEvent.click(within(dialog).getByRole('radio', { name: /^Agent/ }));
    await next();
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Choose the agent');
    await userEvent.selectOptions(within(dialog).getByLabelText('Agent'), '5');
    await next();
    await userEvent.type(within(dialog).getByLabelText('Connection name'), 'Core hooks');
    await userEvent.type(within(dialog).getByLabelText(/Webhook URL/), 'https://hooks.bank.example/in');
    await next();

    // No secret input at all: the agent holds the secret.
    expect(within(dialog).queryByLabelText(/Signing secret/)).not.toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText(/Secret reference/), 'HOOK_HMAC');
    await next();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(createConnection).toHaveBeenCalled());
    const request = createConnection.mock.calls[0][0];
    expect(request).toMatchObject({
      type: 'WEBHOOK',
      mode: 'AGENT',
      agentId: 5,
      authType: 'HMAC_SIGNATURE',
      secretRef: 'HOOK_HMAC',
      config: { url: 'https://hooks.bank.example/in' },
    });
    expect(request).not.toHaveProperty('secret');
  });

  it('never prefills a stored secret: "Secret set ✓ — Replace" and only a replaced secret is sent', async () => {
    testConnection.mockResolvedValue({ ok: true, message: 'Connected', latencyMillis: 42 });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Core DB' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit connection "Core DB"' });
    // Editing starts at the details, prefilled with the non-secret config.
    expect(within(dialog).getByLabelText('Host')).toHaveValue('db.bank.example');
    await next();

    expect(within(dialog).getByText('Secret set ✓')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Database password')).not.toBeInTheDocument();
    await next();
    expect(within(dialog).getByText('Secret set ✓ (unchanged)')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Test connection' }));
    expect(await within(dialog).findByText('Connection OK')).toBeInTheDocument();
    expect(within(dialog).getByText(/Connected \(42 ms\)/)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateConnection).toHaveBeenCalled());
    const [id, kept] = updateConnection.mock.calls[0];
    expect(id).toBe(7);
    expect(kept).not.toHaveProperty('secret');
    expect(kept.expectedVersion).toBe(3);

    // Replace: an empty write-only field; the new value is sent.
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Core DB' }));
    const again = screen.getByRole('dialog');
    await next();
    await userEvent.click(within(again).getByRole('button', { name: 'Replace' }));
    const password = within(again).getByLabelText('Database password');
    expect(password).toHaveValue('');
    await userEvent.type(password, 'rotated-pw');
    await next();
    await userEvent.click(within(again).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateConnection).toHaveBeenCalledTimes(2));
    expect(updateConnection.mock.calls[1][1].secret).toEqual({ password: 'rotated-pw' });
  });

  it('wipes a typed secret when the wizard is closed', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New connection' }));
    let dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('radio', { name: /^REST API/ }));
    await next();
    await next();
    await userEvent.type(within(dialog).getByLabelText('Connection name'), 'Loans API');
    await userEvent.type(within(dialog).getByLabelText(/Base URL/), 'https://api.bank.example');
    await userEvent.selectOptions(within(dialog).getByLabelText('Outbound authentication'), 'BEARER');
    await next();
    await userEvent.type(within(dialog).getByLabelText('Bearer token'), 'tok-123');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await userEvent.click(screen.getByRole('button', { name: 'New connection' }));
    dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Type' })).toBeInTheDocument();
    expect(dialog.textContent).not.toContain('tok-123');
    expect(within(dialog).getByRole('radio', { name: /^REST API/ })).not.toBeChecked();
    expect(createConnection).not.toHaveBeenCalled();
  });

  it('links an agent connection test to its run', async () => {
    testConnection.mockResolvedValue({ runId: 77 });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Test Statements SFTP' }));

    const link = await screen.findByRole('link', { name: 'Follow run #77' });
    expect(link).toHaveAttribute('href', '/admin/integrations/runs/77');
    expect(testConnection).toHaveBeenCalledWith(8);
  });

  it('deletes a connection after confirmation', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Delete Core DB' }));
    const confirm = screen.getByRole('dialog', { name: 'Delete connection "Core DB"' });
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteConnection).toHaveBeenCalledWith(7));
    expect(await screen.findByText('Connection "Core DB" deleted')).toBeInTheDocument();
  });
});
