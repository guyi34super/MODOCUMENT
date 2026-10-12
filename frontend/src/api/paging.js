// v1.2.0 paged lists (docs/optimization/contracts.md §5): list endpoints take
// `page` (0-based) and `size` (≤ 200) and answer the envelope
// `{items, page, size, total}`. A server that predates paging answers a bare
// array, which is shown as a single page — so this UI works against either.

export const DEFAULT_PAGE_SIZE = 50;
export const MAX_PAGE_SIZE = 200;

/** `path?…params&page=&size=`, dropping empty params. */
export function pagedPath(path, { page = 0, size = DEFAULT_PAGE_SIZE, ...params } = {}) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    query.append(key, String(value));
  }
  query.set('page', String(Math.max(0, page)));
  query.set('size', String(Math.min(MAX_PAGE_SIZE, Math.max(1, size))));
  return `${path}?${query.toString()}`;
}

/**
 * Normalises a list answer to `{items, page, size, total, paged}`. `paged`
 * is false for a bare array (an older server): every row is on this one page.
 */
export function toPage(data, { page = 0, size = DEFAULT_PAGE_SIZE } = {}) {
  if (Array.isArray(data)) {
    return { items: data, page: 0, size: Math.max(size, data.length), total: data.length, paged: false };
  }
  const items = Array.isArray(data?.items) ? data.items : [];
  return {
    items,
    page: Number.isInteger(data?.page) ? data.page : page,
    size: Number.isInteger(data?.size) && data.size > 0 ? data.size : size,
    total: Number.isInteger(data?.total) ? data.total : items.length,
    paged: true,
  };
}
