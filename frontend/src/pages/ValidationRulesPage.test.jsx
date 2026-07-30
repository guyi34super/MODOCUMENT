import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import ValidationRulesPage from './ValidationRulesPage';

import { getTemplates } from '../api/cache';
import { getAllFields } from '../api/templates';
import {
  deleteRule,
  getRulesByTemplate,
  upsertRule,
} from '../api/validationRules';

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
}));

vi.mock('../api/templates', () => ({
  getAllFields: vi.fn(),
}));

vi.mock('../api/validationRules', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    getRulesByTemplate: vi.fn(),
    upsertRule: vi.fn().mockResolvedValue({}),
    deleteRule: vi.fn().mockResolvedValue(undefined),
    validateRows: vi.fn(),
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/validation?templateId=1']}>
      <ToastProvider>
        <ValidationRulesPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

async function openRuleForm() {
  renderPage();
  await screen.findByText('No rules for this template yet.');
  await userEvent.selectOptions(screen.getByLabelText('Field'), 'Name');
}

describe('ValidationRulesPage rule config UI', () => {
  beforeEach(() => {
    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
    getAllFields.mockResolvedValue([
      { templateFieldsId: 10, templateId: 1, fieldName: 'Name' },
    ]);
    getRulesByTemplate.mockResolvedValue([]);
    upsertRule.mockClear();
    deleteRule.mockClear();
  });

  it('renders the rule form above the rules table', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REGEX', configJson: '{"Pattern":"^\\\\d+$"}' },
    ]);
    renderPage();
    await screen.findByText('^\\d+$');
    const heading = screen.getByRole('heading', { name: 'Add field rules' });
    const table = screen.getByRole('table');
    expect(heading.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('collapses Test a row behind a toggle', async () => {
    renderPage();
    await screen.findByText('No rules for this template yet.');
    expect(screen.queryByRole('button', { name: 'Validate row' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Test a sample row…' }));
    expect(screen.getByRole('button', { name: 'Validate row' })).toBeInTheDocument();
  });

  it('shows Required/Optional and format rule type defaulting to None', async () => {
    await openRuleForm();
    expect(screen.getByLabelText('Required or Optional')).toHaveValue('optional');
    expect(screen.getByLabelText('Format rule type')).toHaveValue('NONE');
    expect(screen.queryByLabelText('Config (JSON)')).not.toBeInTheDocument();
  });

  it('shows RegexBuilder when format rule type is REGEX', async () => {
    await openRuleForm();
    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'Format pattern — match text rules'
    );
    expect(screen.getByRole('tab', { name: 'Common patterns' })).toBeInTheDocument();
  });

  it('shows Email format rule type and saves as REGEX with email pattern', async () => {
    await openRuleForm();
    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'Email address — valid email format'
    );
    expect(screen.getByText(/valid email address/i)).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Common patterns' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save rule' }));

    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REGEX', {
      Pattern: '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$',
    });
  });

  it('shows min and max inputs for RANGE format rules', async () => {
    await openRuleForm();
    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'Number range — min and max'
    );
    expect(screen.getByLabelText('Minimum')).toBeInTheDocument();
    expect(screen.getByLabelText('Maximum')).toBeInTheDocument();
  });

  it('clears RegexBuilder when format rule type returns to None', async () => {
    await openRuleForm();
    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'Format pattern — match text rules'
    );
    expect(screen.getByRole('tab', { name: 'Common patterns' })).toBeInTheDocument();

    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'None — no format check'
    );
    expect(screen.queryByRole('tab', { name: 'Common patterns' })).not.toBeInTheDocument();
  });

  it('combined save upserts REQUIRED when field is Required', async () => {
    await openRuleForm();
    await userEvent.selectOptions(screen.getByLabelText('Required or Optional'), 'Required');
    await userEvent.click(screen.getByRole('button', { name: 'Save rule' }));

    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REQUIRED', {});
  });

  it('Update button loads a REGEX rule into the form', async () => {
    getRulesByTemplate.mockResolvedValue([
      {
        fieldName: 'Name',
        ruleType: 'REGEX',
        configJson: '{"Pattern":"^\\\\d+$"}',
      },
    ]);

    renderPage();
    await screen.findByText('^\\d+$');
    await userEvent.click(screen.getByRole('button', { name: 'Update' }));

    expect(screen.getByRole('heading', { name: 'Update field rules' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Update rule' })).toBeInTheDocument();
    expect(screen.getByLabelText('Format rule type')).toHaveValue('REGEX');
  });

  it('Cancel clears edit mode', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REQUIRED', configJson: '{}' },
    ]);

    renderPage();
    await screen.findByText('Required — must not be empty');
    await userEvent.click(screen.getByRole('button', { name: 'Update' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('heading', { name: 'Add field rules' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });
});

describe('ValidationRulesPage update without duplicates', () => {
  beforeEach(() => {
    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
    getAllFields.mockResolvedValue([
      { templateFieldsId: 10, templateId: 1, fieldName: 'Name' },
      { templateFieldsId: 11, templateId: 1, fieldName: 'Email' },
    ]);
    upsertRule.mockClear();
    deleteRule.mockClear();
  });

  async function clickUpdateOnRegexRow() {
    renderPage();
    await screen.findByText('^\\d+$');
    const updateButtons = screen.getAllByRole('button', { name: 'Update' });
    await userEvent.click(updateButtons[updateButtons.length - 1]);
  }

  it('Update REGEX-only (optional) upserts format only, not REQUIRED', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REGEX', configJson: '{"Pattern":"^\\\\d+$"}' },
    ]);

    await clickUpdateOnRegexRow();
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    expect(upsertRule).toHaveBeenCalledTimes(1);
    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REGEX', { Pattern: '^\\d+$' });
    expect(deleteRule).not.toHaveBeenCalled();
  });

  it('Update REGEX on a required field re-asserts both rules', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REQUIRED', configJson: '{}' },
      { fieldName: 'Name', ruleType: 'REGEX', configJson: '{"Pattern":"^\\\\d+$"}' },
    ]);

    await clickUpdateOnRegexRow();
    expect(screen.getByLabelText('Required or Optional')).toHaveValue('required');
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    // Both writes are idempotent upserts: what the form shows is what gets
    // persisted, so a save can never silently do nothing.
    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REQUIRED', {});
    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REGEX', { Pattern: '^\\d+$' });
    expect(deleteRule).not.toHaveBeenCalled();
  });

  it('Update REGEX with field rename deletes old key then upserts new field', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REGEX', configJson: '{"Pattern":"^\\\\d+$"}' },
    ]);

    await clickUpdateOnRegexRow();
    await userEvent.selectOptions(screen.getByLabelText('Field'), 'Email');
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    expect(deleteRule).toHaveBeenCalledWith(1, 'Name', 'REGEX');
    expect(upsertRule).toHaveBeenCalledTimes(1);
    expect(upsertRule).toHaveBeenCalledWith(1, 'Email', 'REGEX', { Pattern: '^\\d+$' });
  });

  it('Update REGEX to NONE deletes old format rule without upserting', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REGEX', configJson: '{"Pattern":"^\\\\d+$"}' },
    ]);

    await clickUpdateOnRegexRow();
    await userEvent.selectOptions(
      screen.getByLabelText('Format rule type'),
      'None — no format check'
    );
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    expect(deleteRule).toHaveBeenCalledWith(1, 'Name', 'REGEX');
    expect(upsertRule).not.toHaveBeenCalled();
  });

  it('Update REQUIRED row with format NONE writes only the REQUIRED rule', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REQUIRED', configJson: '{}' },
    ]);

    renderPage();
    await screen.findByText('Required — must not be empty');
    await userEvent.click(screen.getByRole('button', { name: 'Update' }));
    expect(screen.getByLabelText('Format rule type')).toHaveValue('NONE');
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    expect(upsertRule).toHaveBeenCalledTimes(1);
    expect(upsertRule).toHaveBeenCalledWith(1, 'Name', 'REQUIRED', {});
    expect(deleteRule).not.toHaveBeenCalled();
  });

  it('Renaming the field on a REQUIRED row moves the rule to the new field', async () => {
    getRulesByTemplate.mockResolvedValue([
      { fieldName: 'Name', ruleType: 'REQUIRED', configJson: '{}' },
    ]);

    renderPage();
    await screen.findByText('Required — must not be empty');
    await userEvent.click(screen.getByRole('button', { name: 'Update' }));
    await userEvent.selectOptions(screen.getByLabelText('Field'), 'Email');
    await userEvent.click(screen.getByRole('button', { name: 'Update rule' }));

    expect(deleteRule).toHaveBeenCalledWith(1, 'Name', 'REQUIRED');
    expect(upsertRule).toHaveBeenCalledWith(1, 'Email', 'REQUIRED', {});
  });

  it('Optional with no format rule cannot be saved as a no-op', async () => {
    getRulesByTemplate.mockResolvedValue([]);

    renderPage();
    await userEvent.selectOptions(await screen.findByLabelText('Field'), 'Name');

    expect(screen.getByRole('button', { name: 'Save rule' })).toBeDisabled();
    expect(screen.getByText(/nothing to save yet/)).toBeInTheDocument();
  });
});

describe('validationRules helpers', () => {
  it('formatRuleConfig and loadRuleIntoFormState work as expected', async () => {
    const {
      formatRuleConfig,
      buildRuleConfig,
      loadRuleIntoFormState,
      isFieldRequired,
      createEditSnapshot,
      shouldSyncRequired,
      apiRuleType,
      isEmailPattern,
      EMAIL_PATTERN,
    } = await import('../api/validationRules');

    const rules = [
      { fieldName: 'Name', ruleType: 'REQUIRED', configJson: '{}' },
      { fieldName: 'Name', ruleType: 'REGEX', config: { Pattern: '^\\d+$' } },
    ];

    expect(isFieldRequired(rules, 'Name')).toBe(true);
    expect(formatRuleConfig({ ruleType: 'REGEX', config: { Pattern: '^\\d+$' } })).toBe('^\\d+$');
    expect(formatRuleConfig({ ruleType: 'RANGE', config: { Min: 0, Max: 100 } })).toBe(
      'Between 0 and 100'
    );
    expect(formatRuleConfig({ ruleType: 'REQUIRED', config: {} })).toBe('—');

    expect(loadRuleIntoFormState(rules[1], rules)).toMatchObject({
      fieldRequirement: 'required',
      ruleType: 'REGEX',
      pattern: '^\\d+$',
    });
    expect(loadRuleIntoFormState(rules[0], rules)).toMatchObject({
      fieldRequirement: 'required',
      ruleType: 'NONE',
    });

    expect(buildRuleConfig('REGEX', { pattern: '^a+$', rangeMin: '', rangeMax: '' })).toEqual({
      Pattern: '^a+$',
    });
    expect(buildRuleConfig('RANGE', { pattern: '', rangeMin: '1', rangeMax: '10' })).toEqual({
      Min: 1,
      Max: 10,
    });

    expect(createEditSnapshot(rules[1], rules)).toMatchObject({
      fieldName: 'Name',
      ruleType: 'REGEX',
      fieldRequirement: 'required',
      pattern: '^\\d+$',
    });
    expect(shouldSyncRequired('required', 'required')).toBe(false);
    expect(shouldSyncRequired('optional', 'required')).toBe(true);

    expect(apiRuleType('EMAIL')).toBe('REGEX');
    expect(apiRuleType('REGEX')).toBe('REGEX');
    expect(isEmailPattern(EMAIL_PATTERN)).toBe(true);
    expect(buildRuleConfig('EMAIL', { pattern: '', rangeMin: '', rangeMax: '' })).toEqual({
      Pattern: EMAIL_PATTERN,
    });

    const emailRule = {
      fieldName: 'Email',
      ruleType: 'REGEX',
      config: { Pattern: EMAIL_PATTERN },
    };
    expect(loadRuleIntoFormState(emailRule, [])).toMatchObject({ ruleType: 'EMAIL' });
    expect(formatRuleConfig(emailRule)).toBe('e.g. jane@acme.com');
    expect(createEditSnapshot(emailRule, [])).toMatchObject({ ruleType: 'EMAIL' });
  });
});
