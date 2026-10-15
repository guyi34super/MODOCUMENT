import { useRef, useState } from 'react';
import { KEY_STEP, moveBox, pointToFraction, resizeBox } from './geometry';

const CORNERS = [
  { corner: 'nw', className: '-left-1.5 -top-1.5 cursor-nwse-resize' },
  { corner: 'ne', className: '-right-1.5 -top-1.5 cursor-nesw-resize' },
  { corner: 'sw', className: '-left-1.5 -bottom-1.5 cursor-nesw-resize' },
  { corner: 'se', className: '-right-1.5 -bottom-1.5 cursor-nwse-resize' },
];

const ARROWS = {
  ArrowLeft: [-1, 0],
  ArrowRight: [1, 0],
  ArrowUp: [0, -1],
  ArrowDown: [0, 1],
};

export function boxStyle(box) {
  return {
    left: `${box.fx * 100}%`,
    top: `${box.fy * 100}%`,
    width: `${box.fw * 100}%`,
    height: `${box.fh * 100}%`,
  };
}

/**
 * A box on a page overlay, positioned by top-left fractions. Selectable by
 * click or keyboard (it is a focusable button); when `editable`, it moves
 * by dragging its body, resizes from its corners (pointer events, clamped
 * to the page) and moves 0.5 % per arrow key (Shift+arrow resizes).
 * Changes are reported once, on release, through `onChange(box)`.
 */
export default function RegionBox({
  id,
  box,
  label,
  tabText,
  color = '#1a73e8',
  selected = false,
  highlight = false,
  editable = false,
  selectable = true,
  onSelect,
  onChange,
  onDelete,
  className = '',
  style,
  children,
  ...rest
}) {
  const drag = useRef(null);
  const previewRef = useRef(null);
  const [preview, setPreviewState] = useState(null);
  const setPreview = (next) => {
    previewRef.current = next;
    setPreviewState(next);
  };
  const shown = preview || box;

  const startDrag = (event, mode) => {
    if (event.button !== 0) return;
    onSelect?.();
    if (!editable) return;
    event.preventDefault();
    event.stopPropagation();
    const overlay = event.currentTarget.closest('[data-page-overlay]');
    if (!overlay) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    drag.current = { mode, overlay, start: pointToFraction(event, overlay), startBox: box };
  };

  const onPointerMove = (event) => {
    const current = drag.current;
    if (!current) return;
    const point = pointToFraction(event, current.overlay);
    const dx = point.x - current.start.x;
    const dy = point.y - current.start.y;
    setPreview(
      current.mode === 'move'
        ? moveBox(current.startBox, dx, dy)
        : resizeBox(current.startBox, current.mode, dx, dy)
    );
  };

  const endDrag = () => {
    const current = drag.current;
    drag.current = null;
    if (!current) return;
    const last = previewRef.current;
    setPreview(null);
    if (
      last &&
      (last.fx !== box.fx || last.fy !== box.fy || last.fw !== box.fw || last.fh !== box.fh)
    ) {
      onChange?.(last);
    }
  };

  const onKeyDown = (event) => {
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect?.();
      return;
    }
    if (!editable) return;
    if ((event.key === 'Delete' || event.key === 'Backspace') && onDelete) {
      event.preventDefault();
      onDelete();
      return;
    }
    const arrow = ARROWS[event.key];
    if (!arrow) return;
    event.preventDefault();
    const [x, y] = arrow;
    onChange?.(
      event.shiftKey
        ? resizeBox(box, 'se', x * KEY_STEP, y * KEY_STEP)
        : moveBox(box, x * KEY_STEP, y * KEY_STEP)
    );
  };

  return (
    <div
      id={id}
      role="button"
      tabIndex={selectable ? 0 : -1}
      aria-label={label}
      aria-pressed={selected}
      onPointerDown={selectable ? (e) => startDrag(e, 'move') : undefined}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onKeyDown={selectable ? onKeyDown : undefined}
      className={`absolute box-border touch-none outline-none focus-visible:ring-2 focus-visible:ring-accent ${
        editable ? 'cursor-move' : selectable ? 'cursor-pointer' : ''
      } ${className}`}
      style={{
        ...boxStyle(shown),
        border: `${selected ? 2 : 1}px ${highlight ? 'dashed' : 'solid'} ${highlight ? '#b45309' : color}`,
        boxShadow: highlight ? '0 0 0 2px rgba(245, 158, 11, 0.45)' : undefined,
        zIndex: selected ? 3 : 2,
        ...style,
      }}
      {...rest}
    >
      {tabText && (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute bottom-full left-0 max-w-[16rem] truncate whitespace-nowrap rounded-t px-1 text-[10px] leading-4 text-white"
          style={{ background: highlight ? '#b45309' : color }}
        >
          {tabText}
        </span>
      )}
      {children}
      {selected &&
        editable &&
        CORNERS.map(({ corner, className: position }) => (
          <span
            key={corner}
            aria-hidden="true"
            data-handle={corner}
            onPointerDown={(e) => startDrag(e, corner)}
            className={`absolute size-3 rounded-sm border border-white ${position}`}
            style={{ background: color }}
          />
        ))}
    </div>
  );
}
