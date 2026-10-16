import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ValidationPanel from './ValidationPanel';
import { expectAccessible } from '../../test/a11y';

const regions = [
  { key: 'full_name', label: 'Full name', kind: 'TEXT', page: 0 },
  { key: 'dob', label: 'Date of birth', kind: 'DATE', page: 0 },
  { key: 'amount', label: 'Amount', kind: 'NUMBER', page: 0 },
  { key: 'notes', label: '', kind: 'MULTILINE', page: 1 },
];

const NOT_CHECKED = {
  state: 'DRAFT',
  validatedAt: null,
  rulesRevision: 3,
  rulesChangedSinceValidation: false,
  errors: [],
  warnings: [],
  setupWarnings: [],
  checked: false,
};

const FIELD_CHECKS = {
  full_name: {
    rules: [
      { id: 1, ruleType: 'REQUIRED', summary: 'Required', severity: 'ERROR', origin: 'REGION_FLAG' },
      { id: 2, ruleType: 'LENGTH', summary: '1–40 characters', severity: 'WARNING', origin: 'MANUAL' },
    ],
    builtIn: ['At most 40 characters'],
    mappedFrom: [{ templateId: 9, templateName: 'Customers', fieldName: 'full_name', role: 'PRIMARY' }],
  },
  dob: {
    rules: [
      {
        id: 3,
        ruleType: 'DATE_RANGE',
        summary: 'On or before 18 years ago',
        severity: 'ERROR',
        origin: 'IMPORTED',
        sourceTemplateName: 'Customers',
        sourceFieldName: 'date_of_birth',
      },
    ],
    builtIn: ['A date'],
    mappedFrom: [{ templateId: 11, templateName: 'Accounts', fieldName: 'birth_date', role: 'LINKED' }],
  },
  amount: { rules: [], builtIn: ['A number'], mappedFrom: [] },
  notes: { rules: [], builtIn: [], mappedFrom: [] },
};

const doc = (overrides = {}) => ({
  id: 5,
  status: 'DRAFT',
  blueprintId: 1,
  validation: NOT_CHECKED,
  fieldChecks: FIELD_CHECKS,
  rulesRevision: 3,
  canEditRules: false,
  ...overrides,
});

function renderPanel(props = {}) {
  const onGoTo = vi.fn();
  const d = props.doc || doc();
  render(
    <MemoryRouter>
      <ValidationPanel
        doc={d}
        status={props.status || d.status}
        locked={props.locked || false}
        regions={regions}
        issues={props.issues || []}
        onGoTo={onGoTo}
        requiredFilled={props.requiredFilled ?? 1}
        requiredTotal={props.requiredTotal ?? 2}
      />
    </MemoryRouter>
  );
  return { onGoTo, panel: within(screen.getByRole('region', { name: 'Checks' })) };
}

const state = () => screen.getByTestId('checks-state');

describe('ValidationPanel', () => {
  it('says a document that was never checked is not checked yet, with the counts', () => {
    renderPanel();
    expect(screen.getByRole('heading', { name: 'Checks', level: 2 })).toBeInTheDocument();
    expect(state()).toHaveTextContent('Not checked yet — press Save to check this document.');
    expect(state()).toHaveClass('text-muted');
    expect(screen.getByTestId('required-progress')).toHaveTextContent(
      '1 of 2 required fields filled · 3 rules on this template'
    );
    expect(screen.queryByTestId('issues-panel')).not.toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
    expectAccessible();
  });

  it('shows the date a validated document was checked, in green', () => {
    const validatedAt = '2026-10-16T10:00:00';
    renderPanel({
      doc: doc({ status: 'VALIDATED', validation: { ...NOT_CHECKED, state: 'VALIDATED', validatedAt, checked: true } }),
    });
    expect(state()).toHaveTextContent(`Validated on ${new Date(validatedAt).toLocaleString()}`);
    expect(state()).toHaveClass('text-success-ink');
  });

  it.each(['GENERATED', 'SUBMITTED', 'RELEASED'])('says a %s document that passed is generated and validated', (status) => {
    renderPanel({
      doc: doc({ status, validation: { ...NOT_CHECKED, state: 'VALIDATED', validatedAt: '2026-10-16T10:00:00', checked: true } }),
      locked: status !== 'GENERATED',
    });
    expect(state()).toHaveTextContent('Generated · validated');
    expect(state()).toHaveClass('text-success-ink');
  });

  it('counts the errors and warnings in red, lists the issues and notes changed rules', async () => {
    const validation = {
      ...NOT_CHECKED,
      checked: true,
      rulesChangedSinceValidation: true,
      errors: [
        { regionKey: 'dob', label: 'Date of birth', message: 'Date of birth must be on or before 18 years ago.' },
        { regionKey: 'full_name', label: 'Full name', message: 'Full name is required.' },
      ],
      warnings: [{ regionKey: 'full_name', label: 'Full name', message: 'Full name looks short.' }],
    };
    const issues = [
      { id: 'e0', level: 'error', regionKey: 'dob', label: 'Date of birth', message: validation.errors[0].message },
      { id: 'e1', level: 'error', regionKey: 'full_name', label: 'Full name', message: validation.errors[1].message },
      { id: 'w0', level: 'warning', regionKey: 'full_name', label: 'Full name', message: validation.warnings[0].message },
    ];
    const { onGoTo } = renderPanel({ doc: doc({ validation }), issues });

    expect(state()).toHaveTextContent('2 errors, 1 warning — fix them and press Save again.');
    expect(state()).toHaveClass('text-danger-ink');
    expect(screen.getByRole('note')).toHaveTextContent(
      'Rules changed since this document was validated — save again to re-check.'
    );
    const list = within(screen.getByTestId('issues-panel'));
    expect(list.getByRole('heading', { name: 'Issues (2 errors, 1 warning)' })).toBeInTheDocument();
    expect(list.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'ErrorDate of birth must be on or before 18 years ago.Go to field',
      'ErrorFull name is required.Go to field',
      'WarningFull name looks short.Go to field',
    ]);
    await userEvent.click(list.getByRole('button', { name: 'Go to field: Date of birth' }));
    expect(onGoTo).toHaveBeenLastCalledWith('dob');
    await userEvent.click(list.getByRole('button', { name: 'Next issue' }));
    expect(onGoTo).toHaveBeenLastCalledWith('dob');
    await userEvent.click(list.getByRole('button', { name: 'Next issue' }));
    expect(onGoTo).toHaveBeenLastCalledWith('full_name');
    expectAccessible();
  });

  it('hides the changed-rules note on a locked document', () => {
    renderPanel({
      doc: doc({ status: 'RELEASED', validation: { ...NOT_CHECKED, checked: true, rulesChangedSinceValidation: true } }),
      locked: true,
    });
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });

  it('lists the rules of each field: summaries, warning style, imported source, built-in checks and mapping', async () => {
    const { onGoTo } = renderPanel();
    const toggle = screen.getByRole('button', { name: 'Rules for each field' });
    // Open by default: the template has rules.
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByText('This template has no field rules yet.')).not.toBeInTheDocument();

    const name = within(screen.getByTestId('field-rules-full_name'));
    const nameChecks = within(name.getByRole('list', { name: 'Checks' }));
    expect(nameChecks.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Required',
      '1–40 characters (warning)',
      'At most 40 characters',
    ]);
    // WARNING rules look like warnings; built-in checks are neutral.
    expect(name.getByText('1–40 characters').closest('span')).toHaveClass('bg-amber-100');
    expect(name.getByText('Required')).toHaveClass('bg-pill');
    expect(name.getByText('At most 40 characters')).toHaveClass('bg-neutral-100');
    expect(name.getByText('Filled from Customers.full_name')).toBeInTheDocument();

    const dob = within(screen.getByTestId('field-rules-dob'));
    expect(dob.getByText(/On or before 18 years ago/)).toHaveTextContent('On or before 18 years ago · from Customers.date_of_birth');
    expect(dob.getByText('A date')).toBeInTheDocument();
    expect(dob.getByText('Filled from Accounts.birth_date (linked)')).toBeInTheDocument();

    // A field with built-in checks only is listed; one without any is not.
    expect(within(screen.getByTestId('field-rules-amount')).getByText('A number')).toBeInTheDocument();
    expect(screen.queryByTestId('field-rules-notes')).not.toBeInTheDocument();

    await userEvent.click(dob.getByRole('button', { name: 'Date of birth' }));
    expect(onGoTo).toHaveBeenCalledWith('dob');

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('field-rules-list')).not.toBeInTheDocument();
    expectAccessible();
  });

  it('starts closed on a template without rules, and says so', async () => {
    renderPanel({ doc: doc({ fieldChecks: { amount: FIELD_CHECKS.amount } }), requiredTotal: 0, requiredFilled: 0 });
    const toggle = screen.getByRole('button', { name: 'Rules for each field' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('This template has no field rules yet.')).toBeInTheDocument();
    expect(screen.getByTestId('required-progress')).toHaveTextContent('No required fields · 0 rules on this template');
    await userEvent.click(toggle);
    expect(within(screen.getByTestId('field-rules-amount')).getByText('A number')).toBeInTheDocument();
    expectAccessible();
  });

  it('copes with a document without field checks', () => {
    renderPanel({ doc: doc({ fieldChecks: undefined }) });
    expect(screen.getByText('This template has no field rules yet.')).toBeInTheDocument();
    expect(screen.getByTestId('required-progress')).toHaveTextContent('0 rules on this template');
  });

  it('links to the Field rules and Data sources tabs only for who may edit the rules', () => {
    const { panel } = renderPanel({ doc: doc({ canEditRules: true }) });
    expect(panel.getByRole('link', { name: 'Edit rules' })).toHaveAttribute('href', '/creation/blueprints/1?tab=rules');
    expect(panel.getByRole('link', { name: 'Map from data sources' })).toHaveAttribute(
      'href',
      '/creation/blueprints/1?tab=sources'
    );
  });

  it('shows no links without canEditRules', () => {
    const { panel } = renderPanel();
    expect(panel.queryByRole('link')).not.toBeInTheDocument();
  });

  it('lists the template rule problems behind a disclosure, only when there are any', async () => {
    renderPanel({
      doc: doc({
        validation: {
          ...NOT_CHECKED,
          setupWarnings: [
            { ruleId: 40, regionKey: 'dob', message: 'RANGE does not apply to a date field; skipped.' },
            { ruleId: 41, regionKey: null, message: 'A rule names a field that is gone.' },
          ],
        },
      }),
    });
    const toggle = screen.getByRole('button', { name: 'Template rule problems (2)' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(screen.getByTestId('setup-warnings')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Date of birth: RANGE does not apply to a date field; skipped.',
      'A rule names a field that is gone.',
    ]);
    expectAccessible();
  });

  it('has no rule problems part without setup warnings', () => {
    renderPanel();
    expect(screen.queryByRole('button', { name: /Template rule problems/ })).not.toBeInTheDocument();
  });
});
