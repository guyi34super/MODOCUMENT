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

describe('validationRules helpers', () => {
  it('formatRuleConfig and loadRuleIntoFormState work as expected', async () => {
    const {
      formatRuleConfig,
      buildRuleConfig,
      loadRuleIntoFormState,
      isFieldRequired,
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
  });
});
