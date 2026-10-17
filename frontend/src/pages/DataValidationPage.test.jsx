import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DataValidationPage from './DataValidationPage';
import DataTemplateRedirect from './DataTemplateRedirect';
import { DEFAULT_RULE_TYPES } from '../components/rules/ruleText';
import { expectAccessible } from '../test/a11y';

import {
  buildFromFlowTemplates,
  copyPreview,
  createDataTemplate,
  deleteDataTemplate,
  getDataTemplate,
  listDataTemplates,
  saveDataTemplateFields,
  saveDataTemplateRules,
  testDataTemplateRules,
  updateDataTemplate,
} from '../api/dataTemplates';
import { getTemplates } from '../api/cache';

vi.mock('../api/dataTemplates', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    DATA_FIELD_KINDS: actual.DATA_FIELD_KINDS,
    toDataRuleInput: actual.toDataRuleInput,
    toFieldInput: actual.toFieldInput,
    listDataTemplates: vi.fn(),
    createDataTemplate: vi.fn(),
    getDataTemplate: vi.fn(),
    updateDataTemplate: vi.fn(),
    deleteDataTemplate: vi.fn(),
    saveDataTemplateFields: vi.fn(),
    saveDataTemplateRules: vi.fn(),
    testDataTemplateRules: vi.fn(),
    copyPreview: vi.fn(),
    buildFromFlowTemplates: vi.fn(),
    addFromFlowTemplates: vi.fn(),
  };
});
vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));

const RULE_TYPES = DEFAULT_RULE_TYPES.map((t) => ({ ...t, kinds: t.kinds.filter((k) => k !== 'SIGNATURE') }));

const FIELDS = [
  {
    name: 'full_name',
    label: 'Full name',
    kind: 'TEXT',
    piiKind: 'NAME',
    props: null,
    sourceFieldName: 'FullName',
    sourceTemplateId: 9,
    sourceTemplateName: 'Customers',
    sortOrder: 0,
  },
  { name: 'email', label: 'Email', kind: 'TEXT', piiKind: 'EMAIL', props: null, sourceFieldName: null, sortOrder: 1 },
  { name: 'dob', label: 'Date of birth', kind: 'DATE', piiKind: null, props: null, sourceFieldName: null, sortOrder: 2 },
  { name: 'amount', label: 'Amount', kind: 'NUMBER', piiKind: null, props: null, sourceFieldName: null, sortOrder: 3 },
  { name: 'photo', label: 'Photo', kind: 'IMAGE', piiKind: null, props: null, sourceFieldName: null, sortOrder: 4 },
];

const REQUIRED_NAME = {
  id: 1,
  fieldName: 'full_name',
  ruleType: 'REQUIRED',
  config: {},
  message: null,
  severity: 'ERROR',
  origin: 'IMPORTED',
  sourceTemplateId: 9,
  sourceTemplateName: 'Customers',
  sourceFieldName: 'FullName',
  sortOrder: 0,
};
const DOB_RANGE = { id: 2, fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, message: 'Too young', severity: 'WARNING', origin: 'MANUAL', sortOrder: 0 };
const RULES = [REQUIRED_NAME, DOB_RANGE];

const dto = (overrides = {}) => ({
  id: 3,
  name: 'Customer',
  description: null,
  revision: 4,
  sourceTemplateId: 9,
  sourceTemplateName: 'Customers',
  fields: FIELDS,
  rules: RULES,
  usedBy: [{ blueprintId: 4, name: 'Loan form' }],
  ruleTypes: RULE_TYPES,
  ...overrides,
});

const SUMMARIES = [
  { id: 3, name: 'Customer', fieldCount: 5, ruleCount: 2, usedBy: 1 },
  { id: 5, name: 'Vehicle', fieldCount: 0, ruleCount: 0, usedBy: 0 },
];

function Location() {
  const location = useLocation();
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>;
}

function renderPage(path = '/creation/data-templates?template=3') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/data-templates" element={<DataValidationPage />} />
          <Route path="/creation/data-templates/:id" element={<DataTemplateRedirect />} />
        </Routes>
        <Location />
      </ToastProvider>
    </MemoryRouter>
  );
}

const location = () => screen.getByTestId('location').textContent;
const button = (name) => screen.getByRole('button', { name });
const row = (name) => within(screen.getByTestId(`data-field-${name}`));
const rulesTable = () => screen.getByRole('table', { name: 'Rules of Customer' });
const ruleCard = () => screen.getByRole('heading', { name: /field rules$/ }).closest('form').parentElement;
const lastRules = () => saveDataTemplateRules.mock.calls.at(-1)[1];

async function loaded() {
  await screen.findByTestId('data-field-full_name');
}

async function chooseField(label) {
  await userEvent.selectOptions(screen.getByLabelText('Field'), label);
}

describe('DataValidationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDataTemplates.mockResolvedValue(SUMMARIES);
    getDataTemplate.mockResolvedValue(dto());
    getTemplates.mockResolvedValue([
      { templateId: 9, name: 'Customers' },
      { templateId: 11, name: 'Accounts' },
    ]);
    saveDataTemplateFields.mockImplementation(async (id, { baseRevision, fields }) =>
      dto({ revision: baseRevision + 1, fields: fields.map((f, i) => ({ piiKind: null, props: null, sourceFieldName: null, ...f, sortOrder: i })) })
    );
    saveDataTemplateRules.mockImplementation(async (id, { baseRevision, rules }) =>
      dto({ revision: baseRevision + 1, rules: rules.map((r, i) => ({ id: 50 + i, sortOrder: 0, ...r })) })
    );
  });

  it('chooses a template with the Template select, keeps it in ?template= and shows its fields, rules and use', async () => {
    renderPage('/creation/data-templates');
    expect(await screen.findByRole('heading', { name: 'Validation', level: 1 })).toBeInTheDocument();
    const select = screen.getByLabelText('Template');
    await waitFor(() => expect(select).toBeEnabled());
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Select a template…', 'Customer', 'Vehicle']);
    expect(screen.getByText('Choose a template')).toBeInTheDocument();
    expect(button('New template')).toBeInTheDocument();
    expect(button('Build from Document Flow templates')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rename' })).not.toBeInTheDocument();

    await userEvent.selectOptions(select, 'Customer');
    expect(location()).toBe('/creation/data-templates?template=3');
    await loaded();
    expect(getDataTemplate).toHaveBeenCalledWith('3');
    expect(button('Rename')).toBeInTheDocument();
    expect(button('Delete')).toBeInTheDocument();
    expect(screen.getByTestId('used-by')).toHaveTextContent('Used by 1 PDF template: Loan form.');
    expect(screen.getByRole('link', { name: 'Loan form' })).toHaveAttribute('href', '/creation/blueprints/4?step=mapping');

    const fields = screen.getByRole('table', { name: 'Fields of Customer' });
    expect(within(fields).getAllByRole('columnheader').map((th) => th.textContent)).toEqual(['Field name', 'Label', 'Type', 'PII', 'Delete']);
    expect(row('full_name').getByLabelText('Field name of Full name')).toHaveValue('full_name');
    expect(row('full_name').getByText('From Customers.FullName')).toBeInTheDocument();
    expect(row('full_name').getByLabelText('PII of Full name')).toHaveValue('NAME');
    expect(within(row('dob').getByLabelText('Type of Date of birth')).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Text',
      'Long text',
      'Date',
      'Number',
      'Dropdown',
      'Checkbox',
      'Photo',
    ]);
    expect(button('Save fields')).toBeDisabled();

    expect(within(rulesTable()).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Field',
      'Required',
      'Rule',
      'Settings',
      'Source',
      'Update',
      'Delete',
    ]);
    const rows = within(rulesTable()).getAllByRole('row').slice(1);
    expect(rows.map((r) => Array.from(r.cells).slice(0, 5).map((c) => c.textContent))).toEqual([
      ['Full name', 'Yes', 'Must be filled in', 'Error', 'From Customers.FullName'],
      ['Date of birth', 'No', 'A date on or before -18y', 'Warning only · Message: “Too young”', 'Manual'],
    ]);
    expect(screen.getByRole('heading', { name: 'Add field rules' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Test a sample row' })).toBeInTheDocument();
    expectAccessible();
  });

  it('redirects /creation/data-templates/:id to ?template=:id', async () => {
    renderPage('/creation/data-templates/3');
    await loaded();
    expect(location()).toBe('/creation/data-templates?template=3');
    expect(screen.getByLabelText('Template')).toHaveValue('3');
  });

  it('Fields card: adds a dropdown field with its options, changes PII and saves the fields with the revision', async () => {
    renderPage();
    await loaded();
    await userEvent.click(button('+ Add field'));
    const label = screen.getByLabelText('Label of new_field');
    await waitFor(() => expect(label).toHaveFocus());
    await userEvent.clear(label);
    await userEvent.type(label, 'Marital status');
    // A new field's name follows its label.
    expect(screen.getByTestId('data-field-marital_status')).toBeInTheDocument();
    await userEvent.selectOptions(row('marital_status').getByLabelText('Type of Marital status'), 'Dropdown');
    // A dropdown needs options: shown once saving is tried.
    await userEvent.click(button('Save fields'));
    expect(row('marital_status').getByRole('alert')).toHaveTextContent('A dropdown needs at least one option');
    expect(saveDataTemplateFields).not.toHaveBeenCalled();

    const options = screen.getByTestId('field-options-marital_status');
    await userEvent.click(within(options).getByRole('button', { name: 'Add option' }));
    await userEvent.type(within(options).getByLabelText('Option 1'), 'Single');
    await userEvent.selectOptions(row('email').getByLabelText('PII of Email'), 'Not PII');
    expect(screen.getByText('Unsaved changes to the fields.')).toBeInTheDocument();
    expectAccessible();

    await userEvent.click(button('Save fields'));
    const body = saveDataTemplateFields.mock.calls[0][1];
    expect(body.baseRevision).toBe(4);
    expect(body.fields.map((f) => f.name)).toEqual(['full_name', 'email', 'dob', 'amount', 'photo', 'marital_status']);
    expect(body.fields[0]).toEqual({ name: 'full_name', label: 'Full name', kind: 'TEXT', piiKind: 'NAME', sourceFieldName: 'FullName' });
    expect(body.fields[1]).toMatchObject({ name: 'email', piiKind: 'NONE' });
    expect(body.fields[5]).toEqual({ name: 'marital_status', label: 'Marital status', kind: 'CHOICE', props: { options: ['Single'] } });
    expect(saveDataTemplateRules).not.toHaveBeenCalled();
    expect(await screen.findByText('Fields saved')).toBeInTheDocument();
    expect(button('Save fields')).toBeDisabled();
  });

  it('Fields card: a renamed field keeps its rules (and rules naming it)', async () => {
    getDataTemplate.mockResolvedValue(
      dto({ rules: [...RULES, { id: 3, fieldName: 'email', ruleType: 'REQUIRED_IF', config: { Field: 'dob' }, message: null, severity: 'ERROR', origin: 'MANUAL', sortOrder: 0 }] })
    );
    renderPage();
    await loaded();
    const name = row('dob').getByLabelText('Field name of Date of birth');
    await userEvent.clear(name);
    await userEvent.type(name, 'birth_date');
    await userEvent.click(button('Save fields'));
    await waitFor(() => expect(saveDataTemplateRules).toHaveBeenCalledTimes(1));
    expect(saveDataTemplateFields.mock.calls[0][1].fields[2]).toMatchObject({ name: 'birth_date', kind: 'DATE' });
    expect(lastRules().baseRevision).toBe(5);
    expect(lastRules().rules.map((r) => [r.fieldName, r.ruleType, r.config])).toEqual([
      ['full_name', 'REQUIRED', {}],
      ['birth_date', 'DATE_RANGE', { Max: '-18y' }],
      ['email', 'REQUIRED_IF', { Field: 'birth_date' }],
    ]);
  });

  it('Fields card: flags rules that no longer fit a kind change; deleting the rule in the table lets the fields save', async () => {
    renderPage();
    await loaded();
    await userEvent.selectOptions(row('dob').getByLabelText('Type of Date of birth'), 'Number');
    expect(row('dob').getByRole('alert')).toHaveTextContent('A rule does not fit a Number field — remove it in the rules table');
    await userEvent.click(button('Save fields'));
    expect(saveDataTemplateFields).not.toHaveBeenCalled();
    expect(screen.getByText('Fix the problems shown in the fields table first.')).toBeInTheDocument();

    await userEvent.click(within(rulesTable()).getByRole('button', { name: 'Delete Date range rule of Date of birth' }));
    const confirm = await screen.findByRole('dialog', { name: 'Delete this rule?' });
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete rule' }));
    await waitFor(() => expect(saveDataTemplateRules).toHaveBeenCalledTimes(1));
    expect(lastRules().baseRevision).toBe(4);
    expect(lastRules().rules.map((r) => r.ruleType)).toEqual(['REQUIRED']);
    // The kind change is still a draft, and now fits.
    expect(row('dob').getByLabelText('Type of Date of birth')).toHaveValue('NUMBER');
    expect(row('dob').queryByRole('alert')).not.toBeInTheDocument();

    await userEvent.click(button('Save fields'));
    expect(saveDataTemplateFields.mock.calls[0][1].baseRevision).toBe(5);
    expect(saveDataTemplateFields.mock.calls[0][1].fields[2]).toMatchObject({ name: 'dob', kind: 'NUMBER' });
    expect(await screen.findByText('Fields saved')).toBeInTheDocument();
  });

  it('Fields card: explains a refused field save (400) and how to get past it', async () => {
    saveDataTemplateFields.mockRejectedValue(Object.assign(new Error('Field "dob": a rule does not fit.'), { status: 400 }));
    renderPage();
    await loaded();
    await userEvent.type(row('dob').getByLabelText('Label of dob'), '!');
    await userEvent.click(button('Save fields'));
    expect(await screen.findByText(/Remove the rules that no longer fit \(in the rules table\), then save the fields again\./)).toHaveTextContent(
      'Field "dob": a rule does not fit.'
    );
  });

  it('Fields card: warns that deleting a field also removes the rules of other fields naming it', async () => {
    getDataTemplate.mockResolvedValue(
      dto({ rules: [...RULES, { id: 3, fieldName: 'full_name', ruleType: 'COMPARE', config: { Field: 'dob', Op: '!=' }, message: null, severity: 'ERROR', origin: 'MANUAL', sortOrder: 1 }] })
    );
    renderPage();
    await loaded();
    await userEvent.click(row('dob').getByRole('button', { name: 'Delete Date of birth' }));
    const confirm = await screen.findByRole('dialog', { name: 'Delete this field?' });
    expect(confirm).toHaveTextContent('Rules of other fields that compare with it or depend on it (Full name) go too.');
    expectAccessible();
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete field' }));
    expect(screen.queryByTestId('data-field-dob')).not.toBeInTheDocument();
    await userEvent.click(button('Save fields'));
    expect(saveDataTemplateFields.mock.calls[0][1].fields.map((f) => f.name)).toEqual(['full_name', 'email', 'amount', 'photo']);
    // The server drops the rules of the removed field; nothing else is sent.
    expect(saveDataTemplateRules).not.toHaveBeenCalled();
  });

  it('Rule card: offers only the rule types that fit the field’s kind', async () => {
    renderPage();
    await loaded();
    const types = () => within(screen.getByLabelText('Format rule type')).getAllByRole('option').map((o) => o.value);
    await chooseField('Photo (photo)');
    expect(types()).toEqual(['NONE']);
    await chooseField('Amount (amount)');
    expect(types()).toEqual(['NONE', 'REGEX', 'RANGE', 'LENGTH', 'ALLOWED_VALUES', 'REQUIRED_IF', 'COMPARE']);
    await chooseField('Date of birth (dob)');
    expect(types()).toEqual(['NONE', 'DATE_FORMAT', 'EXPIRY_DATE', 'ALLOWED_VALUES', 'DATE_RANGE', 'REQUIRED_IF', 'COMPARE']);
    await chooseField('Email (email)');
    expect(types()).toEqual([
      'NONE',
      'EMAIL',
      'REGEX',
      'RANGE',
      'DATE_FORMAT',
      'EXPIRY_DATE',
      'LENGTH',
      'ALLOWED_VALUES',
      'DATE_RANGE',
      'REQUIRED_IF',
      'COMPARE',
    ]);
    // A type that does not fit is reset when the field changes.
    await userEvent.selectOptions(screen.getByLabelText('Format rule type'), 'EMAIL');
    await chooseField('Amount (amount)');
    expect(screen.getByLabelText('Format rule type')).toHaveValue('NONE');
  });

  it('Rule card: marks a field Required and saves the rule set at once with the revision', async () => {
    renderPage();
    await loaded();
    await chooseField('Email (email)');
    expect(button('Save rule')).toBeDisabled();
    expect(screen.getByText('Mark the field Required or pick a format rule — there is nothing to save yet.')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Required or Optional'), 'Required');
    await userEvent.click(button('Save rule'));
    expect(lastRules()).toEqual({
      baseRevision: 4,
      rules: [
        { fieldName: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'IMPORTED', sourceTemplateId: 9, sourceFieldName: 'FullName' },
        { fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, message: 'Too young', severity: 'WARNING', origin: 'MANUAL' },
        { fieldName: 'email', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'MANUAL' },
      ],
    });
    expect(await screen.findByText('Rules saved for Email')).toBeInTheDocument();
    expect(screen.getByLabelText('Field')).toHaveValue('');
    const emailRow = within(rulesTable()).getAllByRole('row').find((r) => r.cells[0].textContent === 'Email');
    expect(emailRow).toHaveTextContent('EmailYesMust be filled in');
  });

  it('Rule card: adds an E-mail rule with a custom message as a warning', async () => {
    renderPage();
    await loaded();
    await chooseField('Email (email)');
    await userEvent.selectOptions(screen.getByLabelText('Format rule type'), 'EMAIL');
    expect(screen.getByText('The value must be a valid email address (e.g. jane@acme.com).')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/Custom message/), 'Check the e-mail address');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Warning only' }));
    await userEvent.click(button('Save rule'));
    expect(lastRules().rules.at(-1)).toEqual({
      fieldName: 'email',
      ruleType: 'EMAIL',
      config: {},
      message: 'Check the e-mail address',
      severity: 'WARNING',
      origin: 'MANUAL',
    });
  });

  it('Rule card: builds a Pattern rule with the RegexBuilder presets', async () => {
    renderPage();
    await loaded();
    await chooseField('Email (email)');
    await userEvent.selectOptions(screen.getByLabelText('Required or Optional'), 'Required');
    await userEvent.selectOptions(screen.getByLabelText('Format rule type'), 'REGEX');
    expect(button('Save rule')).toBeDisabled();
    expect(screen.getByText('Enter or build a pattern.')).toBeInTheDocument();
    expect(screen.getByRole('tablist', { name: 'Pattern mode' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('What should this field look like?'), 'Phone number');
    expect(screen.getByText(/Generated pattern/)).toBeInTheDocument();
    expectAccessible();
    await userEvent.click(button('Save rule'));
    // REQUIRED goes before the field's other rules.
    expect(lastRules().rules.slice(2).map((r) => [r.fieldName, r.ruleType, r.config])).toEqual([
      ['email', 'REQUIRED', {}],
      ['email', 'REGEX', { Pattern: '^\\+?[0-9][0-9 ()-]{5,18}[0-9]$' }],
    ]);
  });

  it('Rule card: a Number range takes Minimum and Maximum inline', async () => {
    renderPage();
    await loaded();
    await chooseField('Amount (amount)');
    await userEvent.selectOptions(screen.getByLabelText('Format rule type'), 'RANGE');
    expect(screen.getByText('Enter a minimum, a maximum or both.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Minimum'), '5');
    await userEvent.type(screen.getByLabelText('Maximum'), '1');
    expect(screen.getByText('The minimum is greater than the maximum.')).toBeInTheDocument();
    expect(button('Save rule')).toBeDisabled();
    await userEvent.clear(screen.getByLabelText('Maximum'));
    await userEvent.type(screen.getByLabelText('Maximum'), '1000');
    await userEvent.click(button('Save rule'));
    expect(lastRules().rules.at(-1)).toMatchObject({ fieldName: 'amount', ruleType: 'RANGE', config: { Min: 5, Max: 1000 } });
  });

  it('Rule card: Required if names another field', async () => {
    renderPage();
    await loaded();
    await chooseField('Email (email)');
    await userEvent.selectOptions(screen.getByLabelText('Format rule type'), 'REQUIRED_IF');
    const other = screen.getByLabelText('Other field');
    expect(within(other).queryByRole('option', { name: 'Email' })).not.toBeInTheDocument();
    await userEvent.selectOptions(other, 'Date of birth');
    await userEvent.type(screen.getByLabelText(/Only when it equals/), 'x');
    await userEvent.click(button('Save rule'));
    expect(lastRules().rules.at(-1)).toMatchObject({ fieldName: 'email', ruleType: 'REQUIRED_IF', config: { Field: 'dob', Equals: 'x' } });
    const emailRow = await waitFor(() => within(rulesTable()).getAllByRole('row').find((r) => r.cells[0].textContent === 'Email'));
    expect(emailRow.cells[2]).toHaveTextContent('Required when Date of birth is “x”');
  });

  it('Rules table: Update loads a rule into the card and saves it in place; Cancel leaves it', async () => {
    renderPage();
    await loaded();
    await userEvent.click(within(rulesTable()).getByRole('button', { name: 'Update Date range rule of Date of birth' }));
    expect(screen.getByRole('heading', { name: 'Update field rules' })).toBeInTheDocument();
    expect(screen.getByLabelText('Field')).toHaveValue('dob');
    expect(screen.getByLabelText('Required or Optional')).toHaveValue('optional');
    expect(screen.getByLabelText('Format rule type')).toHaveValue('DATE_RANGE');
    expect(screen.getByLabelText(/Latest date/)).toHaveValue('-18y');
    expect(screen.getByLabelText(/Custom message/)).toHaveValue('Too young');
    expect(screen.getByRole('checkbox', { name: 'Warning only' })).toBeChecked();
    expect(button('Cancel')).toBeInTheDocument();
    expectAccessible();

    await userEvent.clear(screen.getByLabelText(/Latest date/));
    await userEvent.type(screen.getByLabelText(/Latest date/), '-21y');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Warning only' }));
    await userEvent.click(button('Update rule'));
    expect(lastRules().rules).toEqual([
      expect.objectContaining({ fieldName: 'full_name', ruleType: 'REQUIRED' }),
      { fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-21y' }, message: 'Too young', severity: 'ERROR', origin: 'MANUAL' },
    ]);
    expect(await screen.findByText('Rules updated for Date of birth')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Add field rules' })).toBeInTheDocument();

    // Update the Required-only row: Optional removes the REQUIRED rule.
    await userEvent.click(within(rulesTable()).getByRole('button', { name: 'Update Required rule of Full name' }));
    expect(screen.getByLabelText('Required or Optional')).toHaveValue('required');
    expect(screen.getByLabelText('Format rule type')).toHaveValue('NONE');
    await userEvent.click(button('Cancel'));
    expect(screen.getByLabelText('Field')).toHaveValue('');
    await userEvent.click(within(rulesTable()).getByRole('button', { name: 'Update Required rule of Full name' }));
    await userEvent.selectOptions(screen.getByLabelText('Required or Optional'), 'Optional');
    await userEvent.click(button('Update rule'));
    expect(lastRules().rules.map((r) => [r.fieldName, r.ruleType])).toEqual([['dob', 'DATE_RANGE']]);
  });

  it('Rules table: Delete removes the rule after confirming and saves at once', async () => {
    renderPage();
    await loaded();
    await userEvent.click(within(rulesTable()).getByRole('button', { name: 'Delete Required rule of Full name' }));
    const confirm = await screen.findByRole('dialog', { name: 'Delete this rule?' });
    expect(confirm).toHaveTextContent('Delete “Must be filled in” on Full name?');
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete rule' }));
    expect(lastRules()).toEqual({
      baseRevision: 4,
      rules: [{ fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, message: 'Too young', severity: 'WARNING', origin: 'MANUAL' }],
    });
    expect(await screen.findByText('Rule on Full name deleted')).toBeInTheDocument();
    expect(within(rulesTable()).getAllByRole('row')).toHaveLength(2);
  });

  it('Test a sample row: an input per field (a photo is a checkbox), errors and warnings under each', async () => {
    testDataTemplateRules.mockResolvedValue({
      state: 'DRAFT',
      checked: true,
      errors: [{ regionKey: 'full_name', label: 'Full name', ruleId: 1, code: 'REQUIRED', message: 'Full name is required.' }],
      warnings: [{ regionKey: 'dob', label: 'Date of birth', ruleId: 2, code: 'DATE_RANGE', message: 'Too young' }],
      setupWarnings: [],
    });
    renderPage();
    await loaded();
    const panel = screen.getByTestId('rules-test');
    await userEvent.type(within(panel).getByLabelText('Sample value for Date of birth'), '2020-01-01');
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Photo: a photo is placed' }));
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testDataTemplateRules).toHaveBeenCalledWith(3, { values: { dob: '2020-01-01', photo: 'photo' } });
    expect(await within(panel).findByText('1 error, 1 warning')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Sample value for Full name')).toHaveAccessibleDescription('Full name is required.');
    expect(within(panel).getByLabelText('Sample value for Date of birth')).toHaveAccessibleDescription('Warning: Too young');
    expectAccessible();
  });

  it('offers to reload when the template was saved elsewhere (409)', async () => {
    saveDataTemplateRules.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }));
    renderPage();
    await loaded();
    await chooseField('Email (email)');
    await userEvent.selectOptions(screen.getByLabelText('Required or Optional'), 'Required');
    await userEvent.click(button('Save rule'));
    const dialog = await screen.findByRole('dialog', { name: 'This template was changed elsewhere' });
    expectAccessible();

    getDataTemplate.mockResolvedValue(dto({ revision: 6, rules: [DOB_RANGE] }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reload the latest version' }));
    expect(await screen.findByText(/your unsaved changes were discarded/)).toBeInTheDocument();
    expect(getDataTemplate).toHaveBeenCalledTimes(2);
    expect(within(rulesTable()).getAllByRole('row')).toHaveLength(2);
    expect(screen.getByLabelText('Field')).toHaveValue('');
  });

  it('creates a new template and selects it', async () => {
    createDataTemplate.mockResolvedValue({ id: 7, name: 'Loan applicant', revision: 0, fields: [], rules: [], usedBy: [] });
    getDataTemplate.mockImplementation(async (id) =>
      id === '7' ? { id: 7, name: 'Loan applicant', revision: 0, fields: [], rules: [], usedBy: [], ruleTypes: RULE_TYPES } : dto()
    );
    renderPage('/creation/data-templates');
    await userEvent.click(await screen.findByRole('button', { name: 'New template' }));
    const dialog = await screen.findByRole('dialog', { name: 'New data template' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Loan applicant');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(createDataTemplate).toHaveBeenCalledWith({ name: 'Loan applicant', description: '' });
    await waitFor(() => expect(location()).toBe('/creation/data-templates?template=7'));
    expect(await screen.findByText(/No fields yet/)).toBeInTheDocument();
    expect(screen.getByTestId('used-by')).toHaveTextContent('Used by 0 PDF templates.');
    expect(listDataTemplates).toHaveBeenCalledTimes(2);
  });

  it('renames the template', async () => {
    updateDataTemplate.mockResolvedValue({ ...dto(), name: 'Client' });
    renderPage();
    await loaded();
    await userEvent.click(button('Rename'));
    const dialog = await screen.findByRole('dialog', { name: 'Rename template' });
    const name = within(dialog).getByLabelText('Name');
    expect(name).toHaveValue('Customer');
    await userEvent.clear(name);
    await userEvent.type(name, 'Client');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(updateDataTemplate).toHaveBeenCalledWith(3, { name: 'Client', description: '' });
    expect(await screen.findByText('Renamed to Client')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Fields of Client' })).toBeInTheDocument();
  });

  it('asks before deleting, says how many PDF templates use it (409), and deletes an unused one', async () => {
    deleteDataTemplate.mockRejectedValueOnce(Object.assign(new Error('1 PDF template uses this data template.'), { status: 409 }));
    renderPage();
    await loaded();
    await userEvent.click(button('Delete'));
    let dialog = await screen.findByRole('dialog', { name: 'Delete this template?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete template' }));
    expect(deleteDataTemplate).toHaveBeenCalledWith(3);
    expect(await screen.findByText('“Customer” can’t be deleted: it is used by 1 PDF template.')).toBeInTheDocument();

    deleteDataTemplate.mockResolvedValueOnce(undefined);
    await userEvent.click(button('Delete'));
    dialog = await screen.findByRole('dialog', { name: 'Delete this template?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete template' }));
    expect(await screen.findByText('Deleted Customer')).toBeInTheDocument();
    expect(location()).toBe('/creation/data-templates');
    expect(screen.getByText('Choose a template')).toBeInTheDocument();
  });

  it('builds a new template from Document Flow templates and selects it', async () => {
    copyPreview.mockResolvedValue({
      templateId: 11,
      templateName: 'Accounts',
      fields: [{ name: 'iban', dataType: 'Text', kind: 'TEXT', piiKind: 'ACCOUNT_NUMBER', rules: [], skipped: [] }],
    });
    buildFromFlowTemplates.mockResolvedValue({ id: 8, name: 'Accounts', revision: 0, fields: [], rules: [], usedBy: [] });
    getDataTemplate.mockImplementation(async (id) =>
      id === '8'
        ? { id: 8, name: 'Accounts', revision: 0, fields: [{ name: 'iban', label: 'iban', kind: 'TEXT', sourceFieldName: 'iban', sourceTemplateName: 'Accounts', sortOrder: 0 }], rules: [], usedBy: [], ruleTypes: RULE_TYPES }
        : dto()
    );
    renderPage();
    await loaded();
    await userEvent.click(button('Build from Document Flow templates'));
    const dialog = await screen.findByRole('dialog', { name: 'Build from Document Flow templates' });
    await userEvent.click(await within(dialog).findByRole('checkbox', { name: 'Accounts' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Next: choose fields' }));
    await within(dialog).findByTestId('build-field-11-iban');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Next: preview' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Next: save' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save as a new template' }));
    expect(buildFromFlowTemplates).toHaveBeenCalledWith({ name: 'Accounts', sources: [{ templateId: 11, fields: ['iban'] }] });
    expect(await screen.findByText('Saved Accounts with 1 field')).toBeInTheDocument();
    await waitFor(() => expect(location()).toBe('/creation/data-templates?template=8'));
    expect(await screen.findByText('From Accounts.iban')).toBeInTheDocument();
  });
});
