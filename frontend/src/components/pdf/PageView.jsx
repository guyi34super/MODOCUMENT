import { useLayoutEffect, useRef, useState } from 'react';
import AuthedImage from '../AuthedImage';
import { displaySize } from './geometry';

/**
 * One PDF page: the server-rendered PNG (Bearer-fetched via AuthedImage)
 * with an absolutely positioned overlay on top. The frame keeps the page's
 * aspect ratio from its PageInfo and fills the available width, so every
 * overlay child is placed with percentages (top-left page fractions).
 *
 * `children` is a node or a function receiving `{ scale }`: CSS pixels per
 * PDF point at the current size, for scaling font sizes to the page.
 */
export default function PageView({ path, page, alt, children, className = '', testId = 'page-view' }) {
  const frameRef = useRef(null);
  const [widthPx, setWidthPx] = useState(0);
  const size = displaySize(page);

  useLayoutEffect(() => {
    const frame = frameRef.current;
    if (!frame) return undefined;
    const measure = () => setWidthPx(frame.getBoundingClientRect().width || 0);
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(frame);
    return () => observer.disconnect();
  }, []);

  const scale = widthPx > 0 ? widthPx / size.width : 1;

  return (
    <div
      ref={frameRef}
      data-testid={testId}
      className={`relative w-full select-none overflow-hidden border border-line bg-white shadow-sm ${className}`}
      style={{ aspectRatio: `${size.width} / ${size.height}` }}
    >
      {path && (
        <AuthedImage
          path={path}
          alt={alt}
          className="pointer-events-none absolute inset-0 h-full w-full"
        />
      )}
      <div data-page-overlay="" className="absolute inset-0">
        {typeof children === 'function' ? children({ scale }) : children}
      </div>
    </div>
  );
}
