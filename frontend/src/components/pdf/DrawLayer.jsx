import { useRef, useState } from 'react';
import { MIN_SIDE, boxFromPoints, pointToFraction } from './geometry';
import { boxStyle } from './RegionBox';

/** The box Enter/Space adds for keyboard users: centred, a typical field size. */
export const DEFAULT_DRAWN_BOX = { fx: 0.4, fy: 0.47, fw: 0.2, fh: 0.03 };

/**
 * A transparent layer over the whole page for drawing a new box: press,
 * drag and release, and `onDraw({ fx, fy, fw, fh })` receives it (boxes
 * smaller than 0.5 % of the page are ignored as stray clicks). It is a
 * focusable button too: Enter or Space adds a default box in the centre,
 * which the user then moves and resizes with the arrow keys.
 */
export default function DrawLayer({ label, onDraw, color = '#1a73e8' }) {
  const drag = useRef(null);
  const [preview, setPreview] = useState(null);

  const onPointerDown = (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    const overlay = event.currentTarget;
    overlay.setPointerCapture?.(event.pointerId);
    const start = pointToFraction(event, overlay);
    drag.current = { overlay, start, box: null };
    setPreview(null);
  };

  const onPointerMove = (event) => {
    const current = drag.current;
    if (!current) return;
    current.box = boxFromPoints(current.start, pointToFraction(event, current.overlay));
    setPreview(current.box);
  };

  const onPointerUp = () => {
    const current = drag.current;
    drag.current = null;
    setPreview(null);
    if (current?.box && current.box.fw >= MIN_SIDE && current.box.fh >= MIN_SIDE) {
      onDraw(current.box);
    }
  };

  const onKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onDraw({ ...DEFAULT_DRAWN_BOX });
    }
  };

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={label}
      data-testid="draw-layer"
      className="absolute inset-0 z-10 cursor-crosshair touch-none outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => {
        drag.current = null;
        setPreview(null);
      }}
      onKeyDown={onKeyDown}
    >
      {preview && (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute border-2 border-dashed"
          style={{ ...boxStyle(preview), borderColor: color, background: `${color}22` }}
        />
      )}
    </div>
  );
}
