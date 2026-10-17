import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { getBlueprint, saveOverlay } from '../api/doccreation';
import { getRules, importPreview, importRules, saveRules, testRules } from '../api/docRules';
import { getTemplates } from '../api/cache';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant = 'template', version } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}${version ? `&version=${version}` : ''}`,
  downloadBlueprintFile: vi.fn(),
  getBlueprint: vi.fn(),
  listBlueprintVersions: vi.fn(),
  saveOverlay: vi.fn(),
  uploadEditImage: vi.fn(),
  listBlueprintSources: vi.fn(() => Promise.resolve([])),
  addBlueprintSource: vi.fn(),
  saveBlueprintSource: vi.fn(),
  deleteBlueprintSource: vi.fn(),
}));

vi.mock('../api/docRules', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    toRuleInput: actual.toRuleInput,
    getRules: vi.fn(),
    saveRules: vi.fn(),
    testRules: vi.fn(),
    importPreview: vi.fn(),
    importRules: vi.fn(),
  };
});

vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));

// ws34: the Validation and Map fields steps read the data template and the mapping; none here.
vi.mock('../api/dataTemplates', async (importOriginal) => ({
  ...(await importOriginal()),
  getDataMapping: vi.fn(() =>
    Promise.resolve({ dataTemplateId: null, dataTemplateName: null, revision: 0, mappedCount: 0, mappableCount: 0, rows: [], fields: [] })
  ),
  listDataTemplates: vi.fn(() => Promise.resolve([])),
  setBlueprintDataTemplate: vi.fn(),
  saveDataMapping: vi.fn(),
}));
vi.mock('../api/templates', () => ({ getAllFields: vi.fn(() => Promise.resolve([])) }));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, overrides = {}) => ({
  key,
  label,
  group: 'Applicant',
  kind: 'TEXT',
  page: 0,
  fx: 0.1,
  fy: 0.2,
  fw: 0.3,
  fh: 0.02,
  fontName: 'LiberationSans',
  fontSize: 10,
  color: '#000000',
  align: 'LEFT',
  maxLines: 1,
  acroFieldName: null,
  required: false,
  sortOrder: 0,
  source: 'DETECTED',
  confidence: 0.9,
  sampleValue: null,
  props: null,
  ...overrides,
});

const blueprint = (overrides = {}) => ({
  id: 4,
  name: 'Loan form',
  status: 'READY',
  flow: 'BLANK',
  version: 2,
  currentVersion: 2,
  pages: [{ index: 0, ...PAGE }],
  regions: [
    region('full_name', 'Full name', { required: true }),
    region('dob', 'Date of birth', { kind: 'DATE', fx: 0.5, fy: 0.3, sortOrder: 1 }),
    region('email', 'E-mail', { fx: 0.1, fy: 0.5, fw: 0.2, sortOrder: 2 }),
  ],
  edits: [],
  warnings: [],
  fonts: ['LiberationSans', 'LiberationSerif'],
  ...overrides,
});

const FIELDS = [
  { key: 'full_name', label: 'Full name', kind: 'TEXT', page: 0 },
  { key: 'dob', label: 'Date of birth', kind: 'DATE', page: 0 },
  { key: 'email', label: 'E-mail', kind: 'TEXT', page: 0 },
];

const RULE_TYPES = [
  { type: 'REQUIRED', kinds: ['TEXT', 'DATE'], configKeys: [] },
  { type: 'REGEX', kinds: ['TEXT'], configKeys: ['Pattern'] },
  { type: 'EMAIL', kinds: ['TEXT'], configKeys: [] },
  { type: 'LENGTH', kinds: ['TEXT'], configKeys: ['Min', 'Max'] },
  { type: 'DATE_RANGE', kinds: ['DATE', 'TEXT'], configKeys: ['Min', 'Max'] },
  { type: 'COMPARE', kinds: ['DATE', 'TEXT'], configKeys: ['Field', 'Op'] },
];

const RULES = [
  { id: 1, regionKey: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'REGION_FLAG', sortOrder: 0, orphan: false },
  {
    id: 2,
    regionKey: 'dob',
    ruleType: 'DATE_RANGE',
    config: { Max: '-18y' },
    message: null,
    severity: 'ERROR',
    origin: 'IMPORTED',
    sourceTemplateId: 9,
    sourceTemplateName: 'Customers',
    sourceFieldName: 'date_of_birth',
    sortOrder: 0,
    orphan: false,
  },
  { id: 3, regionKey: 'old_field', ruleType: 'REQUIRED', config: {}, message: null, severity: 'WARNING', origin: 'MANUAL', sortOrder: 0, orphan: true },
];

const rulesDto = (overrides = {}) => ({ revision: 3, rules: RULES, fields: FIELDS, ruleTypes: RULE_TYPES, ...overrides });

function renderPage(path = '/creation/blueprints/4') {
  stubImageFetch();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/blueprints/:id" element={<BlueprintDetailPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const openRules = async () => {
  await userEvent.click(await screen.findByRole('tab', { name: 'Validation' }));
  return screen.findByTestId('field-rules');
};
const section = (key) => screen.getByTestId(`rules-field-${key}`);
const savedRules = () => saveRules.mock.calls.at(-1)[1];

describe('BlueprintDetailPage — Field rules tab', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    getRules.mockResolvedValue(rulesDto());
    saveRules.mockImplementation(async (id, { baseRevision, rules }) =>
      rulesDto({ revision: baseRevision + 1, rules: rules.map((r, i) => ({ id: 50 + i, orphan: false, origin: 'MANUAL', ...r })) })
    );
    getTemplates.mockResolvedValue([
      { templateId: 9, name: 'Customers' },
      { templateId: 11, name: 'Accounts' },
    ]);
  });

  it('lists the rules by field with type, summary, severity, origin and orphans', async () => {
    renderPage();
    await openRules();

    const name = section('full_name');
    expect(within(name).getByRole('heading', { name: 'Full name' })).toBeInTheDocument();
    expect(within(name).getByRole('table', { name: 'Rules of Full name' })).toHaveTextContent('Must be filled in');
    expect(within(name).getByText('From the Required flag')).toBeInTheDocument();

    const dob = section('dob');
    expect(within(dob).getByText('A date on or before -18y')).toBeInTheDocument();
    expect(within(dob).getByText('Imported from Customers.date_of_birth')).toBeInTheDocument();
    expect(within(section('email')).getByText('No rules.')).toBeInTheDocument();

    const orphan = section('old_field');
    expect(within(orphan).getByRole('note')).toHaveTextContent('no longer on the template');
    expect(within(orphan).getByText('Warning')).toBeInTheDocument();
    expect(within(orphan).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save rules' })).toBeDisabled();
    expectAccessible();
  });

  it('adds, edits and deletes rules in a draft, then saves the whole set with the base revision', async () => {
    renderPage();
    await openRules();

    // Add a LENGTH rule to E-mail.
    await userEvent.click(within(section('email')).getByRole('button', { name: 'Add rule to E-mail' }));
    let dialog = await screen.findByRole('dialog', { name: 'Add a rule — E-mail' });
    const type = within(dialog).getByLabelText('Rule type');
    // Only the types that fit a text field.
    expect(within(type).getAllByRole('option').map((o) => o.value)).toEqual([
      'REQUIRED',
      'REGEX',
      'EMAIL',
      'LENGTH',
      'DATE_RANGE',
      'COMPARE',
    ]);
    await userEvent.selectOptions(type, 'LENGTH');
    await userEvent.type(within(dialog).getByLabelText('Minimum length'), '10');
    await userEvent.type(within(dialog).getByLabelText('Maximum length'), '5');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('The minimum is greater than the maximum.');
    await userEvent.clear(within(dialog).getByLabelText('Maximum length'));
    await userEvent.type(within(dialog).getByLabelText('Maximum length'), '80');
    await userEvent.type(within(dialog).getByLabelText(/^Message/), 'Use a real address');
    await userEvent.click(within(dialog).getByLabelText('Warning'));
    expectAccessible();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(within(section('email')).getByText('Length between 10 and 80 characters')).toBeInTheDocument();
    expect(screen.getByText('Unsaved rule changes.')).toBeInTheDocument();

    // Edit the imported date range: the relative helper fills "today".
    await userEvent.click(within(section('dob')).getByRole('button', { name: 'Edit Date range rule of Date of birth' }));
    dialog = await screen.findByRole('dialog', { name: 'Edit a rule — Date of birth' });
    expect(within(dialog).getByLabelText(/^Latest date/, { selector: 'input' })).toHaveValue('-18y');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Earliest date: -18y' }));
    await userEvent.clear(within(dialog).getByLabelText(/^Latest date/, { selector: 'input' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Latest date: today' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Update rule' }));
    expect(within(section('dob')).getByText('A date on or after -18y and on or before today')).toBeInTheDocument();

    // Delete the orphan's rule.
    await userEvent.click(within(section('old_field')).getByRole('button', { name: 'Delete Required rule of old_field' }));
    expect(screen.queryByTestId('rules-field-old_field')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Save rules' }));
    expect(saveRules).toHaveBeenCalledTimes(1);
    expect(saveRules.mock.calls[0][0]).toBe('4');
    expect(savedRules().baseRevision).toBe(3);
    expect(savedRules().rules).toEqual([
      { regionKey: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'REGION_FLAG' },
      {
        regionKey: 'dob',
        ruleType: 'DATE_RANGE',
        config: { Min: '-18y', Max: 'today' },
        message: null,
        severity: 'ERROR',
        origin: 'IMPORTED',
        sourceTemplateId: 9,
        sourceFieldName: 'date_of_birth',
      },
      {
        regionKey: 'email',
        ruleType: 'LENGTH',
        config: { Min: 10, Max: 80 },
        message: 'Use a real address',
        severity: 'WARNING',
        origin: 'MANUAL',
      },
    ]);
    expect(await screen.findByText('Field rules saved')).toBeInTheDocument();
    expect(screen.getByText('revision 4')).toBeInTheDocument();
  });

  it('builds a pattern rule with the regex builder and compares with another field', async () => {
    renderPage();
    await openRules();

    await userEvent.click(within(section('full_name')).getByRole('button', { name: 'Add rule to Full name' }));
    let dialog = await screen.findByRole('dialog', { name: 'Add a rule — Full name' });
    await userEvent.selectOptions(within(dialog).getByLabelText('Rule type'), 'REGEX');
    await userEvent.click(within(dialog).getByRole('tab', { name: 'Advanced (raw)' }));
    fireEvent.change(within(dialog).getByLabelText(/^Pattern/, { selector: 'input' }), { target: { value: '^[A-Z]' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(within(section('full_name')).getByText('Matches ^[A-Z]')).toBeInTheDocument();

    await userEvent.click(within(section('dob')).getByRole('button', { name: 'Add rule to Date of birth' }));
    dialog = await screen.findByRole('dialog', { name: 'Add a rule — Date of birth' });
    await userEvent.selectOptions(within(dialog).getByLabelText('Rule type'), 'COMPARE');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Choose the other field.');
    const other = within(dialog).getByLabelText('Other field');
    expect(within(other).queryByRole('option', { name: 'Date of birth' })).not.toBeInTheDocument();
    await userEvent.selectOptions(other, 'full_name');
    await userEvent.selectOptions(within(dialog).getByLabelText('This value must be'), '<');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(within(section('dob')).getByText('Before / less than Full name')).toBeInTheDocument();
  });

  it('offers to reload when the rules were saved elsewhere (409)', async () => {
    saveRules.mockRejectedValue(Object.assign(new Error('Stale revision'), { status: 409 }));
    renderPage();
    await openRules();

    await userEvent.click(within(section('old_field')).getByRole('button', { name: 'Delete Required rule of old_field' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save rules' }));
    const dialog = await screen.findByRole('dialog', { name: 'The field rules were changed elsewhere' });
    getRules.mockResolvedValue(rulesDto({ revision: 5 }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reload the rules' }));

    expect(await screen.findByText('revision 5')).toBeInTheDocument();
    expect(getRules).toHaveBeenCalledTimes(2);
    // The draft is replaced by the reloaded rules.
    expect(screen.getByTestId('rules-field-old_field')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save rules' })).toBeDisabled();
  });

  it('imports from a flow template: preview, change a match, skipped rules, Replace mode', async () => {
    importPreview.mockResolvedValue({
      source: 'TEMPLATE',
      templateId: 9,
      templateName: 'Customers',
      fields: [
        {
          regionKey: 'full_name',
          label: 'Full name',
          fieldName: 'full_name',
          score: 1,
          sameName: true,
          rules: [{ ruleType: 'REQUIRED', config: {}, message: null }],
          skipped: [{ flowRuleType: 'CUSTOM', reason: "can't be imported" }],
        },
        {
          regionKey: 'dob',
          label: 'Date of birth',
          fieldName: 'birth_date',
          score: 0.82,
          sameName: false,
          rules: [{ ruleType: 'DATE_FORMAT', config: { Formats: ['yyyy-MM-dd'] }, message: null }],
          skipped: [],
        },
        { regionKey: 'email', label: 'E-mail', fieldName: null, score: null, sameName: false, rules: [], skipped: [] },
      ],
      templateFields: ['full_name', 'birth_date', 'date_of_birth', 'mail'],
    });
    importRules.mockResolvedValue(rulesDto({ revision: 4 }));
    renderPage();
    await openRules();

    await userEvent.click(screen.getByRole('button', { name: 'Import rules' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import rules' });
    await userEvent.click(within(dialog).getByLabelText('A flow template'));
    const template = within(dialog).getByLabelText('Flow template');
    await waitFor(() => expect(template).toBeEnabled());
    await userEvent.selectOptions(template, '9');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Show what would be imported' }));
    expect(importPreview).toHaveBeenCalledWith('4', 9);

    const nameRow = await within(dialog).findByTestId('import-row-full_name');
    expect(within(nameRow).getByText('same name')).toBeInTheDocument();
    expect(within(nameRow).getByText(/Must be filled in/)).toBeInTheDocument();
    expect(within(nameRow).getByText("Skipped CUSTOM: can't be imported")).toBeInTheDocument();
    const dobRow = within(dialog).getByTestId('import-row-dob');
    expect(within(dobRow).getByText('match 82 %')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Import the rules of E-mail')).not.toBeChecked();

    await userEvent.selectOptions(within(dobRow).getByLabelText('Template field for Date of birth'), 'date_of_birth');
    expect(within(dobRow).getByText('chosen by you')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByLabelText(/^Replace/));
    expectAccessible();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import rules of 2 fields' }));

    expect(importRules).toHaveBeenCalledWith('4', {
      baseRevision: 3,
      templateId: 9,
      mapping: { full_name: 'full_name', dob: 'date_of_birth' },
      mode: 'REPLACE',
    });
    expect(await screen.findByText('Rules imported')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('revision 4')).toBeInTheDocument();
  });

  it('imports from the data sources with Merge by default', async () => {
    importPreview.mockResolvedValue({
      source: 'DATA_SOURCES',
      fields: [
        {
          regionKey: 'email',
          label: 'E-mail',
          fieldName: 'mail',
          templateId: 9,
          templateName: 'Customers',
          score: 1,
          sameName: false,
          rules: [{ ruleType: 'EMAIL', config: {}, message: null }],
          skipped: [],
        },
      ],
      templateFields: [],
    });
    importRules.mockResolvedValue(rulesDto({ revision: 4 }));
    renderPage();
    await openRules();

    await userEvent.click(screen.getByRole('button', { name: 'Import rules' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import rules' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Show what would be imported' }));
    expect(importPreview).toHaveBeenCalledWith('4', undefined);
    expect(await within(dialog).findByText('from Customers')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import rules of 1 field' }));
    expect(importRules).toHaveBeenCalledWith('4', { baseRevision: 3, mapping: { email: 'mail' }, mode: 'MERGE' });
  });

  it('tests sample values against the rules, including unsaved ones', async () => {
    testRules.mockResolvedValue({
      state: 'DRAFT',
      checked: true,
      errors: [{ regionKey: 'full_name', label: 'Full name', ruleId: 1, code: 'REQUIRED', message: 'Full name is required.' }],
      warnings: [{ regionKey: 'dob', label: 'Date of birth', ruleId: 2, code: 'DATE_RANGE', message: 'Date of birth looks odd.' }],
      setupWarnings: [{ ruleId: 9, regionKey: 'photo', message: 'RANGE does not apply to a photo field; skipped.' }],
    });
    renderPage();
    await openRules();

    await userEvent.click(screen.getByRole('button', { name: 'Test with sample values' }));
    const panel = screen.getByTestId('rules-test');
    await userEvent.type(within(panel).getByLabelText('Sample value for E-mail'), 'jane@');
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testRules).toHaveBeenCalledWith('4', { values: { email: 'jane@' }, rules: undefined });
    expect(await within(panel).findByText('1 error, 1 warning')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Sample value for Full name')).toHaveAccessibleDescription('Full name is required.');
    expect(within(panel).getByLabelText('Sample value for Full name')).toHaveAttribute('aria-invalid', 'true');
    expect(within(panel).getByText('Warning: Date of birth looks odd.')).toBeInTheDocument();
    expect(within(panel).getByText('RANGE does not apply to a photo field; skipped.')).toBeInTheDocument();

    // With an unsaved change, the draft rules are tested.
    await userEvent.click(within(section('old_field')).getByRole('button', { name: 'Delete Required rule of old_field' }));
    await userEvent.click(within(panel).getByRole('button', { name: 'Run the rules' }));
    expect(testRules.mock.calls.at(-1)[1].rules).toHaveLength(2);
    expectAccessible();
  });
});

describe('BlueprintDetailPage — editor tools with rules', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    getRules.mockResolvedValue(rulesDto());
    saveRules.mockImplementation(async (id, { baseRevision, rules }) =>
      rulesDto({ revision: baseRevision + 1, rules: rules.map((r, i) => ({ id: 50 + i, orphan: false, origin: 'MANUAL', ...r })) })
    );
    saveOverlay.mockImplementation(async (id, body) => blueprint({ version: 3, currentVersion: 3, regions: body.regions }));
  });

  it('toggles Required as a rule and jumps to the field rules from the panel', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('region-dob'));
    const panel = screen.getByTestId('region-properties');
    expect(within(panel).getByText('1 rule')).toBeInTheDocument();
    expect(screen.getByTestId('field-row-dob')).toHaveTextContent('1 rule');

    await userEvent.click(within(panel).getByLabelText('Required'));
    expect(saveRules).toHaveBeenCalledTimes(1);
    expect(savedRules().baseRevision).toBe(3);
    expect(savedRules().rules.filter((r) => r.regionKey === 'dob').map((r) => r.ruleType)).toEqual(['REQUIRED', 'DATE_RANGE']);
    expect(await screen.findByText('“Date of birth” is now required')).toBeInTheDocument();
    expect(within(screen.getByTestId('region-properties')).getByLabelText('Required')).toBeChecked();
    expect(screen.getByTestId('field-row-dob')).toHaveTextContent('required');
    // Mirroring the rule is not an unsaved template change.
    expect(screen.queryByText(/Unsaved changes/)).not.toBeInTheDocument();

    await userEvent.click(within(screen.getByTestId('region-properties')).getByRole('button', { name: 'Edit rules' }));
    expect(screen.getByRole('tab', { name: 'Validation' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(within(section('dob')).getByRole('button', { name: /Show on the page/ })).toHaveFocus());
  });

  it('keeps Required of a new field until the template is saved, then writes the rule', async () => {
    renderPage();
    await screen.findByTestId('region-dob');
    await userEvent.click(screen.getByRole('button', { name: 'Add field' }));
    fireEvent.keyDown(screen.getByTestId('draw-layer'), { key: 'Enter' });
    await userEvent.type(screen.getByLabelText('Label'), 'Employer');
    const panel = screen.getByTestId('region-properties');
    expect(within(panel).getByText(/Required becomes a rule when you save/)).toBeInTheDocument();
    await userEvent.click(within(panel).getByLabelText('Required'));
    expect(saveRules).not.toHaveBeenCalled();

    getRules.mockResolvedValue(rulesDto({ fields: [...FIELDS, { key: 'employer', label: 'Employer', kind: 'TEXT', page: 0 }] }));
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(saveOverlay.mock.calls[0][1].regions[3]).toMatchObject({ key: 'employer', required: true });
    await waitFor(() => expect(saveRules).toHaveBeenCalledTimes(1));
    expect(savedRules().rules.at(-1)).toEqual({
      regionKey: 'employer',
      ruleType: 'REQUIRED',
      config: {},
      message: null,
      severity: 'ERROR',
      origin: 'MANUAL',
    });
  });

  it('sends field properties with the overlay', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('region-full_name'));
    await userEvent.type(screen.getByLabelText('Placeholder'), 'As on the ID');
    await userEvent.selectOptions(screen.getByLabelText('Font'), 'LiberationSerif');
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(saveOverlay.mock.calls[0][1].regions[0]).toMatchObject({
      key: 'full_name',
      fontName: 'LiberationSerif',
      props: { placeholder: 'As on the ID' },
    });
    expect(saveOverlay.mock.calls[0][1].regions[1].props).toBeNull();
  });

  it('multi-selects with Shift-click, aligns from the toolbar, undoes with Ctrl+Z and edits shared properties', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('region-full_name'));
    const toolbar = screen.getByRole('toolbar', { name: 'Arrange fields' });
    expect(within(toolbar).getByRole('button', { name: 'Align left' })).toBeDisabled();
    const user = userEvent.setup();
    await user.keyboard('{Shift>}');
    await user.click(screen.getByTestId('region-dob'));
    await user.click(screen.getByTestId('region-email'));
    await user.keyboard('{/Shift}');
    expect(screen.getByText('3 fields selected', { selector: '[data-testid="arrange-toolbar"] *' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Several fields' })).toBeInTheDocument();

    await user.click(within(toolbar).getByRole('button', { name: 'Align left' }));
    expect(screen.getByTestId('region-dob').style.left).toBe('10%');
    expect(within(toolbar).getByRole('button', { name: 'Distribute vertically' })).toBeEnabled();
    fireEvent.keyDown(document.body, { key: 'z', ctrlKey: true });
    expect(screen.getByTestId('region-dob').style.left).toBe('50%');
    fireEvent.keyDown(document.body, { key: 'y', ctrlKey: true });
    expect(screen.getByTestId('region-dob').style.left).toBe('10%');

    await user.selectOptions(screen.getByLabelText('Align'), 'RIGHT');
    await user.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(saveOverlay.mock.calls[0][1].regions.map((r) => [r.fx, r.align])).toEqual([
      [0.1, 'RIGHT'],
      [0.1, 'RIGHT'],
      [0.1, 'RIGHT'],
    ]);
  });

  it('selects with a marquee, duplicates with Ctrl+D and deletes after confirming (undoable)', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');
    const marquee = screen.getByTestId('marquee-layer');
    fireEvent.pointerDown(marquee, { clientX: 50, clientY: 150, button: 0 });
    fireEvent.pointerMove(marquee, { clientX: 450, clientY: 350 });
    fireEvent.pointerUp(marquee);
    expect(screen.getByTestId('region-full_name')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('region-email')).toHaveAttribute('aria-pressed', 'false');

    fireEvent.keyDown(document.body, { key: 'd', ctrlKey: true });
    const copy = screen.getByTestId('region-full_name_2');
    expect(copy).toHaveAttribute('aria-pressed', 'true');
    expect(copy.style.left).toBe('12%');

    fireEvent.keyDown(copy, { key: 'Delete' });
    const confirm = await screen.findByRole('dialog', { name: 'Delete this field?' });
    expect(within(confirm).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete field' }));
    expect(screen.queryByTestId('region-full_name_2')).not.toBeInTheDocument();
    await userEvent.click(within(screen.getByRole('toolbar', { name: 'Arrange fields' })).getByRole('button', { name: 'Undo' }));
    expect(screen.getByTestId('region-full_name_2')).toBeInTheDocument();
  });

  it('nudges finely with Alt and coarsely with Shift, snaps with guides and zooms the page', async () => {
    renderPage();
    const box = await screen.findByTestId('region-full_name');
    fireEvent.keyDown(box, { key: 'ArrowRight', altKey: true });
    expect(parseFloat(screen.getByTestId('region-full_name').style.left)).toBeCloseTo(10.1);
    fireEvent.keyDown(screen.getByTestId('region-full_name'), { key: 'ArrowDown', shiftKey: true });
    expect(screen.getByTestId('region-full_name').style.top).toBe('22.5%');

    await userEvent.click(screen.getByRole('button', { name: 'Snap' }));
    expect(screen.getByRole('button', { name: 'Snap' })).toHaveAttribute('aria-pressed', 'true');
    const email = screen.getByTestId('region-email');
    // Drag E-mail so its left edge comes within 0.6 % of Date of birth's (50 %).
    fireEvent.pointerDown(email, { clientX: 150, clientY: 505, button: 0 });
    fireEvent.pointerMove(email, { clientX: 547, clientY: 505 });
    expect(screen.getByTestId('guide-x')).toBeInTheDocument();
    fireEvent.pointerUp(email);
    expect(screen.queryByTestId('guide-x')).not.toBeInTheDocument();
    expect(screen.getByTestId('region-email').style.left).toBe('50%');

    const zoom = screen.getByLabelText('Zoom');
    await userEvent.selectOptions(zoom, '1.5');
    expect(screen.getByTestId('page-view').style.width).toBe('150%');
    await userEvent.click(screen.getByRole('button', { name: 'Zoom out' }));
    expect(screen.getByTestId('page-view').style.width).toBe('125%');
  });

  it('opens the rules tab on a field from the URL (the builder link)', async () => {
    renderPage('/creation/blueprints/4?tab=rules&field=dob');
    expect(await screen.findByTestId('field-rules')).toBeInTheDocument();
    await waitFor(() => expect(within(section('dob')).getByRole('button', { name: /Show on the page/ })).toHaveFocus());
    await userEvent.click(within(section('dob')).getByRole('button', { name: /Show on the page/ }));
    expect(screen.getByRole('tab', { name: 'Upload' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.getByTestId('region-dob')).toHaveFocus());
  });
});

describe('BlueprintDetailPage — deep links and a draft template', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    getRules.mockResolvedValue(rulesDto());
    getTemplates.mockResolvedValue([]);
  });

  it.each([
    ['rules', 'Validation', 'field-rules'],
    ['sources', 'Map fields', 'data-sources'],
  ])('selects the step of the older ?tab=%s link on a confirmed template', async (tab, name, testId) => {
    renderPage(`/creation/blueprints/4?tab=${tab}`);
    expect(await screen.findByTestId(testId)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name })).toHaveAttribute('aria-selected', 'true');
    expectAccessible();
  });

  it('shows the steps on a draft, where Validation and Map fields ask to confirm the template first', async () => {
    getBlueprint.mockResolvedValue(blueprint({ status: 'DRAFT', version: 1, currentVersion: 1 }));
    saveOverlay.mockImplementation(async (id, body) => blueprint({ version: 1, currentVersion: 1, regions: body.regions }));
    renderPage('/creation/blueprints/4?tab=rules');

    const rulesTab = await screen.findByRole('tab', { name: 'Validation' });
    expect(rulesTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('confirm-first')).toHaveTextContent(
      'Confirm the template first to add data sources and field rules.'
    );
    // Only a confirmed template loads its rules.
    expect(getRules).not.toHaveBeenCalled();
    expectAccessible();

    await userEvent.click(screen.getByRole('tab', { name: 'Map fields' }));
    expect(screen.getByTestId('confirm-first')).toHaveTextContent('Confirm the template first');
    await userEvent.click(screen.getByRole('tab', { name: 'Upload' }));
    expect(screen.queryByTestId('confirm-first')).not.toBeInTheDocument();
    expect(screen.getByTestId('region-dob')).toBeInTheDocument();

    // The callout's button is the template's own Confirm action.
    await userEvent.click(screen.getByRole('tab', { name: 'Validation' }));
    await userEvent.click(within(screen.getByTestId('confirm-first')).getByRole('button', { name: 'Confirm template' }));
    expect(saveOverlay).toHaveBeenCalledTimes(1);
    expect(saveOverlay.mock.calls[0][1]).toMatchObject({ confirm: true, baseVersion: 1 });
    expect(saveOverlay.mock.calls[0][1].regions.map((r) => r.key)).toEqual(['full_name', 'dob', 'email']);
    // Confirmed: the Validation step now shows (and loads) the rules.
    expect(await screen.findByTestId('field-rules')).toBeInTheDocument();
    expect(getRules).toHaveBeenCalledWith('4');
  });
});
