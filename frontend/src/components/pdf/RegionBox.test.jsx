import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import RegionBox from './RegionBox';
import DrawLayer, { DEFAULT_DRAWN_BOX } from './DrawLayer';
import EditBox from './EditBox';
import { installPointerEvents, mockLayout } from '../../test/pdfTestUtils';

const BOX = { fx: 0.1, fy: 0.2, fw: 0.3, fh: 0.05 };

function Overlay({ children }) {
  return (
    <div data-page-overlay="" style={{ position: 'relative' }}>
      {children}
    </div>
  );
}

describe('RegionBox', () => {
  beforeAll(installPointerEvents);
  beforeEach(() => mockLayout());

  it('is placed by top-left page fractions', () => {
    render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" />
      </Overlay>
    );
    const box = screen.getByRole('button', { name: 'Full name' });
    expect(box.style.left).toBe('10%');
    expect(box.style.top).toBe('20%');
    expect(box.style.width).toBe('30%');
    expect(box.style.height).toBe('5%');
  });

  it('moves by dragging and reports the box once on release', () => {
    const onChange = vi.fn();
    const onSelect = vi.fn();
    render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" editable selected onSelect={onSelect} onChange={onChange} />
      </Overlay>
    );
    const box = screen.getByRole('button', { name: 'Full name' });
    fireEvent.pointerDown(box, { clientX: 150, clientY: 210, button: 0 });
    fireEvent.pointerMove(box, { clientX: 250, clientY: 260 });
    expect(box.style.left).toBe('20%'); // live preview
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.pointerUp(box, { clientX: 250, clientY: 260 });

    expect(onSelect).toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith({ fx: 0.2, fy: 0.25, fw: 0.3, fh: 0.05 });
  });

  it('is clamped to the page while moving', () => {
    const onChange = vi.fn();
    render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" editable selected onChange={onChange} />
      </Overlay>
    );
    const box = screen.getByRole('button', { name: 'Full name' });
    fireEvent.pointerDown(box, { clientX: 150, clientY: 210, button: 0 });
    fireEvent.pointerMove(box, { clientX: 2000, clientY: -500 });
    fireEvent.pointerUp(box);

    expect(onChange).toHaveBeenCalledWith({ fx: 0.7, fy: 0, fw: 0.3, fh: 0.05 });
  });

  it('resizes from a corner handle', () => {
    const onChange = vi.fn();
    const { container } = render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" editable selected onChange={onChange} />
      </Overlay>
    );
    const handle = container.querySelector('[data-handle="se"]');
    fireEvent.pointerDown(handle, { clientX: 400, clientY: 250, button: 0 });
    fireEvent.pointerMove(handle, { clientX: 450, clientY: 300 });
    fireEvent.pointerUp(handle);

    expect(onChange).toHaveBeenCalledWith({ fx: 0.1, fy: 0.2, fw: 0.35, fh: 0.1 });
  });

  it('moves 0.5 % per arrow key, resizes with Shift and deletes with Delete', () => {
    const onChange = vi.fn();
    const onDelete = vi.fn();
    render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" editable onChange={onChange} onDelete={onDelete} />
      </Overlay>
    );
    const box = screen.getByRole('button', { name: 'Full name' });
    fireEvent.keyDown(box, { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith({ fx: 0.105, fy: 0.2, fw: 0.3, fh: 0.05 });
    fireEvent.keyDown(box, { key: 'ArrowUp' });
    expect(onChange).toHaveBeenLastCalledWith({ fx: 0.1, fy: 0.195, fw: 0.3, fh: 0.05 });
    fireEvent.keyDown(box, { key: 'ArrowDown', shiftKey: true });
    expect(onChange).toHaveBeenLastCalledWith({ fx: 0.1, fy: 0.2, fw: 0.3, fh: 0.055 });
    fireEvent.keyDown(box, { key: 'Delete' });
    expect(onDelete).toHaveBeenCalled();
  });

  it('only selects (no move) when not editable', () => {
    const onChange = vi.fn();
    const onSelect = vi.fn();
    render(
      <Overlay>
        <RegionBox box={BOX} label="Full name" onSelect={onSelect} onChange={onChange} />
      </Overlay>
    );
    const box = screen.getByRole('button', { name: 'Full name' });
    fireEvent.pointerDown(box, { clientX: 150, clientY: 210, button: 0 });
    fireEvent.pointerMove(box, { clientX: 250, clientY: 260 });
    fireEvent.pointerUp(box);
    fireEvent.keyDown(box, { key: 'ArrowRight' });
    fireEvent.keyDown(box, { key: 'Enter' });

    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('DrawLayer', () => {
  beforeAll(installPointerEvents);
  beforeEach(() => mockLayout());

  it('draws a box by dragging', () => {
    const onDraw = vi.fn();
    render(
      <Overlay>
        <DrawLayer label="Draw a field" onDraw={onDraw} />
      </Overlay>
    );
    const layer = screen.getByRole('button', { name: 'Draw a field' });
    fireEvent.pointerDown(layer, { clientX: 500, clientY: 400, button: 0 });
    fireEvent.pointerMove(layer, { clientX: 300, clientY: 450 });
    fireEvent.pointerUp(layer);

    expect(onDraw).toHaveBeenCalledWith({ fx: 0.3, fy: 0.4, fw: 0.2, fh: 0.05 });
  });

  it('ignores a click without a drag', () => {
    const onDraw = vi.fn();
    render(<DrawLayer label="Draw a field" onDraw={onDraw} />);
    const layer = screen.getByRole('button', { name: 'Draw a field' });
    fireEvent.pointerDown(layer, { clientX: 500, clientY: 400, button: 0 });
    fireEvent.pointerUp(layer);

    expect(onDraw).not.toHaveBeenCalled();
  });

  it('adds a default box from the keyboard', () => {
    const onDraw = vi.fn();
    render(<DrawLayer label="Draw a field" onDraw={onDraw} />);
    fireEvent.keyDown(screen.getByRole('button', { name: 'Draw a field' }), { key: 'Enter' });

    expect(onDraw).toHaveBeenCalledWith(DEFAULT_DRAWN_BOX);
  });
});

describe('EditBox', () => {
  it('renders each edit kind', () => {
    render(
      <Overlay>
        <EditBox edit={{ id: 'a', kind: 'REMOVE', ...BOX, removedText: 'John Doe', confidence: 0.4 }} showRemoved />
        <EditBox edit={{ id: 'b', kind: 'REPLACE_TEXT', ...BOX, text: 'ACME Ltd', fontSize: 10 }} />
        <EditBox edit={{ id: 'c', kind: 'ADD_TEXT', ...BOX, text: 'Branch 12' }} />
        <EditBox edit={{ id: 'd', kind: 'ADD_IMAGE', ...BOX, imageKey: 'k1' }} />
      </Overlay>
    );
    const removed = screen.getByRole('button', { name: 'Removed content: “John Doe” (low confidence)' });
    expect(removed).toHaveAttribute('data-low-confidence', 'true');
    expect(removed.style.background).toContain('repeating-linear-gradient');
    expect(screen.getByRole('button', { name: 'Replaced text: “ACME Ltd”' })).toHaveTextContent('ACME Ltd');
    expect(screen.getByRole('button', { name: 'Added text: “Branch 12”' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Added image' })).toHaveTextContent('Image');
  });
});
