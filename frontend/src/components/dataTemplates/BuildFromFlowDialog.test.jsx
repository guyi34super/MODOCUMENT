import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BuildFromFlowDialog, { predictFieldNames } from './BuildFromFlowDialog';
import { expectAccessible } from '../../test/a11y';

import { addFromFlowTemplates, buildFromFlowTemplates, copyPreview } from '../../api/dataTemplates';
import { getTemplates } from '../../api/cache';

vi.mock('../../api/dataTemplates', () => ({
  copyPreview: vi.fn(),
  buildFromFlowTemplates: vi.fn(),
  addFromFlowTemplates: vi.fn(),
}));
vi.mock('../../api/cache', () => ({ getTemplates: vi.fn() }));

const PREVIEWS = {
  9: {
    templateId: 9,
    templateName: 'Customers',
    fields: [
      { name: 'Full Name', dataType: 'Text', kind: 'TEXT', piiKind: 'NAME', rules: [{ ruleType: 'REQUIRED', config: {}, message: null }], skipped: [] },
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
  },
  11: {
    templateId: 11,
    templateName: 'Bank Accounts',
    fields: [
      { name: 'full_name', dataType: 'Text', kind: 'TEXT', piiKind: 'NAME', rules: [{ ruleType: 'LENGTH', config: { Max: 40 }, message: null }], skipped: [] },
      { name: 'iban', dataType: 'Text', kind: 'TEXT', piiKind: 'ACCOUNT_NUMBER', rules: [{ ruleType: 'REGEX', config: { Pattern: '^[A-Z]{2}\\d+$' }, message: null }], skipped: [] },
    ],
  },
};

const CURRENT = {
  id: 3,
  name: 'Customer',
  revision: 4,
  fields: [
    { name: 'iban', label: 'IBAN', kind: 'TEXT' },
    { name: 'iban_bank_accounts', label: 'IBAN 2', kind: 'TEXT' },
  ],
};

function renderDialog(props = {}) {
  const onSaved = vi.fn();
  const onClose = vi.fn();
  const onConflict = vi.fn();
  render(<BuildFromFlowDialog open onClose={onClose} onSaved={onSaved} onConflict={onConflict} {...props} />);
  return { onSaved, onClose, onConflict, dialog: screen.getByRole('dialog', { name: 'Build from Document Flow templates' }) };
}

const click = (scope, name) => userEvent.click(within(scope).getByRole('button', { name }));

async function pickTemplates(dialog, names) {
  for (const name of names) await userEvent.click(await within(dialog).findByRole('checkbox', { name }));
  await click(dialog, 'Next: choose fields');
  await within(dialog).findByTestId('build-fields');
  await waitFor(() => expect(within(dialog).queryByText('Reading their fields and rules…')).not.toBeInTheDocument());
}

describe('predictFieldNames', () => {
  it('slugs the flow names and gives a clash the flow template’s name, then a number', () => {
    expect(
      predictFieldNames(
        [
          { templateName: 'Customers', fields: ['Full Name', 'full_name'] },
          { templateName: 'Bank Accounts', fields: ['full_name', 'iban'] },
        ],
        ['iban']
      )
    ).toEqual([
      { source: 0, flowName: 'Full Name', name: 'full_name', renamed: false },
      { source: 0, flowName: 'full_name', name: 'full_name_customers', renamed: true },
      { source: 1, flowName: 'full_name', name: 'full_name_bank_accounts', renamed: true },
      { source: 1, flowName: 'iban', name: 'iban_bank_accounts', renamed: true },
    ]);
    expect(predictFieldNames([{ templateName: 'A', fields: ['x', 'x', 'x', 'x'] }]).map((p) => p.name)).toEqual(['x', 'x_a', 'x_a_2', 'x_a_3']);
  });
});

describe('BuildFromFlowDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getTemplates.mockResolvedValue([
      { templateId: 9, name: 'Customers' },
      { templateId: 11, name: 'Bank Accounts' },
      { templateId: 12, name: 'Vehicles' },
    ]);
    copyPreview.mockImplementation(async (id) => PREVIEWS[id]);
  });

  it('builds a new template from the chosen fields of two flow templates, a clash renamed', async () => {
    buildFromFlowTemplates.mockResolvedValue({ id: 8, name: 'Bank Accounts + Customers' });
    const { dialog, onSaved } = renderDialog();

    // Step 1: search and tick two templates (the order of ticking is the order sent).
    const search = within(dialog).getByLabelText('Search flow templates');
    await within(dialog).findByRole('checkbox', { name: 'Vehicles' });
    expect(within(dialog).getByRole('button', { name: 'Next: choose fields' })).toBeDisabled();
    await userEvent.type(search, 'acc');
    expect(within(dialog).queryByRole('checkbox', { name: 'Customers' })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Bank Accounts' }));
    await userEvent.clear(search);
    expect(within(dialog).getByRole('checkbox', { name: 'Bank Accounts' })).toBeChecked();
    expect(within(dialog).getByText('Flow templates (1 ticked)')).toBeInTheDocument();
    expectAccessible();
    await pickTemplates(dialog, ['Customers']);

    // Step 2: per template, every field ticked with its type, PII and rules.
    expect(copyPreview).toHaveBeenCalledWith(11);
    expect(copyPreview).toHaveBeenCalledWith(9);
    const customers = within(dialog).getByTestId('build-source-9');
    const accounts = within(dialog).getByTestId('build-source-11');
    expect(within(accounts).getByText('Bank Accounts (2 of 2 fields)')).toBeInTheDocument();
    expect(accounts.compareDocumentPosition(customers) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    within(customers)
      .getAllByRole('checkbox')
      .forEach((box) => expect(box).toBeChecked());
    const fullName = within(customers).getByTestId('build-field-9-Full Name');
    expect(fullName).toHaveTextContent('Full NameTextPIIRequired');
    const dob = within(customers).getByTestId('build-field-9-date_of_birth');
    expect(dob).toHaveTextContent('Date format');
    expect(dob).toHaveTextContent("Not copied: CUSTOM (can't be imported)");
    expect(within(accounts).getByTestId('build-field-11-iban')).toHaveTextContent('Pattern');
    expectAccessible();

    await click(customers, 'Select no fields of Customers');
    within(customers)
      .getAllByRole('checkbox')
      .forEach((box) => expect(box).not.toBeChecked());
    await click(customers, 'Select all fields of Customers');
    await userEvent.click(within(customers).getByRole('checkbox', { name: 'balance' }));
    expect(within(customers).getByText('Customers (2 of 3 fields)')).toBeInTheDocument();

    // Step 3: the combined preview with the final names.
    await click(dialog, 'Next: preview');
    const preview = within(dialog).getByRole('table', { name: 'Fields of the new template' });
    expect(within(preview).getAllByRole('row').slice(1).map((r) => r.cells[0].textContent)).toEqual([
      'full_name ',
      'iban ',
      'full_name_customers renamed',
      'date_of_birth ',
    ]);
    expect(within(dialog).getByTestId('preview-row-full_name_customers')).toHaveTextContent('Customers.Full Name');
    expect(within(dialog).getByTestId('preview-row-full_name')).toHaveTextContent('Bank Accounts.full_nameTextLength');
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument();
    expectAccessible();

    // Step 4: name it and save.
    await click(dialog, 'Next: save');
    const name = within(dialog).getByLabelText('Name of the new template');
    expect(name).toHaveValue('Bank Accounts + Customers');
    await userEvent.clear(name);
    await click(dialog, 'Save as a new template');
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Give the new template a name.');
    await userEvent.type(name, 'KYC');
    await click(dialog, 'Save as a new template');
    expect(buildFromFlowTemplates).toHaveBeenCalledWith({
      name: 'KYC',
      sources: [
        { templateId: 11, fields: ['full_name', 'iban'] },
        { templateId: 9, fields: ['Full Name', 'date_of_birth'] },
      ],
    });
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ id: 8, name: 'Bank Accounts + Customers' }, { added: false, count: 4 }));
  });

  it('adds the chosen fields to the current template with its revision, previewing names against its fields', async () => {
    addFromFlowTemplates.mockResolvedValue({ ...CURRENT, revision: 5 });
    const { dialog, onSaved } = renderDialog({ current: CURRENT });
    await pickTemplates(dialog, ['Bank Accounts']);
    await click(within(dialog).getByTestId('build-source-11'), 'Select no fields of Bank Accounts');
    expect(within(dialog).getByRole('button', { name: 'Next: preview' })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'iban' }));
    await click(dialog, 'Next: preview');

    expect(within(dialog).getByTestId('preview-row-iban')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Add to Customer' }));
    const preview = within(dialog).getByRole('table', { name: 'Fields to add to Customer' });
    // "iban" and "iban_bank_accounts" are taken in Customer.
    expect(within(preview).getByTestId('preview-row-iban_bank_accounts_2')).toHaveTextContent('renamed');

    await click(dialog, 'Next: save');
    expect(within(dialog).queryByLabelText('Name of the new template')).not.toBeInTheDocument();
    expect(within(dialog).getByTestId('build-save')).toHaveTextContent('Add 1 field and their rules to Customer.');
    await click(dialog, 'Add to Customer');
    expect(addFromFlowTemplates).toHaveBeenCalledWith(3, { baseRevision: 4, sources: [{ templateId: 11, fields: ['iban'] }] });
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ ...CURRENT, revision: 5 }, { added: true, count: 1 }));
  });

  it('hands a stale revision (409) to the page', async () => {
    addFromFlowTemplates.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }));
    const { dialog, onConflict, onSaved } = renderDialog({ current: CURRENT });
    await pickTemplates(dialog, ['Customers']);
    await click(dialog, 'Next: preview');
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Add to Customer' }));
    await click(dialog, 'Next: save');
    await click(dialog, 'Add to Customer');
    await waitFor(() => expect(onConflict).toHaveBeenCalled());
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('shows why the preview could not be read', async () => {
    copyPreview.mockRejectedValue(Object.assign(new Error('You may not read this template.'), { status: 403 }));
    const { dialog } = renderDialog();
    await pickTemplates(dialog, ['Vehicles']);
    expect(within(dialog).getByRole('alert')).toHaveTextContent('You may not read this template.');
    expect(within(dialog).getByRole('button', { name: 'Next: preview' })).toBeDisabled();
  });
});
