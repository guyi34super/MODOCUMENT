/**
 * Generic bordered table. columns: [{ key, label, render?(row) }].
 * Wide content scrolls inside the wrapper instead of the page.
 */
export default function DataTable({ columns, rows, emptyMessage = 'No data.' }) {
  if (!rows || rows.length === 0) {
    return <p className="py-6 text-sm text-muted">{emptyMessage}</p>;
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr className="bg-sidebar text-left">
            {columns.map((col) => (
              <th key={col.key} className="border-b border-line px-3 py-2 font-medium text-muted">
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={row.id ?? i} className="hover:bg-sidebar/60">
              {columns.map((col) => (
                <td key={col.key} className="border-b border-line px-3 py-2 align-top last:border-b-0">
                  {col.render ? col.render(row, i) : String(row[col.key] ?? '')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
