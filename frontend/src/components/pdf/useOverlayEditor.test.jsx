import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, renderHook } from '@testing-library/react';
import useOverlayEditor, { HISTORY_LIMIT, PASTE_OFFSET } from './useOverlayEditor';

const field = (uid, key, overrides = {}) => ({
  _uid: uid,
  key,
  label: key.toUpperCase(),
  kind: 'TEXT',
  page: 0,
  fx: 0.1,
  fy: 0.1,
  fw: 0.2,
  fh: 0.05,
  sortOrder: 0,
  ...overrides,
});

function setup(options = {}, items = [field('a', 'name'), field('b', 'dob', { fx: 0.5, fy: 0.3, sortOrder: 1 })]) {
  const onChange = vi.fn();
  const hook = renderHook((props) => useOverlayEditor({ onChange, ...options, ...props }), {
    initialProps: {},
  });
  act(() => hook.result.current.reset(items));
  return { ...hook, onChange, editor: () => hook.result.current };
}

const byUid = (editor, uid) => editor.items.find((i) => i._uid === uid);
const key = (k, init = {}) => fireEvent.keyDown(document.body, { key: k, ...init });

describe('useOverlayEditor', () => {
  it('undoes and redoes changes, one step each, and reports every change', () => {
    const { editor, onChange } = setup();
    act(() => editor().update('a', { label: 'Full name' }));
    act(() => editor().dragEnd('b', { fx: 0.6, fy: 0.3, fw: 0.2, fh: 0.05 }));
    expect(byUid(editor(), 'b').fx).toBe(0.6);
    expect(editor().canUndo).toBe(true);
    expect(onChange).toHaveBeenCalledTimes(2);

    act(() => editor().undo());
    expect(byUid(editor(), 'b').fx).toBe(0.5);
    expect(byUid(editor(), 'a').label).toBe('Full name');
    act(() => editor().undo());
    expect(byUid(editor(), 'a').label).toBe('NAME');
    expect(editor().canUndo).toBe(false);
    act(() => editor().redo());
    act(() => editor().redo());
    expect(byUid(editor(), 'b').fx).toBe(0.6);
    expect(editor().canRedo).toBe(false);
  });

  it('makes one step of a run of the same edit (typing) and keeps at most 100 steps', () => {
    const { editor } = setup();
    for (const label of ['F', 'Fu', 'Ful', 'Full']) act(() => editor().update('a', { label }, { chain: true }));
    act(() => editor().undo());
    expect(byUid(editor(), 'a').label).toBe('NAME');

    for (let i = 0; i < HISTORY_LIMIT + 20; i += 1) act(() => editor().update('a', { fx: i / 1000 }));
    let steps = 0;
    while (editor().canUndo) {
      act(() => editor().undo());
      steps += 1;
    }
    expect(steps).toBe(HISTORY_LIMIT);
  });

  it('marks changed items with touch, and quiet changes leave no undo step', () => {
    const { editor, onChange } = setup({ touch: (i) => ({ ...i, _edited: true }) });
    act(() => editor().update('a', { fy: 0.2 }));
    expect(byUid(editor(), 'a')._edited).toBe(true);
    expect(byUid(editor(), 'b')._edited).toBeUndefined();
    onChange.mockClear();
    act(() => editor().quietly((list) => list.map((i) => ({ ...i, required: true }))));
    expect(byUid(editor(), 'b').required).toBe(true);
    expect(onChange).not.toHaveBeenCalled();
    act(() => editor().undo());
    expect(byUid(editor(), 'a').fy).toBe(0.1);
  });

  it('selects with Shift (toggle), keeps a group when pressing a member, and by marquee or Ctrl+A on the page', () => {
    const items = [field('a', 'a'), field('b', 'b', { fx: 0.5 }), field('c', 'c', { page: 1 })];
    const { editor } = setup({}, items);
    act(() => editor().select('a'));
    act(() => editor().select('b', { additive: true }));
    expect(editor().selected).toEqual(['a', 'b']);
    act(() => editor().select('a', { keep: true }));
    expect(editor().selected).toEqual(['a', 'b']);
    act(() => editor().select('a', { additive: true }));
    expect(editor().selected).toEqual(['b']);

    act(() => editor().selectInRect({ fx: 0, fy: 0, fw: 0.35, fh: 0.5 }));
    expect(editor().selected).toEqual(['a']);
    act(() => editor().selectInRect({ fx: 0.45, fy: 0, fw: 0.1, fh: 0.5 }, { additive: true }));
    expect(editor().selected).toEqual(['a', 'b']);

    act(() => editor().clearSelection());
    act(() => key('a', { ctrlKey: true }));
    expect(editor().selected).toEqual(['a', 'b']); // page 0 only
  });

  it('duplicates and pastes with an offset, unique keys and the next sort order', () => {
    const { editor, rerender } = setup();
    act(() => editor().select('a'));
    act(() => editor().duplicate());
    const copy = editor().selectedItems[0];
    expect(copy).toMatchObject({ key: 'name_2', label: 'NAME (copy)', page: 0, sortOrder: 2, source: 'MANUAL' });
    expect(copy.fx).toBeCloseTo(0.1 + PASTE_OFFSET);
    expect(copy.fy).toBeCloseTo(0.1 + PASTE_OFFSET);
    expect(copy._uid).not.toBe('a');

    act(() => editor().setSelection(['a', 'b']));
    act(() => editor().copy());
    rerender({ page: 2 });
    act(() => editor().paste());
    const pasted = editor().selectedItems;
    expect(pasted.map((p) => p.key)).toEqual(['name_3', 'dob_2']);
    expect(pasted.every((p) => p.page === 2)).toBe(true);
    expect(editor().items).toHaveLength(5);
    act(() => editor().undo());
    expect(editor().items).toHaveLength(3);
  });

  it('asks before deleting, and the delete can be undone', () => {
    const { editor } = setup();
    act(() => editor().setSelection(['a', 'b']));
    act(() => key('Delete'));
    expect(editor().pendingDeleteItems.map((i) => i.key)).toEqual(['name', 'dob']);
    expect(editor().items).toHaveLength(2);
    act(() => editor().cancelDelete());
    expect(editor().pendingDelete).toBeNull();

    act(() => editor().requestDelete(['b']));
    act(() => editor().confirmDelete());
    expect(editor().items.map((i) => i.key)).toEqual(['name']);
    expect(editor().selected).toEqual(['a']);
    act(() => key('z', { ctrlKey: true }));
    expect(editor().items.map((i) => i.key)).toEqual(['name', 'dob']);
    act(() => key('Z', { ctrlKey: true, shiftKey: true }));
    expect(editor().items).toHaveLength(1);
    act(() => key('y', { metaKey: true }));
    act(() => key('z', { metaKey: true }));
    expect(editor().items).toHaveLength(2);
  });

  it('aligns, distributes and sizes the selection as one undo step each', () => {
    const items = [
      field('a', 'a', { fx: 0.1, fw: 0.2 }),
      field('b', 'b', { fx: 0.5, fy: 0.3, fw: 0.1 }),
      field('c', 'c', { fx: 0.3, fy: 0.8, fw: 0.1 }),
    ];
    const { editor } = setup({}, items);
    act(() => editor().setSelection(['a', 'b', 'c']));
    act(() => editor().align('left'));
    expect(editor().items.map((i) => i.fx)).toEqual([0.1, 0.1, 0.1]);
    act(() => editor().distribute('y'));
    expect(byUid(editor(), 'b').fy).toBeCloseTo(0.45);
    act(() => editor().sameSize('width'));
    expect(editor().items.map((i) => i.fw)).toEqual([0.2, 0.2, 0.2]);
    act(() => editor().undo());
    act(() => editor().undo());
    act(() => editor().undo());
    expect(editor().items.map((i) => i.fx)).toEqual([0.1, 0.5, 0.3]);
    // Fewer than two selected: nothing to arrange.
    act(() => editor().setSelection(['a']));
    act(() => editor().align('right'));
    expect(editor().canUndo).toBe(false);
  });

  it('moves the whole selection with a drag of one member and nudges with the arrows', () => {
    const { editor } = setup();
    act(() => editor().setSelection(['a', 'b']));
    act(() => editor().dragEnd('a', { fx: 0.2, fy: 0.15, fw: 0.2, fh: 0.05 }));
    expect(byUid(editor(), 'b')).toMatchObject({ fx: 0.6, fy: 0.35 });

    act(() => key('ArrowRight'));
    expect(byUid(editor(), 'a').fx).toBeCloseTo(0.205);
    act(() => key('ArrowRight', { altKey: true }));
    expect(byUid(editor(), 'a').fx).toBeCloseTo(0.206);
    act(() => key('ArrowDown', { shiftKey: true }));
    expect(byUid(editor(), 'b').fy).toBeCloseTo(0.375);
    act(() => key('ArrowRight', { ctrlKey: true }));
    expect(byUid(editor(), 'a').fw).toBeCloseTo(0.205);
    // The nudges of one selection are one step; the drag is another.
    act(() => editor().undo());
    act(() => editor().undo());
    expect(byUid(editor(), 'a')).toMatchObject({ fx: 0.2, fw: 0.2 });
  });

  it('snaps a dragged box to the others when Snap is on, and zooms between 50 and 200 %', () => {
    const { editor } = setup();
    expect(editor().snapFor('a')).toBeNull();
    act(() => editor().setSnapOn(true));
    const { box, guides } = editor().snapFor('a')({ fx: 0.497, fy: 0.2, fw: 0.15, fh: 0.05 }, 'move');
    expect(box.fx).toBe(0.5);
    expect(guides).toEqual([{ axis: 'x', at: 0.5 }]);

    act(() => editor().zoomIn());
    expect(editor().zoom).toBe(1.25);
    act(() => editor().setZoom(5));
    expect(editor().zoom).toBe(2);
    act(() => editor().zoomIn());
    expect(editor().zoom).toBe(2);
    act(() => editor().setZoom(0.1));
    expect(editor().zoom).toBe(0.5);
    act(() => editor().zoomOut());
    expect(editor().zoom).toBe(0.5);
  });

  it('leaves shortcuts alone while typing in a field or when disabled', () => {
    const { editor, rerender } = setup();
    act(() => editor().update('a', { label: 'X' }));
    const input = document.createElement('input');
    document.body.appendChild(input);
    act(() => {
      fireEvent.keyDown(input, { key: 'z', ctrlKey: true });
    });
    expect(byUid(editor(), 'a').label).toBe('X');
    input.remove();
    rerender({ enabled: false });
    act(() => key('z', { ctrlKey: true }));
    expect(byUid(editor(), 'a').label).toBe('X');
    rerender({ enabled: true });
    act(() => key('z', { ctrlKey: true }));
    expect(byUid(editor(), 'a').label).toBe('NAME');
  });
});
