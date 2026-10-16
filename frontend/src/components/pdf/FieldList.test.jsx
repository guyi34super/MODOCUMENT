import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FieldList, { reorderPage } from './FieldList';
import { fakeDataTransfer } from '../../test/pdfTestUtils';

const field = (uid, key, label, overrides = {}) => ({
  _uid: uid,
  key,
  label,
  kind: 'TEXT',
  group: null,
  page: 0,
  fx: 0.1,
  fy: 0.1,
  fw: 0.2,
  fh: 0.03,
  sortOrder: 0,
  ...overrides,
});

const FIELDS = [
  field('a', 'full_name', 'Full name', { group: 'Applicant', sortOrder: 0, required: true }),
  field('b', 'dob', 'Date of birth', { kind: 'DATE', group: 'Applicant', sortOrder: 1 }),
  field('c', 'amount', 'Amount', { kind: 'NUMBER', group: 'Loan', sortOrder: 2 }),
  field('d', 'notes', 'Notes', { kind: 'MULTILINE', page: 1, sortOrder: 3 }),
];

function Harness({ onSelect = () => {}, rules = { dob: 2 } }) {
  const [regions, setRegions] = useState(FIELDS);
  return (
    <>
      {/* As in the editors: the list sits under a "Fields" card heading. */}
      <h2>Fields</h2>
      <FieldList
        regions={regions}
        isSelected={(uid) => uid === 'b'}
        onSelect={onSelect}
        onReorder={(page, uids) => setRegions((list) => reorderPage(list, page, uids))}
        rulesCount={(key) => rules[key] || 0}
      />
      <output data-testid="order">
        {JSON.stringify(
          [...regions].sort((x, y) => x.page - y.page || x.sortOrder - y.sortOrder).map((r) => `${r.key}:${r.sortOrder}`)
        )}
      </output>
    </>
  );
}

const order = () => JSON.parse(screen.getByTestId('order').textContent);
describe('FieldList', () => {
  it('lists the fields by page in tab order with required and rules badges', () => {
    render(<Harness />);
    const lists = screen.getAllByTestId('field-list');
    expect(lists).toHaveLength(2);
    expect(screen.getByRole('heading', { name: 'Page 2' })).toBeInTheDocument();
    const dob = screen.getByRole('button', { name: /Date of birth/, pressed: true });
    expect(dob).toHaveTextContent('2 rules');
    expect(screen.getByRole('button', { name: /Full name/, pressed: false })).toHaveTextContent('required');
  });

  it('searches by label or key and filters by kind and group', async () => {
    render(<Harness />);
    await userEvent.type(screen.getByLabelText('Search fields'), 'amo');
    expect(screen.getByText(/Showing 1 of 4 fields/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Amount/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Full name/ })).not.toBeInTheDocument();
    // Reordering waits until the list is complete again.
    expect(screen.queryByRole('button', { name: 'Move up' })).not.toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('Search fields'));
    await userEvent.type(screen.getByLabelText('Search fields'), 'dob');
    expect(screen.getByRole('button', { name: /Date of birth/ })).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Search fields'));

    await userEvent.selectOptions(screen.getByLabelText('Filter by kind'), 'MULTILINE');
    expect(screen.getByRole('button', { name: /Notes/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Amount/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Filter by kind'), '');

    await userEvent.selectOptions(screen.getByLabelText('Filter by group'), 'Applicant');
    expect(screen.getAllByRole('button', { name: /Full name|Date of birth/ })).toHaveLength(2);
    await userEvent.selectOptions(screen.getByLabelText('Filter by group'), '__none');
    expect(screen.getByRole('button', { name: /Notes/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Amount/ })).not.toBeInTheDocument();
  });

  it('reorders with the keyboard (Move up / Move down), rewriting the page tab order', async () => {
    render(<Harness />);
    const amountRow = screen.getByTestId('field-row-amount');
    const up = within(amountRow).getByRole('button', { name: 'Move up' });
    expect(up).toHaveAccessibleDescription('Amount');
    up.focus();
    await userEvent.keyboard('{Enter}');
    expect(order()).toEqual(['full_name:0', 'amount:1', 'dob:2', 'notes:3']);
    await userEvent.click(within(screen.getByTestId('field-row-full_name')).getByRole('button', { name: 'Move down' }));
    expect(order()).toEqual(['amount:0', 'full_name:1', 'dob:2', 'notes:3']);
    expect(within(screen.getByTestId('field-row-amount')).getByRole('button', { name: 'Move up' })).toBeDisabled();
    // The only field of page 2 cannot move.
    expect(within(screen.getByTestId('field-row-notes')).getByRole('button', { name: 'Move down' })).toBeDisabled();
  });

  it('reorders by drag and drop within a page only', () => {
    render(<Harness />);
    const dataTransfer = fakeDataTransfer();
    fireEvent.dragStart(screen.getByTestId('field-row-amount'), { dataTransfer });
    fireEvent.dragOver(screen.getByTestId('field-row-full_name'), { dataTransfer });
    fireEvent.drop(screen.getByTestId('field-row-full_name'), { dataTransfer });
    expect(order()).toEqual(['amount:0', 'full_name:1', 'dob:2', 'notes:3']);

    fireEvent.dragStart(screen.getByTestId('field-row-notes'), { dataTransfer });
    fireEvent.drop(screen.getByTestId('field-row-dob'), { dataTransfer });
    expect(order()).toEqual(['amount:0', 'full_name:1', 'dob:2', 'notes:3']);
  });

  it('passes the click event on so Shift-click can add to a selection', async () => {
    const onSelect = vi.fn();
    render(<Harness onSelect={onSelect} />);
    const user = userEvent.setup();
    await user.keyboard('{Shift>}');
    await user.click(screen.getByRole('button', { name: /Amount/ }));
    await user.keyboard('{/Shift}');
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ key: 'amount' }), expect.objectContaining({ shiftKey: true }));
  });

  it('rewrites only the given page, from its lowest sort order', () => {
    const regions = [field('x', 'x', 'X', { sortOrder: 5 }), field('y', 'y', 'Y', { sortOrder: 9 }), field('z', 'z', 'Z', { page: 1, sortOrder: 1 })];
    expect(reorderPage(regions, 0, ['y', 'x']).map((r) => r.sortOrder)).toEqual([6, 5, 1]);
  });
});
