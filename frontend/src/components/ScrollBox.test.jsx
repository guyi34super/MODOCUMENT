import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ScrollBox from './ScrollBox';
import DataTable from './DataTable';
import Modal from './Modal';
import RuleTable from './rules/RuleTable';
import RuleDialog from './rules/RuleDialog';
import ValidationPanel from './doc/ValidationPanel';
import BuildFromFlowDialog from './dataTemplates/BuildFromFlowDialog';
import { expectAccessible } from '../test/a11y';
import { getTemplates } from '../api/cache';

vi.mock('../api/dataTemplates', () => ({
  copyPreview: vi.fn(),
  buildFromFlowTemplates: vi.fn(),
  addFromFlowTemplates: vi.fn(),
}));
vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));

const table = (
  <table>
    <caption>People</caption>
    <thead>
      <tr>
        <th>Name</th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <td>Jane</td>
      </tr>
    </tbody>
  </table>
);

describe('ScrollBox', () => {
  it('is a named, focusable region that scrolls inside itself, with a visible focus ring', async () => {
    render(
      <>
        <button type="button">Before</button>
        <ScrollBox label="People list" maxHeight="max-h-60">
          {table}
        </ScrollBox>
      </>
    );
    const box = screen.getByRole('region', { name: 'People list' });
    expect(box).toHaveAttribute('tabindex', '0');
    expect(box).toHaveClass('overflow-auto', 'max-h-60', 'focus-visible:ring-2', 'max-w-full', 'min-w-0');
    expect(box).not.toHaveAttribute('data-sticky-header');
    expect(within(box).getByRole('table', { name: 'People' })).toBeInTheDocument();

    await userEvent.tab();
    await userEvent.tab();
    expect(box).toHaveFocus();
    expectAccessible();
  });

  it('pins a table header with stickyHeader (sticky top-0 with a background on thead th)', () => {
    render(
      <ScrollBox label="People list" stickyHeader>
        {table}
      </ScrollBox>
    );
    const box = screen.getByRole('region', { name: 'People list' });
    expect(box).toHaveAttribute('data-sticky-header', 'true');
    expect(box).toHaveClass('[&_thead_th]:sticky', '[&_thead_th]:top-0', '[&_thead_th]:bg-white');
    // The default height cap.
    expect(box).toHaveClass('max-h-96');
  });

  it('can be named by a visible heading instead', () => {
    render(
      <>
        <h2 id="people-heading">People</h2>
        <ScrollBox labelledBy="people-heading">{table}</ScrollBox>
      </>
    );
    const box = screen.getByRole('region', { name: 'People' });
    expect(box).toHaveAttribute('aria-labelledby', 'people-heading');
    expect(box).not.toHaveAttribute('aria-label');
  });

  it('DataTable with scrollLabel scrolls in a named region with the header pinned', () => {
    render(<DataTable caption="Rules" scrollLabel="Rules of Customer" maxHeight="45vh" columns={[{ key: 'a', label: 'A' }]} rows={[{ a: 1 }]} />);
    const box = screen.getByRole('region', { name: 'Rules of Customer' });
    expect(box).toHaveAttribute('tabindex', '0');
    expect(box.style.maxHeight).toBe('45vh');
    expect(within(box).getByRole('columnheader', { name: 'A' })).toHaveClass('sticky', 'top-0');
  });
});

describe('ScrollBox in use', () => {
  it('a field’s rules table scrolls in its own box with the header pinned', () => {
    render(
      <RuleTable
        name="Full name"
        nameOf={(k) => k}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        rules={[{ id: 1, regionKey: 'full_name', ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'MANUAL' }]}
      />
    );
    const box = screen.getByRole('region', { name: 'Rules of Full name' });
    expect(box).toHaveAttribute('data-sticky-header', 'true');
    expect(box).toHaveAttribute('tabindex', '0');
    expect(within(box).getByRole('table', { name: 'Rules of Full name' })).toBeInTheDocument();
    expectAccessible();
  });

  it('the Checks panel scrolls "Rules for each field" and the issues in their own boxes', () => {
    render(
      <MemoryRouter>
        <ValidationPanel
          doc={{
            id: 5,
            status: 'DRAFT',
            blueprintId: 1,
            validation: { checked: true, errors: [{ regionKey: 'dob', message: 'Too young' }], warnings: [], setupWarnings: [] },
            fieldChecks: { dob: { rules: [{ id: 3, ruleType: 'DATE_RANGE', summary: 'On or before 18 years ago', severity: 'ERROR', origin: 'MANUAL' }], builtIn: [], mappedFrom: [] } },
          }}
          status="DRAFT"
          locked={false}
          regions={[{ key: 'dob', label: 'Date of birth', kind: 'DATE', page: 0 }]}
          issues={[{ id: 'e0', level: 'error', regionKey: 'dob', label: 'Date of birth', message: 'Too young' }]}
          onGoTo={vi.fn()}
          requiredFilled={0}
          requiredTotal={0}
        />
      </MemoryRouter>
    );
    const rules = screen.getByRole('region', { name: 'Rules for each field' });
    expect(rules).toHaveClass('max-h-80', 'overflow-auto');
    expect(within(rules).getByTestId('field-rules-list')).toBeInTheDocument();
    const issues = screen.getByRole('region', { name: 'Issue list' });
    expect(issues).toHaveClass('max-h-60', 'overflow-auto');
    expect(within(issues).getByText('Too young')).toBeInTheDocument();
    expectAccessible();
  });

  it('a Modal with scrollBody scrolls its body (max 70vh) while the footer stays outside, and focus starts on the content', () => {
    render(
      <Modal open onClose={vi.fn()} title="Long dialog" scrollBody footer={<button type="button">OK</button>}>
        <input aria-label="First field" />
      </Modal>
    );
    const dialog = screen.getByRole('dialog', { name: 'Long dialog' });
    const body = within(dialog).getByRole('region', { name: 'Long dialog' });
    expect(body).toHaveClass('max-h-[70vh]', 'overflow-auto');
    expect(body).not.toContainElement(within(dialog).getByRole('button', { name: 'OK' }));
    expect(screen.getByLabelText('First field')).toHaveFocus();
    expectAccessible();
  });

  it('the rule dialog has a scrolling body and its buttons stay visible', () => {
    render(
      <RuleDialog
        open
        field={{ key: 'full_name', label: 'Full name' }}
        rule={null}
        ruleTypes={['REQUIRED', 'LENGTH']}
        fields={[{ key: 'full_name', label: 'Full name' }]}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    const dialog = screen.getByRole('dialog', { name: 'Add a rule — Full name' });
    const body = within(dialog).getByRole('region', { name: 'Add a rule — Full name' });
    expect(body).toHaveClass('max-h-[70vh]', 'overflow-auto');
    expect(body).not.toContainElement(within(dialog).getByRole('button', { name: 'Add rule' }));
    expect(body).not.toContainElement(within(dialog).getByRole('button', { name: 'Cancel' }));
    expectAccessible();
  });

  it('"Build from Document Flow templates" has a scrolling body and its buttons stay visible', async () => {
    getTemplates.mockResolvedValue([{ templateId: 9, name: 'Customers' }]);
    render(<BuildFromFlowDialog open onClose={vi.fn()} onSaved={vi.fn()} onConflict={vi.fn()} />);
    const dialog = screen.getByRole('dialog', { name: 'Build from Document Flow templates' });
    const body = within(dialog).getByRole('region', { name: 'Build from Document Flow templates' });
    expect(body).toHaveClass('max-h-[70vh]', 'overflow-auto');
    await within(body).findByText('Customers');
    const buttons = within(dialog).getAllByRole('button').filter((b) => !body.contains(b));
    expect(buttons.map((b) => b.textContent)).toContain('Cancel');
    expectAccessible();
  });
});
