import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DataTemplateEditorPage from './DataTemplateEditorPage';
import { expectAccessible } from '../test/a11y';

import {
  getDataTemplate,
  saveDataTemplateFields,
  saveDataTemplateRules,
  testDataTemplateRules,
  updateDataTemplate,
} from '../api/dataTemplates';

vi.mock('../api/dataTemplates', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    DATA_FIELD_KINDS: actual.DATA_FIELD_KINDS,
    toDataRuleInput: actual.toDataRuleInput,
    toFieldInput: actual.toFieldInput,
    getDataTemplate: vi.fn(),
    updateDataTemplate: vi.fn(),
    saveDataTemplateFields: vi.fn(),
    saveDataTemplateRules: vi.fn(),
    testDataTemplateRules: vi.fn(),
  };
});

const RULE_TYPES = [
  { type: 'REQUIRED', kinds: ['TEXT', 'DATE', 'CHOICE', 'NUMBER'], configKeys: [] },
  { type: 'LENGTH', kinds: ['TEXT', 'CHOICE'], configKeys: ['Min', 'Max'] },
  { type: 'DATE_RANGE', kinds: ['DATE', 'TEXT'], configKeys: ['Min', 'Max'] },
];

const FIELDS = [
  { name: 'full_name', label: 'Full name', kind: 'TEXT', piiKind: 'NAME', props: null, sourceFieldName: 'full_name', sortOrder: 0 },
  { name: 'dob', label: 'Date of birth', kind: 'DATE', piiKind: null, props: null, sourceFieldName: null, sortOrder: 1 },
];

const RULES = [
  { id: 1, fieldName: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'IMPORTED', sourceTemplateId: 9, sourceTemplateName: 'Customers', sourceFieldName: 'full_name', sortOrder: 0 },
  { id: 2, fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, message: null, severity: 'ERROR', origin: 'MANUAL', sortOrder: 0 },
];

const dto = (overrides = {}) => ({
  id: 3,
  name: 'Customer',
  description: 'KYC data',
  revision: 4,
  sourceTemplateId: 9,
  sourceTemplateName: 'Customers',
  fields: FIELDS,
  rules: RULES,
  usedBy: [{ blueprintId: 4, name: 'Loan form' }],
  ruleTypes: RULE_TYPES,
  ...overrides,
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creation/data-templates/3']}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/data-templates/:id" element={<DataTemplateEditorPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const row = (name) => within(screen.getByTestId(`data-field-${name}`));
const button = (name) => screen.getByRole('button', { name });

describe('DataTemplateEditorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getDataTemplate.mockResolvedValue(dto());
    saveDataTemplateFields.mockImplementation(async (id, { baseRevision, fields }) =>
      dto({ revision: baseRevision + 1, fields: fields.map((f, i) => ({ piiKind: null, props: null, sourceFieldName: null, ...f, sortOrder: i })) })
    );
    saveDataTemplateRules.mockImplementation(async (id, { baseRevision, rules }) =>
      dto({ revision: baseRevision + 1, rules: rules.map((r, i) => ({ id: 50 + i, sortOrder: 0, ...r })) })
    );
  });

  it('shows the details and the fields with kind, PII, Required and rule chips', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Customer', level: 1 })).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveValue('Customer');
    expect(screen.getByLabelText('Description')).toHaveValue('KYC data');
    expect(screen.getByText(/Copied from the flow template Customers/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Loan form' })).toHaveAttribute('href', '/creation/blueprints/4?step=mapping');

    const table = screen.getByRole('table', { name: 'Fields of Customer' });
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Name',
      'Label',
      'Kind',
      'PII',
      'Required',
      'Rules',
      'Actions',
    ]);
    expect(row('full_name').getByLabelText('Label of full_name')).toHaveValue('Full name');
    expect(row('full_name').getByLabelText('PII of Full name')).toHaveValue('NAME');
    expect(row('full_name').getByRole('switch', { name: 'Required: Full name' })).toBeChecked();
    expect(row('dob').getByRole('switch', { name: 'Required: Date of birth' })).not.toBeChecked();
    expect(row('dob').getByLabelText('Kind of Date of birth')).toHaveValue('DATE');
    // Dropdown is a kind on offer.
    expect(within(row('dob').getByLabelText('Kind of Date of birth')).getByRole('option', { name: 'Dropdown' })).toHaveValue('CHOICE');
    expect(row('full_name').getByRole('list', { name: 'Rules of Full name' })).toHaveTextContent('Must be filled in');
    expect(row('dob').getByRole('list', { name: 'Rules of Date of birth' })).toHaveTextContent('A date on or before -18y');
    expect(button('Save fields')).toBeDisabled();
    expect(button('Save rules')).toBeDisabled();
    expectAccessible();
  });

  it('turns Required on and off as a REQUIRED rule and saves the rules with the revision', async () => {
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.click(row('dob').getByRole('switch', { name: 'Required: Date of birth' }));
    expect(row('dob').getByRole('switch', { name: 'Required: Date of birth' })).toBeChecked();
    expect(row('dob').getByRole('list', { name: 'Rules of Date of birth' })).toHaveTextContent('Must be filled in');
    await userEvent.click(row('full_name').getByRole('switch', { name: 'Required: Full name' }));
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes: rules.');

    await userEvent.click(button('Save rules'));
    expect(saveDataTemplateFields).not.toHaveBeenCalled();
    const body = saveDataTemplateRules.mock.calls[0][1];
    expect(body.baseRevision).toBe(4);
    expect(body.rules).toEqual([
      { fieldName: 'dob', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'MANUAL' },
      { fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, message: null, severity: 'ERROR', origin: 'MANUAL' },
    ]);
    expect(await screen.findByText('Rules saved')).toBeInTheDocument();
    expect(button('Save rules')).toBeDisabled();
  });

  it('edits the rules of a field in the drawer with the shared rule dialog', async () => {
    renderPage();
    await screen.findByTestId('data-field-full_name');
    await userEvent.click(row('full_name').getByRole('button', { name: 'Edit rules of Full name' }));
    let drawer = await screen.findByRole('dialog', { name: 'Rules — Full name' });
    expect(within(drawer).getByRole('table', { name: 'Rules of Full name' })).toHaveTextContent('Imported from Customers.full_name');

    await userEvent.click(within(drawer).getByRole('button', { name: 'Add rule to Full name' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a rule — Full name' });
    // Only the rule types that fit a text field.
    expect(within(within(dialog).getByLabelText('Rule type')).getAllByRole('option').map((o) => o.value)).toEqual([
      'REQUIRED',
      'LENGTH',
      'DATE_RANGE',
    ]);
    await userEvent.selectOptions(within(dialog).getByLabelText('Rule type'), 'LENGTH');
    await userEvent.type(within(dialog).getByLabelText('Maximum length'), '40');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));

    drawer = await screen.findByRole('dialog', { name: 'Rules — Full name' });
    expect(within(drawer).getByRole('table', { name: 'Rules of Full name' })).toHaveTextContent('Length at most 40 characters');
    expectAccessible();
    await userEvent.click(within(drawer).getByRole('button', { name: 'Done' }));
    expect(row('full_name').getByRole('list', { name: 'Rules of Full name' })).toHaveTextContent('Length at most 40 characters');

    await userEvent.click(button('Save rules'));
    const rules = saveDataTemplateRules.mock.calls[0][1].rules.filter((r) => r.fieldName === 'full_name');
    expect(rules.map((r) => [r.ruleType, r.config])).toEqual([
      ['REQUIRED', {}],
      ['LENGTH', { Max: 40 }],
    ]);
  });

  it('adds a dropdown field with its options, then saves the fields before its rules', async () => {
    renderPage();
    await screen.findByTestId('data-field-full_name');
    await userEvent.click(button('+ Add field'));
    const label = screen.getByLabelText('Label of new_field');
    await waitFor(() => expect(label).toHaveFocus());
    await userEvent.clear(label);
    await userEvent.type(label, 'Marital status');
    // A new field's name follows its label.
    expect(screen.getByTestId('data-field-marital_status')).toBeInTheDocument();
    await userEvent.selectOptions(row('marital_status').getByLabelText('Kind of Marital status'), 'CHOICE');

    const properties = screen.getByTestId('field-properties');
    expect(within(properties).getByRole('heading', { name: 'Properties: Marital status' })).toBeInTheDocument();
    await userEvent.click(within(properties).getByRole('button', { name: 'Add option' }));
    await userEvent.type(within(properties).getByLabelText('Option 1'), 'Single');
    await userEvent.click(row('marital_status').getByRole('switch', { name: 'Required: Marital status' }));
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes: fields and rules.');

    await userEvent.click(button('Save rules'));
    expect(saveDataTemplateFields).toHaveBeenCalledTimes(1);
    const fieldsBody = saveDataTemplateFields.mock.calls[0][1];
    expect(fieldsBody.baseRevision).toBe(4);
    expect(fieldsBody.fields[2]).toEqual({
      name: 'marital_status',
      label: 'Marital status',
      kind: 'CHOICE',
      props: { options: ['Single'] },
    });
    await waitFor(() => expect(saveDataTemplateRules).toHaveBeenCalledTimes(1));
    // The rules go with the revision the field save returned.
    expect(saveDataTemplateRules.mock.calls[0][1].baseRevision).toBe(5);
    expect(saveDataTemplateRules.mock.calls[0][1].rules.at(-1)).toMatchObject({ fieldName: 'marital_status', ruleType: 'REQUIRED' });
    expect(await screen.findByText('Fields and rules saved')).toBeInTheDocument();
  });

  it('deletes a field after confirming, with its rules', async () => {
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.click(row('dob').getByRole('button', { name: 'Delete Date of birth' }));
    const confirm = await screen.findByRole('dialog', { name: 'Delete this field?' });
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete field' }));
    expect(screen.queryByTestId('data-field-dob')).not.toBeInTheDocument();

    await userEvent.click(button('Save fields'));
    expect(saveDataTemplateFields.mock.calls[0][1].fields.map((f) => f.name)).toEqual(['full_name']);
    expect(await screen.findByText('Fields saved')).toBeInTheDocument();
    // The rule of the removed field waits for "Save rules".
    expect(button('Save rules')).toBeEnabled();
  });

  it('tests sample values against the rules, unsaved ones included', async () => {
    testDataTemplateRules.mockResolvedValue({
      state: 'DRAFT',
      checked: true,
      errors: [{ regionKey: 'full_name', label: 'Full name', ruleId: 1, code: 'REQUIRED', message: 'Full name is required.' }],
      warnings: [],
      setupWarnings: [],
    });
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.click(button('Test with sample values'));
    const panel = screen.getByTestId('rules-test');
    await userEvent.type(within(panel).getByLabelText('Sample value for Date of birth'), '2020-01-01');
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testDataTemplateRules).toHaveBeenCalledWith('3', { values: { dob: '2020-01-01' }, rules: undefined });
    expect(await within(panel).findByText('1 error, 0 warnings')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Sample value for Full name')).toHaveAccessibleDescription('Full name is required.');

    await userEvent.click(row('dob').getByRole('switch', { name: 'Required: Date of birth' }));
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testDataTemplateRules.mock.calls.at(-1)[1].rules).toHaveLength(3);
  });

  it('offers to reload when the data template was saved elsewhere (409)', async () => {
    saveDataTemplateRules.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }));
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.click(row('dob').getByRole('switch', { name: 'Required: Date of birth' }));
    await userEvent.click(button('Save rules'));
    const dialog = await screen.findByRole('dialog', { name: 'This data template was changed elsewhere' });
    expectAccessible();

    getDataTemplate.mockResolvedValue(dto({ revision: 6 }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reload the latest version' }));
    expect(await screen.findByText(/your unsaved changes were discarded/)).toBeInTheDocument();
    expect(getDataTemplate).toHaveBeenCalledTimes(2);
    expect(row('dob').getByRole('switch', { name: 'Required: Date of birth' })).not.toBeChecked();
    expect(screen.getByText(/revision 6/)).toBeInTheDocument();
  });

  it('flags rules that no longer fit after a kind change and saves the rules before the fields when the server asks', async () => {
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.selectOptions(row('dob').getByLabelText('Kind of Date of birth'), 'NUMBER');
    expect(row('dob').getByRole('alert')).toHaveTextContent('A rule does not fit a Number field — remove it with Edit rules');
    await userEvent.click(button('Save fields'));
    expect(saveDataTemplateFields).not.toHaveBeenCalled();
    expect(screen.getByText('Fix the problems shown in the fields table first.')).toBeInTheDocument();

    // Remove the unfitting rule in the drawer, then Save rules: the server still holds it, so it refuses the
    // fields first (400); the rules of the saved fields go first, then the fields, then the rest of the rules.
    await userEvent.click(row('dob').getByRole('button', { name: 'Edit rules of Date of birth' }));
    const drawer = await screen.findByRole('dialog', { name: 'Rules — Date of birth' });
    await userEvent.click(within(drawer).getByRole('button', { name: 'Delete Date range rule of Date of birth' }));
    await userEvent.click(within(drawer).getByRole('button', { name: 'Done' }));
    expect(row('dob').queryByRole('alert')).not.toBeInTheDocument();

    saveDataTemplateFields
      .mockRejectedValueOnce(Object.assign(new Error('Field "dob": the rule DATE_RANGE does not fit a NUMBER field.'), { status: 400 }));
    await userEvent.click(button('Save rules'));
    await waitFor(() => expect(saveDataTemplateRules).toHaveBeenCalledTimes(2));
    expect(saveDataTemplateRules.mock.calls[0][1]).toEqual({
      baseRevision: 4,
      rules: [{ fieldName: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'IMPORTED', sourceTemplateId: 9, sourceFieldName: 'full_name' }],
    });
    expect(saveDataTemplateFields).toHaveBeenCalledTimes(2);
    expect(saveDataTemplateFields.mock.calls[1][1].baseRevision).toBe(5);
    expect(saveDataTemplateFields.mock.calls[1][1].fields[1]).toMatchObject({ name: 'dob', kind: 'NUMBER' });
    expect(await screen.findByText('Fields and rules saved')).toBeInTheDocument();
  });

  it('explains a refused field save (400) and how to get past it', async () => {
    saveDataTemplateFields.mockRejectedValue(Object.assign(new Error('Field "dob": a rule does not fit.'), { status: 400 }));
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.type(row('dob').getByLabelText('Label of dob'), '!');
    await userEvent.click(button('Save fields'));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Field "dob": a rule does not fit. Remove the rules that no longer fit (Edit rules), save the rules, then save the fields again.'
    );
  });

  it('warns that deleting a field also removes the rules of other fields naming it', async () => {
    getDataTemplate.mockResolvedValue(
      dto({
        rules: [
          ...RULES,
          { id: 3, fieldName: 'full_name', ruleType: 'REQUIRED_IF', config: { Field: 'dob' }, message: null, severity: 'ERROR', origin: 'MANUAL', sortOrder: 1 },
        ],
      })
    );
    renderPage();
    await screen.findByTestId('data-field-dob');
    await userEvent.click(row('dob').getByRole('button', { name: 'Delete Date of birth' }));
    const confirm = await screen.findByRole('dialog', { name: 'Delete this field?' });
    expect(confirm).toHaveTextContent('Rules of other fields that compare with it or depend on it (Full name) go too.');
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete field' }));
    expect(row('full_name').getByRole('list', { name: 'Rules of Full name' })).not.toHaveTextContent('Required when');

    await userEvent.click(button('Save rules'));
    await waitFor(() => expect(saveDataTemplateRules).toHaveBeenCalled());
    expect(saveDataTemplateRules.mock.calls[0][1].rules.map((r) => [r.fieldName, r.ruleType])).toEqual([['full_name', 'REQUIRED']]);
  });

  it('tests a photo field with "a photo is placed"', async () => {
    getDataTemplate.mockResolvedValue(
      dto({ fields: [...FIELDS, { name: 'photo', label: 'Photo', kind: 'IMAGE', piiKind: null, props: null, sortOrder: 2 }] })
    );
    testDataTemplateRules.mockResolvedValue({ state: 'VALIDATED', checked: true, errors: [], warnings: [], setupWarnings: [] });
    renderPage();
    await screen.findByTestId('data-field-photo');
    await userEvent.click(button('Test with sample values'));
    const panel = screen.getByTestId('rules-test');
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Photo: a photo is placed' }));
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testDataTemplateRules).toHaveBeenCalledWith('3', { values: { photo: 'photo' }, rules: undefined });
    expect(await within(panel).findByText('No errors or warnings')).toBeInTheDocument();
  });

  it('renames the data template', async () => {
    updateDataTemplate.mockResolvedValue({ ...dto(), name: 'Client' });
    renderPage();
    const name = await screen.findByLabelText('Name');
    expect(button('Save details')).toBeDisabled();
    await userEvent.clear(name);
    await userEvent.type(name, 'Client');
    await userEvent.click(button('Save details'));
    expect(updateDataTemplate).toHaveBeenCalledWith('3', { name: 'Client', description: 'KYC data' });
    expect(await screen.findByText('Details saved')).toBeInTheDocument();
  });
});
