import ScrollBox from './ScrollBox';

/**
 * Generic bordered table. columns: [{ key, label, render?(row) }].
 * Wide content scrolls inside the wrapper instead of the page.
 *
 * onRowClick (optional) makes whole rows clickable as a pointer convenience;
 * keep a real link in one of the cells as the accessible/keyboard affordance.
 *
 * maxHeight (optional, any CSS length) caps the table's height: rows scroll
 * inside the wrapper and the header row stays pinned, so long tables never
 * force the page itself to scroll.
 *
 * scrollLabel (optional) makes the scrolling wrapper a named, focusable
 * region (ScrollBox), so keyboard users can reach and scroll a long table.
 *
 * caption (optional) names the table for assistive technology (a visually
 * hidden <caption>). A column with an empty label (an actions column) gets
 * a hidden header, `srLabel` or "Actions", so its cells are not unnamed.
 */
export default function DataTable({
  columns,
  rows,
  emptyMessage = 'No data.',
  onRowClick,
  maxHeight,
  getRowClassName,
  caption,
  scrollLabel,
}) {
  if (!rows || rows.length === 0) {
    return <p className="py-6 text-sm text-muted">{emptyMessage}</p>;
  }
  const stickyHead = Boolean(maxHeight);
  const table = (
    <table className="w-full border-collapse text-[13px]">
      {caption && <caption className="sr-only">{caption}</caption>}
      <thead>
        <tr className="bg-sidebar text-left">
          {columns.map((col) => (
            <th
              key={col.key}
              scope="col"
              className={`bg-sidebar px-3 py-2 font-medium text-muted ${
                stickyHead
                  ? // border-collapse detaches borders from sticky cells in
                    // Chrome, so the bottom rule becomes an inset shadow.
                    'sticky top-0 z-[1] shadow-[inset_0_-1px_0_var(--color-line)]'
                  : 'border-b border-line'
              }`}
            >
              {col.label || <span className="sr-only">{col.srLabel || 'Actions'}</span>}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr
            // Index, not row.id: mapped data can contain its own "id" column,
            // and duplicate values there made React reuse the wrong row.
            key={i}
            className={`hover:bg-sidebar/60 ${getRowClassName?.(row, i) ?? ''} ${onRowClick ? 'cursor-pointer' : ''}`}
            onClick={
              onRowClick
                ? (e) => {
                    // Links/buttons/inputs inside cells keep their own behavior.
                    if (e.target.closest('a, button, input, select, textarea, label')) return;
                    onRowClick(row, i);
                  }
                : undefined
            }
          >
            {columns.map((col) => (
              <td key={col.key} className="border-b border-line px-3 py-2 align-top last:border-b-0">
                {col.render ? col.render(row, i) : String(row[col.key] ?? '')}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
  if (scrollLabel) {
    return (
      <ScrollBox
        label={scrollLabel}
        maxHeight=""
        className="!rounded-lg border border-line"
        style={stickyHead ? { maxHeight } : undefined}
      >
        {table}
      </ScrollBox>
    );
  }
  return (
    <div
      className={`overflow-x-auto rounded-lg border border-line ${stickyHead ? 'overflow-y-auto' : ''}`}
      style={stickyHead ? { maxHeight } : undefined}
    >
      {table}
    </div>
  );
}
