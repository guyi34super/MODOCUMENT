import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DataTemplatesPage from './DataTemplatesPage';
import { expectAccessible } from '../test/a11y';

import {
  copyFromFlowTemplate,
  copyPreview,
  createDataTemplate,
  deleteDataTemplate,
  listDataTemplates,
} from '../api/dataTemplates';
import { getTemplates } from '../api/cache';

vi.mock('../api/dataTemplates', () => ({
  listDataTemplates: vi.fn(),
  createDataTemplate: vi.fn(),
  deleteDataTemplate: vi.fn(),
  copyPreview: vi.fn(),
  copyFromFlowTemplate: vi.fn(),
}));
vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));

const SUMMARIES = [
  {
    id: 1,
    name: 'Customer',
    description: null,
    fieldCount: 4,
    ruleCount: 6,
    sourceTemplateId: 9,
    sourceTemplateName: 'Customers',
    usedBy: 2,
    updatedAt: '2026-10-16T10:00:00',
  },
  { id: 2, name: 'Vehicle', description: null, fieldCount: 0, ruleCount: 0, sourceTemplateId: null, sourceTemplateName: null, usedBy: 0 },
];

const PREVIEW = {
  templateId: 9,
  templateName: 'Customers',
  fields: [
    {
      name: 'full_name',
      dataType: 'Text',
      kind: 'TEXT',
      piiKind: 'NAME',
      rules: [{ ruleType: 'REQUIRED', config: {}, message: null }],
      skipped: [],
    },
    {
      name: 'date_of_birth',
      dataType: 'Date',
      kind: 'DATE',
      piiKind: null,
      rules: [{ ruleType: 'DATE_FORMAT', config: { Formats: ['yyyy-MM-dd'] }, message: null }],
      skipped: [{ flowRuleType: 'CUSTOM', reason: "can't be imported" }],
    },
    { name: 'balance', dataType: 'Number', kind: 'NUMBER', piiKind: null, rules: [], skipped: [] },
  ],
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creation/data-templates']}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/data-templates" element={<DataTemplatesPage />} />
          <Route path="/creation/data-templates/:id" element={<div>EDITOR PAGE</div>} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('DataTemplatesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDataTemplates.mockResolvedValue(SUMMARIES);
    getTemplates.mockResolvedValue([
      { templateId: 9, name: 'Customers' },
      { templateId: 11, name: 'Accounts' },
    ]);
  });

  it('lists the data templates with their fields, rules, flow template and use', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: 'Data templates' });
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Name',
      'Fields',
      'Rules',
      'Based on',
      'Used by',
      'Actions',
    ]);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(within(rows[0]).getByRole('link', { name: 'Customer' })).toHaveAttribute('href', '/creation/data-templates/1');
    expect(rows[0]).toHaveTextContent('Customer46Customers2 PDF templates');
    expect(rows[1]).toHaveTextContent('Vehicle00—Not used yet');
    expect(screen.getByRole('button', { name: 'New data template' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy from a flow template' })).toBeInTheDocument();
    expectAccessible();
  });

  it('creates a data template and opens it', async () => {
    createDataTemplate.mockResolvedValue({ id: 7, name: 'Loan applicant', fields: [], rules: [] });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New data template' }));
    const dialog = await screen.findByRole('dialog', { name: 'New data template' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Give the data template a name.');
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Loan applicant');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(createDataTemplate).toHaveBeenCalledWith({ name: 'Loan applicant', description: '' });
    expect(await screen.findByText('EDITOR PAGE')).toBeInTheDocument();
  });

  it('copies a flow template: every field ticked with its rules, untick some, name it, copy', async () => {
    copyPreview.mockResolvedValue(PREVIEW);
    copyFromFlowTemplate.mockResolvedValue({ id: 8, name: 'Customers (copy)', fields: [{}, {}], rules: [] });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Copy from a flow template' }));
    const dialog = await screen.findByRole('dialog', { name: 'Copy from a flow template' });
    const picker = within(dialog).getByLabelText('Flow template');
    await waitFor(() => expect(picker).toBeEnabled());
    expect(within(dialog).getByRole('button', { name: 'Copy 0 fields' })).toBeDisabled();

    await userEvent.selectOptions(picker, 'Customers');
    expect(copyPreview).toHaveBeenCalledWith('9');
    const list = await within(dialog).findByTestId('copy-fields');
    const boxes = ['full_name', 'date_of_birth', 'balance'].map((n) =>
      within(within(list).getByTestId(`copy-field-${n}`)).getByRole('checkbox')
    );
    boxes.forEach((box) => expect(box).toBeChecked());
    expect(within(list).getByTestId('copy-field-full_name')).toHaveTextContent('Required');
    expect(within(list).getByTestId('copy-field-date_of_birth')).toHaveTextContent('Date format');
    expect(within(list).getByTestId('copy-field-date_of_birth')).toHaveTextContent("Not copied: CUSTOM (can't be imported)");
    expect(within(dialog).getByLabelText('Name of the new data template')).toHaveValue('Customers');
    expectAccessible();

    await userEvent.click(boxes[2]);
    const name = within(dialog).getByLabelText('Name of the new data template');
    await userEvent.clear(name);
    await userEvent.type(name, 'Customers (copy)');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Copy 2 fields' }));
    expect(copyFromFlowTemplate).toHaveBeenCalledWith({
      templateId: 9,
      name: 'Customers (copy)',
      fields: ['full_name', 'date_of_birth'],
    });
    expect(await screen.findByText('EDITOR PAGE')).toBeInTheDocument();
  });

  it('asks before deleting and shows why a used data template cannot be deleted (409)', async () => {
    deleteDataTemplate.mockRejectedValue(
      Object.assign(new Error('2 PDF templates use this data template.'), { status: 409 })
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Delete Customer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete data template' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(deleteDataTemplate).toHaveBeenCalledWith(1);
    expect(await screen.findByRole('alert')).toHaveTextContent('2 PDF templates use this data template.');
  });
});
