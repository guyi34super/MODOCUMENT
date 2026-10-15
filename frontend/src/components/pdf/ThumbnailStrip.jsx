import AuthedImage from '../AuthedImage';

/** Thumbnails beyond this many pages would be a request storm: number buttons only. */
const MAX_THUMBNAILS = 40;

/**
 * Page navigator: previous/next, "Page x of n", and one button per page
 * (a small server-rendered thumbnail when `pathFor(index)` is given and the
 * document is short). `badges[index]` adds a note under a page, such as
 * its field count.
 */
export default function ThumbnailStrip({ pages, current, onSelect, pathFor, badges, label = 'Pages' }) {
  const count = pages?.length || 0;
  if (count === 0) return null;
  const thumbnails = pathFor && count <= MAX_THUMBNAILS;
  return (
    <nav aria-label={label} className="mb-3">
      <div className="mb-2 flex items-center gap-2 text-sm">
        <button
          type="button"
          className="rounded border border-line px-2 py-0.5 text-accent hover:bg-pill/50 disabled:opacity-40"
          onClick={() => onSelect(current - 1)}
          disabled={current <= 0}
          aria-label="Previous page"
        >
          ‹
        </button>
        <span aria-live="polite">
          Page {current + 1} of {count}
        </span>
        <button
          type="button"
          className="rounded border border-line px-2 py-0.5 text-accent hover:bg-pill/50 disabled:opacity-40"
          onClick={() => onSelect(current + 1)}
          disabled={current >= count - 1}
          aria-label="Next page"
        >
          ›
        </button>
      </div>
      {count > 1 && (
        <ul className="flex gap-2 overflow-x-auto pb-1">
          {pages.map((page, index) => (
            <li key={page.index ?? index} className="shrink-0">
              <button
                type="button"
                aria-label={`Page ${index + 1}${badges?.[index] ? `, ${badges[index]}` : ''}`}
                aria-current={index === current ? 'page' : undefined}
                onClick={() => onSelect(index)}
                className={`flex flex-col items-center rounded border p-1 text-[11px] hover:bg-pill/50 ${
                  index === current ? 'border-accent ring-2 ring-pill' : 'border-line'
                }`}
              >
                {thumbnails && (
                  <AuthedImage
                    path={pathFor(index)}
                    alt={`Page ${index + 1} thumbnail`}
                    className="mb-0.5 h-16 w-12 object-contain"
                  />
                )}
                <span aria-hidden="true">{index + 1}</span>
                {badges?.[index] && (
                  <span aria-hidden="true" className="text-muted">
                    {badges[index]}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </nav>
  );
}
