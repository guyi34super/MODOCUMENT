import { describe, expect, it } from 'vitest';
import { pagedPath, toPage } from './paging';

describe('paging helpers', () => {
  it('builds page/size queries and drops empty filters', () => {
    expect(pagedPath('/api/admin/changes', { page: 2, size: 25, status: 'REQUESTED', flowId: '' })).toBe(
      '/api/admin/changes?status=REQUESTED&page=2&size=25'
    );
    expect(pagedPath('/api/x', { size: 999 })).toBe('/api/x?page=0&size=200');
  });

  it('reads the {items, page, size, total} envelope', () => {
    expect(toPage({ items: [1, 2], page: 1, size: 2, total: 5 })).toEqual({
      items: [1, 2],
      page: 1,
      size: 2,
      total: 5,
      paged: true,
    });
  });

  it('shows a bare array (an older server) as one page', () => {
    expect(toPage([1, 2, 3], { page: 0, size: 2 })).toEqual({
      items: [1, 2, 3],
      page: 0,
      size: 3,
      total: 3,
      paged: false,
    });
  });
});
