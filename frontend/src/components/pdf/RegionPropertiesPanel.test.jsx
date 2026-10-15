import { describe, expect, it } from 'vitest';
import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RegionPropertiesPanel from './RegionPropertiesPanel';
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
function Harness({ initial, others = [] }) {
  const [current, setCurrent] = useState(initial);
  return (
    <>
      <RegionPropertiesPanel
        region={current}
        regions={[current, ...others]}
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
