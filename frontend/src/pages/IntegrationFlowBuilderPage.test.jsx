import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationFlowBuilderPage, { cronFor, presetOf } from './IntegrationFlowBuilderPage';
import { pendingChange, renderWithProviders } from '../test/renderWithProviders';
import { expectAccessible } from '../test/a11y';
import { getTemplates } from '../api/cache';
import {
  createFlow,
  disableFlow,
  enableFlow,
  getFlow,
  listConnections,
  previewFlow,
  updateFlow,
} from '../api/integrations';

vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));
vi.mock('../api/integrations', () => ({
  listConnections: vi.fn(),
  getFlow: vi.fn(),
  createFlow: vi.fn(),
  updateFlow: vi.fn(),
  previewFlow: vi.fn(),
  enableFlow: vi.fn(),
  disableFlow: vi.fn(),
}));

const CONNECTIONS = [
  { id: 7, name: 'Core DB', type: 'DATABASE', mode: 'DIRECT' },
  { id: 8, name: 'Loans API', type: 'REST_API', mode: 'DIRECT' },
  { id: 9, name: 'Hooks', type: 'WEBHOOK', mode: 'DIRECT' },
];
const TEMPLATES = [
  { templateId: 3, name: 'Loan statement', visibility: 'BANK' },
  { templateId: 4, name: 'My private draft', visibility: 'PRIVATE' },
];

const INBOUND = {
  id: 12,
  name: 'Pull loans',
  direction: 'INBOUND',
  connectionId: 8,
  connectionName: 'Loans API',
  templateId: 3,
  templateName: 'Loan statement',
  trigger: 'SCHEDULE',
  cron: '0 */15 * * * *',
  events: [],
  payloadKinds: ['VALIDATED_RECORDS'],
  format: 'JSON',
  sourceSpec: {
    path: '/loans',
    query: { status: 'open' },
    pagination: { type: 'PAGE', pageParam: 'page', sizeParam: 'size', pageSize: 100 },
    recordsPath: '$.items',
  },
  targetSpec: null,
  fieldMapping: [],
  maskPii: true,
  rowCap: 5000,
  enabled: false,
  pendingChangeId: null,
  version: 2,
};
const OUTBOUND = {
  ...INBOUND,
  id: 13,
  name: 'Push results',
  direction: 'OUTBOUND',
  connectionId: 7,
  trigger: 'EVENT',
  cron: null,
  events: ['INGESTION_VALIDATED'],
  sourceSpec: null,
  targetSpec: { table: 'stg_results', mode: 'INSERT', keyColumns: [] },
};

const renderBuilder = (id = 'new') =>
  renderWithProviders(<IntegrationFlowBuilderPage />, {
    path: `/admin/integrations/flows/${id}`,
    route: '/admin/integrations/flows/:id',
  });

describe('cron helper', () => {
  it('maps presets to six-field Spring cron expressions and back', () => {
    expect(cronFor('every5', '', '')).toBe('0 */5 * * * *');
    expect(cronFor('every15', '', '')).toBe('0 */15 * * * *');
    expect(cronFor('every60', '', '')).toBe('0 0 * * * *');
    expect(cronFor('daily', '07:30', '')).toBe('0 30 7 * * *');
    expect(cronFor('custom', '', ' 0 0 */2 * * * ')).toBe('0 0 */2 * * *');
    expect(presetOf('0 30 7 * * *')).toEqual({ preset: 'daily', time: '07:30' });
    expect(presetOf('0 */5 * * * *').preset).toBe('every5');
    expect(presetOf('0 0 1 * * MON').preset).toBe('custom');
  });
});

describe('IntegrationFlowBuilderPage', () => {
  beforeEach(() => {
    listConnections.mockReset().mockResolvedValue(CONNECTIONS);
    getTemplates.mockReset().mockResolvedValue(TEMPLATES);
    getFlow.mockReset().mockImplementation((id) =>
      Promise.resolve(String(id) === '13' ? OUTBOUND : INBOUND)
    );
    createFlow.mockReset().mockResolvedValue({ ...OUTBOUND, id: 13 });
    updateFlow.mockReset().mockImplementation((id, body) => Promise.resolve({ ...INBOUND, ...body, id }));
    previewFlow.mockReset();
    enableFlow.mockReset();
    disableFlow.mockReset();
  });

  it('builds an outbound database flow on a daily schedule', async () => {
    renderBuilder();
    await userEvent.type(await screen.findByLabelText('Flow name'), 'Push results');
    await userEvent.click(screen.getByRole('radio', { name: /Outbound/ }));
    await userEvent.selectOptions(screen.getByLabelText('Connection'), '7');
    // Only bank-shared templates are offered.
    const template = screen.getByLabelText(/^Template/);
    expect(within(template).queryByText('My private draft')).not.toBeInTheDocument();
    await userEvent.selectOptions(template, '3');

    await userEvent.click(screen.getByRole('radio', { name: 'Schedule' }));
    await userEvent.selectOptions(screen.getByLabelText('Repeat'), 'daily');
    const time = screen.getByLabelText(/Time/);
    await userEvent.clear(time);
    await userEvent.type(time, '07:30');
    expect(screen.getByTestId('cron-value')).toHaveTextContent('0 30 7 * * *');

    expect(screen.getByRole('heading', { name: 'What to send' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: 'Rejected rows' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Job status & errors' }));
    await userEvent.selectOptions(screen.getByLabelText('Format'), 'CSV');

    await userEvent.type(screen.getByLabelText(/Target table/), 'stg_results');
    await userEvent.selectOptions(screen.getByLabelText('Write mode'), 'UPSERT');
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(screen.getByRole('alert')).toHaveTextContent('UPSERT needs at least one key column.');
    await userEvent.type(screen.getByLabelText(/Key columns/), 'account_no, period');

    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() =>
      expect(createFlow).toHaveBeenCalledWith({
        name: 'Push results',
        direction: 'OUTBOUND',
        connectionId: 7,
        templateId: 3,
        trigger: 'SCHEDULE',
        cron: '0 30 7 * * *',
        events: [],
        payloadKinds: ['VALIDATED_RECORDS', 'REJECTED_ROWS', 'JOB_STATUS'],
        format: 'CSV',
        sourceSpec: null,
        targetSpec: { table: 'stg_results', mode: 'UPSERT', keyColumns: ['account_no', 'period'] },
        fieldMapping: [],
        maskPii: true,
        rowCap: null,
      })
    );
    expect(await screen.findByText('Flow "Push results" saved')).toBeInTheDocument();
    // The new flow's own URL.
    await waitFor(() => expect(getFlow).toHaveBeenCalledWith('13'));
  });

  it('takes events as the trigger and refuses an inbound webhook flow', async () => {
    renderBuilder();
    await userEvent.type(await screen.findByLabelText('Flow name'), 'Hook feed');
    await userEvent.selectOptions(screen.getByLabelText('Connection'), '9');
    await userEvent.selectOptions(screen.getByLabelText(/^Template/), '3');
    expect(screen.getByText(/A webhook connection can only send/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'On events' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Pick at least one event.');

    await userEvent.click(screen.getByRole('checkbox', { name: 'Document released' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(screen.getByRole('alert')).toHaveTextContent('can only send (OUTBOUND)');

    await userEvent.click(screen.getByRole('radio', { name: /Outbound/ }));
    await userEvent.selectOptions(screen.getByLabelText('Connection'), '9');
    await userEvent.type(screen.getByLabelText(/URL path/), '/hooks/modoc');
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() =>
      expect(createFlow).toHaveBeenCalledWith(
        expect.objectContaining({
          trigger: 'EVENT',
          events: ['DOC_RELEASED'],
          cron: null,
          targetSpec: { path: '/hooks/modoc' },
        })
      )
    );
  });

  it('loads a flow, runs the dry run and prefills the mapping from its suggestions', async () => {
    previewFlow.mockResolvedValue({
      columns: ['acct', 'amount'],
      rows: [
        { acct: 'A-1', amount: '10.00' },
        { acct: 'A-2', amount: '12.50' },
      ],
      suggestedMapping: [
        { from: 'acct', to: 'AccountNumber', score: 0.92 },
        { from: 'amount', to: 'Amount', score: 1 },
      ],
    });
    renderBuilder(12);

    // The stored REST source spec is back in the form.
    expect(await screen.findByLabelText('Path')).toHaveValue('/loans');
    expect(screen.getByLabelText('Pagination')).toHaveValue('PAGE');
    expect(screen.getByLabelText('Page size')).toHaveValue('100');
    expect(screen.getByLabelText(/Query parameters/)).toHaveValue('status=open');
    expect(screen.getByLabelText('Repeat')).toHaveValue('every15');
    expect(screen.getByRole('heading', { name: 'What to receive' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Dry run / preview' }));

    // Unchanged since load: no save, straight to the dry run.
    expect(updateFlow).not.toHaveBeenCalled();
    await waitFor(() => expect(previewFlow).toHaveBeenCalledWith(12));
    const result = await screen.findByRole('region', { name: 'Dry run result' });
    expect(within(result).getByText('Dry run — nothing was written')).toBeInTheDocument();
    expect(within(result).getByText('A-2')).toBeInTheDocument();

    expect(screen.getByLabelText('Mapping 1 from')).toHaveValue('acct');
    expect(screen.getByLabelText('Mapping 1 to')).toHaveValue('AccountNumber');
    expect(screen.getByText('suggested 92%')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(updateFlow).toHaveBeenCalled());
    const [, body] = updateFlow.mock.calls[0];
    expect(body.fieldMapping).toEqual([
      { from: 'acct', to: 'AccountNumber' },
      { from: 'amount', to: 'Amount' },
    ]);
    expect(body.expectedVersion).toBe(2);
    expect(body.sourceSpec).toEqual({
      path: '/loans',
      query: { status: 'open' },
      pagination: { type: 'PAGE', pageParam: 'page', sizeParam: 'size', pageSize: 100 },
      recordsPath: '$.items',
    });
  });

  it('warns that turning PII masking off needs approval', async () => {
    renderBuilder(12);
    const toggle = await screen.findByRole('switch', { name: 'Mask personal data (PII)' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByText('Masking off needs approval')).not.toBeInTheDocument();

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText('Masking off needs approval')).toBeInTheDocument();
  });

  it('sends an OUTBOUND enable for approval (202)', async () => {
    enableFlow.mockResolvedValue(pendingChange(50, 'INTEGRATION_FLOW_ENABLE'));
    renderBuilder(13);
    await userEvent.click(await screen.findByRole('button', { name: 'Request enable' }));

    await waitFor(() => expect(enableFlow).toHaveBeenCalledWith(13));
    expect(await screen.findByText('Sent for approval (change #50)')).toBeInTheDocument();
    expect(screen.getByText('Pending approval')).toBeInTheDocument();
    expect(screen.getByText(/Enabling flow "Push results" is change #50/)).toBeInTheDocument();
  });

  it('keeps keyboard focus in the mapping table when rows are added and removed', async () => {
    const user = userEvent.setup();
    renderBuilder(12);
    const addRow = await screen.findByRole('button', { name: 'Add row' });

    // Keyboard only: activate "Add row" with Enter.
    addRow.focus();
    await user.keyboard('{Enter}');
    expect(screen.getByLabelText('Mapping 1 from')).toHaveFocus();
    await user.keyboard('loan_id');
    await user.tab();
    expect(screen.getByLabelText('Mapping 1 to')).toHaveFocus();
    await user.keyboard('loanId');

    addRow.focus();
    await user.keyboard('{Enter}');
    expect(screen.getByLabelText('Mapping 2 from')).toHaveFocus();

    // Removing row 1 moves focus to the row that took its place.
    screen.getByRole('button', { name: 'Remove mapping 1' }).focus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Remove mapping 1' })).toHaveFocus();
    // Removing the last row hands focus back to "Add row", not to <body>.
    await user.keyboard('{Enter}');
    expect(screen.queryByRole('table', { name: 'Field mapping' })).toBeNull();
    expect(addRow).toHaveFocus();
  });

  it('announces the dry run result in a status region', async () => {
    previewFlow.mockResolvedValue({
      columns: ['loan_id', 'amount'],
      rows: [{ loan_id: 'L-1', amount: 10 }],
      suggestedMapping: [],
    });
    renderBuilder(12);
    await userEvent.click(await screen.findByRole('button', { name: 'Dry run / preview' }));
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Dry run — nothing was written');
    expect(screen.getByRole('table', { name: 'Dry run: first rows read' })).toBeInTheDocument();
    expectAccessible();
  });

  it('enables an INBOUND flow at once and disables immediately', async () => {
    enableFlow.mockResolvedValue({ ...INBOUND, enabled: true });
    disableFlow.mockResolvedValue({ ...INBOUND, enabled: false });
    renderBuilder(12);
    await userEvent.click(await screen.findByRole('button', { name: 'Enable' }));

    expect(await screen.findByText('Flow "Pull loans" enabled')).toBeInTheDocument();
    expect(screen.getByText('Enabled')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(disableFlow).toHaveBeenCalledWith(12));
    expect(await screen.findByText('Flow "Pull loans" disabled')).toBeInTheDocument();
  });
});
