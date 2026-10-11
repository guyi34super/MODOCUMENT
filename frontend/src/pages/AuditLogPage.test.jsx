import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditLogPage, { diffValues, entityLink, toApiFilters } from './AuditLogPage';
import { ADMIN, PLATFORM, READER, renderWithProviders } from '../test/renderWithProviders';
import { api, downloadFile } from '../api/client';
import { resetAuditActionsCache } from '../api/audit';

// The real api/audit.js builds every URL; only the transport is mocked, so
// these tests pin the query strings sent to SE2's endpoints (contracts §3).
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal()),
  api: { get: vi.fn() },
  downloadFile: vi.fn(),
}));

const APPROVER = { userId: 4, email: 'checker@bank.example', role: 'APPROVER' };

const EVENT = {
  id: 501,
  occurredAt: '2026-10-10T09:15:00Z',
  action: 'USER_ROLE_CHANGED',
  outcome: 'SUCCESS',
  actorUserId: 1,
  actorEmail: 'admin@bank.example',
  actorRole: 'ADMIN',
  entityType: 'AppUser',
  entityId: '7',
  ip: '10.0.0.8',
  requestId: 'req-abc',
  oldValues: { role: 'READ_WRITE', displayName: 'Worker', mfa: true },
  newValues: { role: 'READ_ONLY', displayName: 'Worker', team: 'Ops' },
};
const DENIED = {
  ...EVENT,
  id: 502,
  action: 'ACCESS_DENIED',
  outcome: 'DENIED',
  entityType: 'IngestionJob',
  entityId: '40',
  oldValues: null,
  newValues: null,
};

const ACTIONS = {
  actions: [
    { name: 'USER_ROLE_CHANGED', category: 'Users' },
    { name: 'USER_DISABLED', category: 'Users' },
    { name: 'ACCESS_DENIED', category: 'Security' },
  ],
};

function mockServer({ pages = { '': { events: [EVENT, DENIED], nextCursor: null } }, counts } = {}) {
  api.get.mockImplementation((path) => {
    const url = new URL(path, 'http://x');
    if (url.pathname === '/api/admin/audit/actions') return Promise.resolve(ACTIONS);
    if (url.pathname === '/api/platform/audit/counts') {
      return Promise.resolve(counts || { banks: [] });
    }
    if (url.pathname === '/api/admin/audit' || url.pathname === '/api/platform/audit') {
      const page = pages[url.searchParams.get('cursor') || ''];
      return page ? Promise.resolve(page) : Promise.reject(new Error(`no page for ${path}`));
    }
    return Promise.reject(new Error(`unexpected ${path}`));
  });
}

const listCalls = () =>
  api.get.mock.calls.map(([p]) => p).filter((p) => /^\/api\/(admin|platform)\/audit(\?|$)/.test(p));

function renderPage(user = ADMIN, mode = 'bank') {
  const path = mode === 'platform' ? '/platform/audit' : '/admin/audit';
  return renderWithProviders(<AuditLogPage mode={mode} />, { user, path });
}

describe('AuditLogPage', () => {
  beforeEach(() => {
    resetAuditActionsCache();
    api.get.mockReset();
    downloadFile.mockReset().mockResolvedValue(undefined);
    mockServer();
  });

  it('lists the newest events with entity links and offers the action catalogue', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(within(table).getByText('USER_ROLE_CHANGED')).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: 'IngestionJob #40' })).toHaveAttribute(
      'href',
      '/ingestion/40'
    );
    expect(within(table).getByRole('link', { name: 'AppUser #7' })).toHaveAttribute(
      'href',
      '/admin/users'
    );
    expect(listCalls()).toEqual(['/api/admin/audit?limit=50']);

    const picker = screen.getByLabelText('Actions');
    expect(await within(picker).findByRole('option', { name: 'ACCESS_DENIED' })).toBeInTheDocument();
    expect(within(picker).getByRole('group', { name: 'Users' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('sends every filter, actions repeated and the dates as instants', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Audit events' });
    await within(screen.getByLabelText('Actions')).findByRole('option', { name: 'USER_DISABLED' });

    await userEvent.type(screen.getByLabelText('Actor (email or user id)'), 'admin@bank.example');
    await userEvent.selectOptions(screen.getByLabelText('Actions'), [
      'USER_ROLE_CHANGED',
      'USER_DISABLED',
    ]);
    await userEvent.type(screen.getByLabelText('Entity type'), 'AppUser');
    await userEvent.type(screen.getByLabelText('Entity id'), '7');
    await userEvent.selectOptions(screen.getByLabelText('Outcome'), 'DENIED');
    await userEvent.type(screen.getByLabelText('From'), '2026-10-01');
    await userEvent.type(screen.getByLabelText('To'), '2026-10-10');
    await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));

    await waitFor(() => expect(listCalls()).toHaveLength(2));
    const url = new URL(listCalls()[1], 'http://x');
    expect(url.pathname).toBe('/api/admin/audit');
    expect(url.searchParams.get('actor')).toBe('admin@bank.example');
    expect(url.searchParams.getAll('action')).toEqual(['USER_ROLE_CHANGED', 'USER_DISABLED']);
    expect(url.searchParams.get('entityType')).toBe('AppUser');
    expect(url.searchParams.get('entityId')).toBe('7');
    expect(url.searchParams.get('outcome')).toBe('DENIED');
    expect(url.searchParams.get('from')).toBe(new Date('2026-10-01T00:00:00').toISOString());
    expect(url.searchParams.get('to')).toBe(new Date('2026-10-10T23:59:59.999').toISOString());
    expect(url.searchParams.get('limit')).toBe('50');

    // Clearing goes back to the unfiltered list.
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(listCalls()).toHaveLength(3));
    expect(listCalls()[2]).toBe('/api/admin/audit?limit=50');
    expect(screen.getByLabelText('Actor (email or user id)')).toHaveValue('');
  });

  it('pages with the keyset cursor: "Load more" appends until there is no next cursor', async () => {
    mockServer({
      pages: {
        '': { events: [EVENT], nextCursor: 'c-1' },
        'c-1': { events: [DENIED], nextCursor: null },
      },
    });
    renderPage();

    await screen.findByText('1 event shown — more are available');
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('2 events shown')).toBeInTheDocument();
    expect(listCalls()[1]).toBe('/api/admin/audit?cursor=c-1&limit=50');
    const table = screen.getByRole('table', { name: 'Audit events' });
    expect(within(table).getByText('USER_ROLE_CHANGED')).toBeInTheDocument();
    expect(within(table).getByText('ACCESS_DENIED')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('exports the filtered events as CSV (no cursor, no limit)', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Audit events' });

    await userEvent.selectOptions(screen.getByLabelText('Outcome'), 'DENIED');
    await userEvent.type(screen.getByLabelText('Entity type'), 'IngestionJob');
    await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(listCalls()).toHaveLength(2));

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() =>
      expect(downloadFile).toHaveBeenCalledWith(
        '/api/admin/audit/export?entityType=IngestionJob&outcome=DENIED',
        'audit-log.csv'
      )
    );
    expect(await screen.findByText('Audit export downloaded')).toBeInTheDocument();
  });

  it('shows old → new values in the detail dialog', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Audit events' });

    await userEvent.click(screen.getByRole('button', { name: 'Details of event 501' }));
    const dialog = screen.getByRole('dialog', { name: 'Audit event #501' });
    const diff = within(dialog).getByRole('table', { name: 'Old and new values' });
    const row = (field) => within(diff).getByRole('row', { name: new RegExp(`^${field}\\b`) });

    expect(within(row('role')).getByText('READ_WRITE')).toBeInTheDocument();
    expect(within(row('role')).getByText('READ_ONLY')).toBeInTheDocument();
    expect(within(row('role')).getByText('Changed')).toBeInTheDocument();
    expect(within(row('team')).getByText('Added')).toBeInTheDocument();
    expect(within(row('mfa')).getByText('Removed')).toBeInTheDocument();
    expect(within(row('displayName')).getByText('Unchanged')).toBeInTheDocument();
    expect(within(dialog).getByText('10.0.0.8')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Open AppUser' })).toHaveAttribute(
      'href',
      '/admin/users'
    );

    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('says so when an event recorded no values', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Audit events' });
    await userEvent.click(screen.getByRole('button', { name: 'Details of event 502' }));
    expect(
      within(screen.getByRole('dialog')).getByText('No old or new values were recorded for this event.')
    ).toBeInTheDocument();
  });

  it('is open to APPROVERs (read-only role)', async () => {
    renderPage(APPROVER);
    expect(await screen.findByRole('table', { name: 'Audit events' })).toBeInTheDocument();
  });

  it('is not offered to roles without audit:read, and asks the server nothing', async () => {
    renderPage(READER);
    expect(await screen.findByText('Not available')).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });

  it('keeps the platform variant to the PLATFORM_ADMIN', async () => {
    renderPage(ADMIN, 'platform');
    expect(await screen.findByText('Not available')).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalled();
  });

  it('platform variant: the platform bank’s events plus per-bank counts for the period', async () => {
    mockServer({
      counts: {
        banks: [
          { bankId: 2, code: 'ACME', total: 120, denied: 4, failed: 1 },
          { bankId: 3, code: 'BETA', total: 8, denied: 0, failed: 0 },
        ],
      },
    });
    renderPage(PLATFORM, 'platform');

    const counts = await screen.findByRole('table', { name: 'Audit events per bank' });
    expect(within(counts).getByRole('link', { name: 'ACME' })).toHaveAttribute('href', '/platform/banks/2');
    expect(within(counts).getByText('120')).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Audit events' })).toBeInTheDocument();
    expect(listCalls()).toEqual(['/api/platform/audit?limit=50']);
    expect(api.get).toHaveBeenCalledWith('/api/platform/audit/counts');

    await userEvent.type(screen.getByLabelText('From'), '2026-10-01');
    await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    const from = encodeURIComponent(new Date('2026-10-01T00:00:00').toISOString());
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(`/api/platform/audit/counts?from=${from}`)
    );
  });

  it('surfaces a failing list as an error, not a spinner', async () => {
    api.get.mockImplementation((path) =>
      path.startsWith('/api/admin/audit/actions')
        ? Promise.resolve(ACTIONS)
        : Promise.reject(new Error('audit store unavailable'))
    );
    renderPage();
    expect(await screen.findByText('audit store unavailable')).toBeInTheDocument();
    expect(screen.getByText('No audit events match these filters.')).toBeInTheDocument();
  });
});

describe('audit helpers', () => {
  it('diffValues compares fields, parses JSON text and wraps bare values', () => {
    expect(diffValues('{"a":1,"b":2}', { a: 1, b: 3, c: 4 })).toEqual([
      { field: 'a', before: 1, after: 1, change: 'Unchanged' },
      { field: 'b', before: 2, after: 3, change: 'Changed' },
      { field: 'c', before: undefined, after: 4, change: 'Added' },
    ]);
    expect(diffValues('old', 'new')).toEqual([
      { field: 'value', before: 'old', after: 'new', change: 'Changed' },
    ]);
    expect(diffValues(null, undefined)).toEqual([]);
  });

  it('entityLink opens the entity when the app has a page for it', () => {
    expect(entityLink('Template', 12)).toBe('/templates/12');
    expect(entityLink('IntegrationRun', '5')).toBe('/admin/integrations/runs/5');
    expect(entityLink('PendingChange', 3)).toBe('/admin/changes');
    expect(entityLink('Template', null)).toBeNull();
    expect(entityLink('RefreshTokenFamily', 1)).toBeNull();
  });

  it('toApiFilters leaves empty dates out', () => {
    expect(toApiFilters({ actor: 'x', from: '', to: '' })).toEqual({
      actor: 'x',
      from: undefined,
      to: undefined,
    });
  });
});
