import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ToastProvider } from '../../context/ToastContext';
import FieldMatcher from './FieldMatcher';
import { stubImageFetch } from '../../test/pdfTestUtils';
import { expectAccessible } from '../../test/a11y';

import { saveDataMapping } from '../../api/dataTemplates';

vi.mock('../../api/dataTemplates', async (importOriginal) => ({
  ...(await importOriginal()),
  saveDataMapping: vi.fn(),
}));

const PAGE = { index: 0, width: 600, height: 800, rotation: 0 };

const row = (regionKey, label, kind, overrides = {}) => ({
  regionKey,
  label,
  kind,
  page: 0,
  fx: 0.1,
  fy: 0.2,
  fw: 0.3,
  fh: 0.05,
  mappedField: null,
  suggestion: null,
  ...overrides,
});

const MAPPING = {
  dataTemplateId: 3,
  dataTemplateName: 'Customer',
  revision: 2,
  mappedCount: 2,
  mappableCount: 4,
  rows: [
    row('full_name', 'Full name', 'TEXT', {
      mappedField: 'full_name',
      suggestion: { field: 'full_name', score: 1, sameName: true },
    }),
    row('dob', 'Birth date', 'DATE', {
      fy: 0.4,
      mappedField: 'date_of_birth',
      suggestion: { field: 'date_of_birth', score: 0.85, sameName: false },
    }),
    row('notes', 'Notes', 'MULTILINE', { fy: 0.6, suggestion: { field: 'remarks', score: 0.72, sameName: false } }),
    row('agree', 'I agree', 'CHECKBOX', { page: 1, fy: 0.8 }),
    row('photo', 'Photo', 'IMAGE', { fy: 0.9 }),
  ],
  fields: [
    { name: 'full_name', label: 'Full name', kind: 'TEXT' },
    { name: 'date_of_birth', label: 'Date of birth', kind: 'DATE' },
    { name: 'remarks', label: 'Remarks', kind: 'MULTILINE' },
    { name: 'balance', label: 'Balance', kind: 'NUMBER' },
    { name: 'consent', label: 'Consent', kind: 'CHECKBOX' },
  ],
};

function renderMatcher(props = {}) {
  stubImageFetch();
  const onSaved = vi.fn();
  render(
    <ToastProvider>
      <FieldMatcher
        blueprintId="4"
        mapping={MAPPING}
        pages={[PAGE, { ...PAGE, index: 1 }]}
        pagePath={(i) => `/api/doccreation/blueprints/4/pages/${i}`}
        onSaved={onSaved}
        {...props}
      />
    </ToastProvider>
  );
  return onSaved;
}

const matchRow = (key) => within(screen.getByTestId(`match-row-${key}`));

describe('FieldMatcher', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    saveDataMapping.mockImplementation(async (id, mapping) => ({
      ...MAPPING,
      mappedCount: Object.keys(mapping).length,
      rows: MAPPING.rows.map((r) => ({ ...r, mappedField: mapping[r.regionKey] || null })),
    }));
  });

  it('pre-fills each row with its badge and counts the mapped fields', () => {
    renderMatcher();
    expect(screen.getByRole('heading', { name: 'Match the PDF fields to Customer' })).toBeInTheDocument();
    expect(screen.getByTestId('mapped-count')).toHaveTextContent('2 of 4 mapped');

    expect(matchRow('full_name').getByLabelText('Data field for Full name')).toHaveValue('full_name');
    expect(matchRow('full_name').getByText('same name')).toHaveClass('bg-success-bg');
    expect(matchRow('dob').getByLabelText('Data field for Birth date')).toHaveValue('date_of_birth');
    expect(matchRow('dob').getByText('suggested 0.85')).toHaveClass('bg-pill');
    expect(matchRow('notes').getByLabelText('Data field for Notes')).toHaveValue('');
    expect(matchRow('notes').getByText('not mapped')).toHaveClass('bg-amber-100');
    expect(matchRow('notes').getByText('suggestion: Remarks')).toBeInTheDocument();
    // No data field of a fitting kind: no dropdown, not counted.
    expect(matchRow('photo').queryByRole('combobox')).not.toBeInTheDocument();
    expect(matchRow('photo').getByText('No data field of a fitting kind')).toBeInTheDocument();
    expect(matchRow('dob').getByText('Date · page 1')).toBeInTheDocument();
    expectAccessible();
  });

  it('offers only the data fields whose kind fits the PDF field', () => {
    renderMatcher();
    const options = (key, name) =>
      within(matchRow(key).getByLabelText(`Data field for ${name}`))
        .getAllByRole('option')
        .map((o) => o.value);
    expect(options('full_name', 'Full name')).toEqual(['', 'full_name', 'date_of_birth', 'balance']);
    expect(options('dob', 'Birth date')).toEqual(['', 'full_name', 'date_of_birth']);
    expect(options('notes', 'Notes')).toEqual(['', 'full_name', 'remarks']);
    expect(options('agree', 'I agree')).toEqual(['', 'consent']);
  });

  it('accepts every suggestion in one click and saves the mapping', async () => {
    const onSaved = renderMatcher();
    const save = screen.getByRole('button', { name: 'Save mapping' });
    expect(save).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Accept all suggestions (1)' }));
    expect(matchRow('notes').getByLabelText('Data field for Notes')).toHaveValue('remarks');
    expect(matchRow('notes').getByText('suggested 0.72')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Accept all suggestions/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('mapped-count')).toHaveTextContent('3 of 4 mapped');

    await userEvent.selectOptions(matchRow('dob').getByLabelText('Data field for Birth date'), 'full_name');
    expect(matchRow('dob').getByText('chosen by you')).toBeInTheDocument();
    await userEvent.selectOptions(matchRow('agree').getByLabelText('Data field for I agree'), 'consent');
    expect(screen.getByTestId('mapped-count')).toHaveTextContent('4 of 4 mapped');
    await userEvent.click(save);

    expect(saveDataMapping).toHaveBeenCalledWith('4', {
      full_name: 'full_name',
      dob: 'full_name',
      notes: 'remarks',
      agree: 'consent',
    });
    expect(await screen.findByText('Mapping saved — 4 of 4 mapped')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ mappedCount: 4 }));
  });

  it('highlights the box of the focused row on a page preview', async () => {
    renderMatcher();
    const preview = screen.getByTestId('match-preview');
    // The first row until another is focused.
    expect(within(preview).getByTestId('match-highlight')).toHaveAttribute('data-key', 'full_name');

    fireEvent.focus(matchRow('dob').getByLabelText('Data field for Birth date'));
    const box = within(preview).getByTestId('match-highlight');
    expect(box).toHaveAttribute('data-key', 'dob');
    expect(box.style.top).toBe('40%');
    expect(box.style.left).toBe('10%');
    expect(within(preview).getByText('Birth date · page 1')).toBeInTheDocument();

    fireEvent.focus(matchRow('agree').getByLabelText('Data field for I agree'));
    expect(within(preview).getByText('I agree · page 2')).toBeInTheDocument();
    expect(await within(preview).findByRole('img', { name: 'Page 2 with I agree highlighted' })).toBeInTheDocument();
  });

  it('is read-only on an older version', () => {
    renderMatcher({ readOnly: true });
    expect(matchRow('full_name').getByLabelText('Data field for Full name')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save mapping' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Accept all/ })).not.toBeInTheDocument();
  });
});
