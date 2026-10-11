import { Button } from './ui';

/**
 * Previous / next for a paged list (v1.2.0, `{items, page, size, total}`).
 * Renders nothing when everything fits on one page. `label` names what is
 * paged ("Users") for the navigation landmark and the status line.
 */
export default function Pager({ page, size, total, onPage, label = 'Results', disabled = false }) {
  const pages = Math.max(1, Math.ceil((total || 0) / Math.max(1, size || 1)));
  if (pages <= 1) return null;
  const first = page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  return (
    <nav aria-label={`${label} pages`} className="mt-3 flex flex-wrap items-center justify-end gap-3 text-sm">
      <span className="text-muted" aria-live="polite">
        {first}–{last} of {total} · page {page + 1} of {pages}
      </span>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onPage(page - 1)}
        disabled={disabled || page <= 0}
      >
        Previous page
      </Button>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onPage(page + 1)}
        disabled={disabled || page + 1 >= pages}
      >
        Next page
      </Button>
    </nav>
  );
}
