import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RegionPropertiesPanel, { cleanProps, optionsProblem } from './RegionPropertiesPanel';
import PageView from './PageView';
import ThumbnailStrip from './ThumbnailStrip';
import { stubImageFetch } from '../../test/pdfTestUtils';

const region = (overrides = {}) => ({
  key: 'field',
  label: '',
  group: null,
  kind: 'TEXT',
  page: 0,
  fx: 0.1,
  fy: 0.1,
  fw: 0.2,
  fh: 0.03,
  fontSize: 0,
  align: 'LEFT',
  maxLines: 1,
  required: false,
  ...overrides,
});

/** The panel is controlled: keep the edited region in state like the editor does. */
function Harness({ initial, others = [], fonts, rules }) {
  const [current, setCurrent] = useState(initial);
  return (
    <>
      <RegionPropertiesPanel
        region={current}
        regions={[current, ...others]}
        fonts={fonts}
        rules={rules}
        onChange={(patch) => setCurrent((r) => ({ ...r, ...patch }))}
        onDelete={() => {}}
      />
      <output data-testid="state">{JSON.stringify(current)}</output>
    </>
  );
}

const state = () => JSON.parse(screen.getByTestId('state').textContent);

describe('RegionPropertiesPanel', () => {
  it('derives a unique key from the label until the key is edited by hand', async () => {
    render(<Harness initial={region()} others={[region({ key: 'full_name', label: 'Full name' })]} />);

    await userEvent.type(screen.getByLabelText('Label'), 'Full name');
    expect(state().key).toBe('full_name_2');

    const key = screen.getByLabelText(/^Key/);
    await userEvent.clear(key);
    await userEvent.type(key, 'applicant');
    await userEvent.type(screen.getByLabelText('Label'), ' (joint)');
    expect(state()).toMatchObject({ key: 'applicant', label: 'Full name (joint)' });
  });

  it('flags an invalid or duplicate key', async () => {
    render(<Harness initial={region({ key: 'a', label: 'A' })} others={[region({ key: 'taken', label: 'T' })]} />);
    const key = screen.getByLabelText(/^Key/);
    await userEvent.clear(key);
    await userEvent.type(key, 'taken');

    expect(key).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Another field already uses this key')).toBeInTheDocument();
    await userEvent.clear(key);
    expect(screen.getByText('Use 1–64 lower-case letters, digits or _')).toBeInTheDocument();
  });

  it('edits kind, group, font size, alignment, max lines and required', async () => {
    render(<Harness initial={region({ key: 'notes', label: 'Notes' })} />);

    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'MULTILINE');
    expect(state()).toMatchObject({ kind: 'MULTILINE', maxLines: 2 });
    fireEvent.change(screen.getByLabelText('Max lines'), { target: { value: '5' } });
    await userEvent.type(screen.getByLabelText('Group'), 'Remarks');
    fireEvent.change(screen.getByLabelText(/Font size/), { target: { value: '9' } });
    await userEvent.selectOptions(screen.getByLabelText('Align'), 'RIGHT');
    await userEvent.click(screen.getByLabelText('Required'));

    expect(state()).toMatchObject({
      kind: 'MULTILINE',
      maxLines: 5,
      group: 'Remarks',
      fontSize: 9,
      align: 'RIGHT',
      required: true,
    });
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'IMAGE');
    expect(state().maxLines).toBe(0);
  });
});

describe('RegionPropertiesPanel — sections and field properties', () => {
  const group = (name) => screen.getByRole('group', { name });

  it('groups the properties into Basics, Appearance, Input and Rules', () => {
    render(<Harness initial={region({ key: 'name', label: 'Name' })} />);
    expect(within(group('Basics')).getByLabelText('Label')).toBeInTheDocument();
    expect(within(group('Basics')).getByLabelText('Kind')).toBeInTheDocument();
    expect(within(group('Appearance')).getByLabelText('Font')).toBeInTheDocument();
    expect(within(group('Appearance')).getByLabelText('Colour')).toBeInTheDocument();
    expect(within(group('Input')).getByLabelText('Placeholder')).toBeInTheDocument();
    expect(within(group('Rules')).getByLabelText('Required')).toBeInTheDocument();
  });

  it('offers the blueprint fonts plus the detected one, sizes up to 144 pt and a colour', async () => {
    render(<Harness initial={region({ fontName: 'ArialMT', color: '#000000' })} fonts={['LiberationSans', 'LiberationSerif']} />);
    const font = screen.getByLabelText('Font');
    expect(within(font).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'LiberationSans',
      'LiberationSerif',
      'ArialMT (detected)',
    ]);
    await userEvent.selectOptions(font, 'LiberationSerif');
    fireEvent.change(screen.getByLabelText(/Font size/), { target: { value: '300' } });
    fireEvent.change(screen.getByLabelText('Colour'), { target: { value: '#1a73e8' } });
    expect(state()).toMatchObject({ fontName: 'LiberationSerif', fontSize: 144, color: '#1a73e8' });
  });

  it('allows up to 500 lines for a multi-line field', () => {
    render(<Harness initial={region({ kind: 'MULTILINE', maxLines: 3 })} />);
    fireEvent.change(screen.getByLabelText('Max lines'), { target: { value: '900' } });
    expect(state().maxLines).toBe(500);
  });

  it('suggests existing groups', () => {
    render(<Harness initial={region()} others={[region({ key: 'x', group: 'Applicant' }), region({ key: 'y', group: 'Loan' })]} />);
    const input = screen.getByLabelText('Group');
    const list = document.getElementById(input.getAttribute('list'));
    expect([...list.querySelectorAll('option')].map((o) => o.value)).toEqual(['Applicant', 'Loan']);
  });

  it('stores placeholder, help text, default value and max length in props, dropping empty ones', async () => {
    render(<Harness initial={region({ key: 'name' })} />);
    await userEvent.type(screen.getByLabelText('Placeholder'), 'e.g. Jane');
    await userEvent.type(screen.getByLabelText(/Help text/), 'As on the ID');
    await userEvent.type(screen.getByLabelText('Default value'), 'Jane');
    fireEvent.change(screen.getByLabelText(/Max length/), { target: { value: '40' } });
    expect(state().props).toEqual({ placeholder: 'e.g. Jane', helpText: 'As on the ID', defaultValue: 'Jane', maxLength: 40 });
    await userEvent.clear(screen.getByLabelText('Placeholder'));
    expect(state().props).not.toHaveProperty('placeholder');
    expect(cleanProps({ placeholder: '', options: [], numberFormat: { thousands: false } })).toBeNull();
  });

  it('edits the options of a dropdown: add, reorder, remove, with a duplicate check, and a default among them', async () => {
    render(<Harness initial={region({ key: 'status', label: 'Status' })} />);
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'CHOICE');
    expect(state()).toMatchObject({ kind: 'CHOICE', maxLines: 1, props: { options: [] } });
    expect(screen.getByRole('alert')).toHaveTextContent('A dropdown needs at least one option');

    await userEvent.click(screen.getByRole('button', { name: 'Add option' }));
    await userEvent.type(screen.getByLabelText('Option 1'), 'Single');
    await userEvent.click(screen.getByRole('button', { name: 'Add option' }));
    await userEvent.type(screen.getByLabelText('Option 2'), 'Single');
    expect(screen.getByText('Each option may appear only once.')).toBeInTheDocument();
    expect(screen.getByLabelText('Option 2')).toHaveAttribute('aria-invalid', 'true');
    await userEvent.clear(screen.getByLabelText('Option 2'));
    await userEvent.type(screen.getByLabelText('Option 2'), 'Married');
    await userEvent.click(screen.getByRole('button', { name: 'Move option 2 up' }));
    expect(state().props.options).toEqual(['Married', 'Single']);
    await userEvent.click(screen.getByRole('button', { name: 'Add option' }));
    expect(optionsProblem(state())).toBe('Fill in or remove the empty option');
    await userEvent.click(screen.getByRole('button', { name: 'Remove option 3' }));
    expect(optionsProblem(state())).toBe('');

    const fallback = screen.getByLabelText('Default value');
    expect(within(fallback).getAllByRole('option').map((o) => o.textContent)).toEqual(['No default', 'Married', 'Single']);
    await userEvent.selectOptions(fallback, 'Single');
    expect(state().props).toEqual({ options: ['Married', 'Single'], defaultValue: 'Single' });
  });

  it('chooses the date format of a date and the number format of a number', async () => {
    const { unmount } = render(<Harness initial={region({ kind: 'DATE' })} />);
    await userEvent.selectOptions(screen.getByLabelText(/Date format in the PDF/), 'dd/MM/yyyy');
    expect(screen.getByLabelText('Default value')).toHaveAttribute('type', 'date');
    expect(state().props).toEqual({ dateFormat: 'dd/MM/yyyy' });
    unmount();

    render(<Harness initial={region({ kind: 'NUMBER' })} />);
    fireEvent.change(screen.getByLabelText(/Decimals/), { target: { value: '9' } });
    await userEvent.click(screen.getByLabelText('Thousands separator'));
    await userEvent.type(screen.getByLabelText('Prefix'), 'Rs ');
    expect(state().props).toEqual({ numberFormat: { decimals: 6, thousands: true, prefix: 'Rs ' } });
    expect(screen.getByLabelText('Prefix')).toHaveAttribute('maxLength', '5');
    expect(screen.queryByLabelText(/Max length/)).not.toBeInTheDocument();
  });

  it('shows the rules count, links to the rules and toggles Required through the rules', async () => {
    const onToggleRequired = vi.fn();
    const onEditRules = vi.fn();
    render(
      <Harness
        initial={region({ key: 'dob', label: 'Date of birth' })}
        rules={{ count: 2, required: false, onToggleRequired, onEditRules }}
      />
    );
    expect(within(group('Rules')).getByText('2 rules')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Edit rules' }));
    expect(onEditRules).toHaveBeenCalled();
    await userEvent.click(screen.getByLabelText('Required'));
    expect(onToggleRequired).toHaveBeenCalledWith(true);
    // The rules decide: the region's own flag is not touched.
    expect(state().required).toBe(false);
  });
});

describe('RegionPropertiesPanel — several fields', () => {
  function Multi({ initial }) {
    const [items, setItems] = useState(initial);
    return (
      <>
        <RegionPropertiesPanel
          region={null}
          regions={items}
          selection={items}
          fonts={['LiberationSans']}
          onChangeMany={(patchFor) => setItems((list) => list.map((r) => ({ ...r, ...patchFor(r) })))}
        />
        <output data-testid="state">{JSON.stringify(items)}</output>
      </>
    );
  }

  it('edits only the shared properties of every selected field, showing mixed values', async () => {
    render(
      <Multi
        initial={[
          region({ key: 'a', fontSize: 10, align: 'LEFT', fontName: 'LiberationSans' }),
          region({ key: 'b', fontSize: 12, align: 'LEFT', fontName: 'LiberationSans', kind: 'MULTILINE', maxLines: 4 }),
        ]}
      />
    );
    expect(screen.getByText('2 fields selected')).toBeInTheDocument();
    expect(screen.queryByLabelText('Label')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Key/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Kind')).toHaveValue('');
    expect(screen.getByLabelText(/Font size/)).toHaveValue(null);

    await userEvent.selectOptions(screen.getByLabelText('Align'), 'RIGHT');
    fireEvent.change(screen.getByLabelText(/Font size/), { target: { value: '9' } });
    await userEvent.type(screen.getByLabelText('Group'), 'Loan');
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'TEXT');
    const items = state();
    expect(items.map((r) => [r.align, r.fontSize, r.group, r.kind, r.maxLines])).toEqual([
      ['RIGHT', 9, 'Loan', 'TEXT', 1],
      ['RIGHT', 9, 'Loan', 'TEXT', 1],
    ]);
  });
});

describe('PageView and ThumbnailStrip', () => {
  it('keeps the page aspect ratio and shows the authed page image under the overlay', async () => {
    stubImageFetch();
    render(
      <PageView path="/p/0.png" page={{ index: 0, width: 600, height: 800, rotation: 0 }} alt="Page 1">
        <span>overlay</span>
      </PageView>
    );
    expect(screen.getByTestId('page-view').style.aspectRatio).toBe('600 / 800');
    expect(await screen.findByAltText('Page 1')).toHaveAttribute('src', 'blob:page');
    expect(screen.getByText('overlay')).toBeInTheDocument();
  });

  it('turns the frame for rotated pages', () => {
    render(<PageView page={{ index: 0, width: 600, height: 800, rotation: 270 }} alt="Page 1" />);
    expect(screen.getByTestId('page-view').style.aspectRatio).toBe('800 / 600');
  });

  it('navigates between pages', async () => {
    const pages = [0, 1, 2].map((index) => ({ index, width: 600, height: 800, rotation: 0 }));
    let current = 0;
    const { rerender } = render(
      <ThumbnailStrip pages={pages} current={current} onSelect={(i) => (current = i)} badges={['2 fields']} />
    );
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Page 3' }));
    expect(current).toBe(2);
    rerender(<ThumbnailStrip pages={pages} current={2} onSelect={(i) => (current = i)} />);
    expect(screen.getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });
});
