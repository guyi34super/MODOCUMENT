import { useRef, useState } from 'react';
import ConfirmDialog from '../ConfirmDialog';
import { boxFromPoints, pointToFraction } from './geometry';
import { boxStyle } from './RegionBox';
import { ZOOM_LEVELS } from './useOverlayEditor';

const toolClass = (on) =>
  `rounded border px-2 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-40 ${
    on ? 'border-accent bg-accent text-white' : 'border-line bg-white text-accent hover:bg-pill/50'
  }`;

const ALIGN = [
  ['left', 'Align left', '⇤'],
  ['hcenter', 'Align centres horizontally', '↔'],
  ['right', 'Align right', '⇥'],
  ['top', 'Align top', '⤒'],
  ['vcenter', 'Align middles vertically', '↕'],
  ['bottom', 'Align bottom', '⤓'],
];

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || '');
const MOD = isMac ? '⌘' : 'Ctrl+';

/**
 * The arrange toolbar of useOverlayEditor: undo/redo, duplicate, delete,
 * align (2+ selected), distribute (3+), same size (2+), snap and zoom.
 * Every button has a name and a shortcut in its tooltip.
 */
export default function EditorToolbar({ editor, readOnly = false }) {
  const count = editor.selected.length;
  const iconButton = (label, glyph, onClick, disabled, shortcut) => (
    <button
      type="button"
      aria-label={label}
      title={shortcut ? `${label} (${shortcut})` : label}
      onClick={onClick}
      disabled={disabled}
      className={toolClass(false)}
    >
      <span aria-hidden="true">{glyph}</span>
    </button>
  );
  return (
    <div role="toolbar" aria-label="Arrange fields" className="mb-2 flex flex-wrap items-center gap-1" data-testid="arrange-toolbar">
      {!readOnly && (
        <>
          {iconButton('Undo', '↶', editor.undo, !editor.canUndo, `${MOD}Z`)}
          {iconButton('Redo', '↷', editor.redo, !editor.canRedo, `${MOD}Shift+Z`)}
          <span className="mx-1 h-5 w-px bg-line" aria-hidden="true" />
          {iconButton('Duplicate', '⧉', editor.duplicate, count === 0, `${MOD}D`)}
          {iconButton('Paste', '⎘', editor.paste, !editor.canPaste, `${MOD}V`)}
          {iconButton('Delete selected fields', '⌫', () => editor.requestDelete(), count === 0, 'Delete')}
          <span className="mx-1 h-5 w-px bg-line" aria-hidden="true" />
          {ALIGN.map(([edge, label, glyph]) => (
            <span key={edge}>{iconButton(label, glyph, () => editor.align(edge), count < 2)}</span>
          ))}
          {iconButton('Distribute horizontally', '⋯', () => editor.distribute('x'), count < 3)}
          {iconButton('Distribute vertically', '⋮', () => editor.distribute('y'), count < 3)}
          {iconButton('Same width', 'W', () => editor.sameSize('width'), count < 2)}
          {iconButton('Same height', 'H', () => editor.sameSize('height'), count < 2)}
          {iconButton('Same size', '▣', () => editor.sameSize('both'), count < 2)}
          <span className="mx-1 h-5 w-px bg-line" aria-hidden="true" />
          <button
            type="button"
            aria-pressed={editor.snapOn}
            onClick={() => editor.setSnapOn(!editor.snapOn)}
            title="Snap to the grid and to other fields' edges and centres"
            className={toolClass(editor.snapOn)}
          >
            Snap
          </button>
        </>
      )}
      <span className="flex-1" />
      {iconButton('Zoom out', '−', editor.zoomOut, editor.zoom <= ZOOM_LEVELS[0])}
      <label className="text-xs">
        <span className="sr-only">Zoom</span>
        <select
          className="rounded border border-line px-1 py-0.5 text-xs"
          value={String(editor.zoom)}
          onChange={(e) => editor.setZoom(Number(e.target.value))}
        >
          {ZOOM_LEVELS.map((level) => (
            <option key={level} value={String(level)}>
              {Math.round(level * 100)} %
            </option>
          ))}
        </select>
      </label>
      {iconButton('Zoom in', '+', editor.zoomIn, editor.zoom >= ZOOM_LEVELS[ZOOM_LEVELS.length - 1])}
      {count > 1 && (
        <span className="ml-1 text-xs text-muted" role="status">
          {count} fields selected
        </span>
      )}
    </div>
  );
}

/** Confirms deleting the fields useOverlayEditor is asked to delete (undoable with Undo). */
export function DeleteFieldsDialog({ editor, nameOf = (item) => item.label?.trim() || item.key }) {
  const items = editor.pendingDeleteItems;
  const single = items.length === 1;
  return (
    <ConfirmDialog
      open={items.length > 0}
      title={single ? 'Delete this field?' : `Delete ${items.length} fields?`}
      confirmLabel={single ? 'Delete field' : `Delete ${items.length} fields`}
      tone="danger"
      onConfirm={editor.confirmDelete}
      onCancel={editor.cancelDelete}
    >
      <p>
        {single ? `“${nameOf(items[0])}” is removed from the template.` : `${items.map(nameOf).join(', ')} are removed from the template.`}{' '}
        Undo brings {single ? 'it' : 'them'} back until you leave the editor.
      </p>
    </ConfirmDialog>
  );
}

/**
 * The empty space of the page with the Select tool: dragging draws a marquee
 * that selects the boxes it touches (Shift adds to the selection); a plain
 * click clears the selection. It lies under the boxes, so pressing a box
 * still drags the box. Keyboard users select with Shift+Enter and Ctrl+A.
 */
export function MarqueeLayer({ onSelectRect, onClear }) {
  const drag = useRef(null);
  const [rect, setRect] = useState(null);
  return (
    <div
      aria-hidden="true"
      data-testid="marquee-layer"
      className="absolute inset-0 z-[1] touch-none"
      onPointerDown={(event) => {
        if (event.button !== 0) return;
        const overlay = event.currentTarget;
        overlay.setPointerCapture?.(event.pointerId);
        drag.current = { overlay, start: pointToFraction(event, overlay), additive: event.shiftKey, box: null };
      }}
      onPointerMove={(event) => {
        const current = drag.current;
        if (!current) return;
        current.box = boxFromPoints(current.start, pointToFraction(event, current.overlay));
        setRect(current.box);
      }}
      onPointerUp={() => {
        const current = drag.current;
        drag.current = null;
        setRect(null);
        if (!current) return;
        if (current.box && (current.box.fw > 0.003 || current.box.fh > 0.003)) {
          onSelectRect(current.box, { additive: current.additive });
        } else if (!current.additive) {
          onClear();
        }
      }}
      onPointerCancel={() => {
        drag.current = null;
        setRect(null);
      }}
    >
      {rect && (
        <span
          className="pointer-events-none absolute border border-dashed border-accent bg-accent/10"
          style={boxStyle(rect)}
          data-testid="marquee"
        />
      )}
    </div>
  );
}

/** The snap guides of a drag: thin lines across the page. */
export function SnapGuides({ guides }) {
  if (!guides?.length) return null;
  return (
    <>
      {guides.map((g) => (
        <span
          key={`${g.axis}:${g.at}`}
          aria-hidden="true"
          data-testid={`guide-${g.axis}`}
          className="pointer-events-none absolute z-[5] bg-fuchsia-500"
          style={
            g.axis === 'x'
              ? { left: `${g.at * 100}%`, top: 0, bottom: 0, width: 1 }
              : { top: `${g.at * 100}%`, left: 0, right: 0, height: 1 }
          }
        />
      ))}
    </>
  );
}
