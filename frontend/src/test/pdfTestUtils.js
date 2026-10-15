import { vi } from 'vitest';

/**
 * jsdom has no PointerEvent (nor layout): these helpers give the PDF page
 * components what a browser would — pointer events carrying clientX/Y, a
 * fixed 1000×1000 px box for every element (so 10 px = 1 % of the page),
 * a fake DataTransfer for HTML5 drag and drop, and Bearer-image fetches
 * that resolve to an object URL.
 */
export function installPointerEvents() {
  if (typeof window.PointerEvent === 'function') return;
  class PointerEventPolyfill extends MouseEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
      this.pointerType = init.pointerType ?? 'mouse';
    }
  }
  window.PointerEvent = PointerEventPolyfill;
}

export const PAGE_PX = 1000;

export function mockLayout(size = PAGE_PX) {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
    x: 0,
    y: 0,
    left: 0,
    top: 0,
    width: size,
    height: size,
    right: size,
    bottom: size,
    toJSON() {},
  });
}

export function fakeDataTransfer() {
  const store = new Map();
  return {
    dropEffect: 'none',
    effectAllowed: 'all',
    get types() {
      return [...store.keys()];
    },
    setData: (type, value) => store.set(type, String(value)),
    getData: (type) => store.get(type) ?? '',
    clearData: () => store.clear(),
    files: [],
    items: [],
  };
}

/** AuthedImage fetches page PNGs with the Bearer header: answer with a tiny blob. */
export function stubImageFetch() {
  const fetchMock = vi.fn().mockImplementation(
    () => Promise.resolve(new Response(new Blob([new Uint8Array([137, 80, 78, 71])]), { status: 200 }))
  );
  vi.stubGlobal('fetch', fetchMock);
  URL.createObjectURL = vi.fn(() => 'blob:page');
  URL.revokeObjectURL = vi.fn();
  return fetchMock;
}
