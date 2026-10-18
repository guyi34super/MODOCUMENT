/**
 * A scrollable box for long lists and wide tables: the content scrolls
 * inside it (both ways) instead of stretching the page, so a narrow screen
 * never gets a horizontal page scroll.
 *
 * It is a named, focusable region (role="region", `aria-label` from
 * `label` or `aria-labelledby` from `labelledBy`, tabIndex 0) so keyboard
 * users can reach it and scroll it with the arrow keys; focus shows a
 * visible ring.
 *
 * Props:
 * - `label` (string) or `labelledBy` (id of a visible heading): its name
 * - `maxHeight`: a Tailwind max-height class (default 'max-h-96')
 * - `stickyHeader`: keeps the `thead th` cells of a table inside pinned at
 *   the top while the rows scroll (with a background so rows do not show
 *   through)
 * - `className`, `style`, `testId`: extra classes, inline style, data-testid
 */
export default function ScrollBox({
  label,
  labelledBy,
  maxHeight = 'max-h-96',
  stickyHeader = false,
  className = '',
  style,
  testId,
  children,
}) {
  return (
    <div
      role="region"
      aria-label={labelledBy ? undefined : label}
      aria-labelledby={labelledBy}
      // Focusable: a scrollable region must be reachable (and scrollable) by keyboard.
      tabIndex={0}
      data-scrollbox=""
      data-sticky-header={stickyHeader ? 'true' : undefined}
      data-testid={testId}
      style={style}
      className={`min-w-0 max-w-full overflow-auto overscroll-contain rounded outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 ${maxHeight} ${
        stickyHeader
          ? '[&_thead_th]:sticky [&_thead_th]:top-0 [&_thead_th]:z-[1] [&_thead_th]:bg-white [&_thead_th]:shadow-[inset_0_-1px_0_var(--color-line)]'
          : ''
      } ${className}`}
    >
      {children}
    </div>
  );
}
