// Page geometry for the PDF template editor and builder. Every box is in
// top-left page fractions: { fx, fy, fw, fh } in 0..1 (contract §2).

/** Smallest box side a user can draw or resize to (0.5 % of the page). */
export const MIN_SIDE = 0.005;
/** One arrow-key step: 0.5 % of the page. */
export const KEY_STEP = 0.005;
/** Below this confidence a proposal is flagged for review. */
export const LOW_CONFIDENCE = 0.6;

export const REGION_KINDS = ['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHECKBOX', 'IMAGE', 'SIGNATURE'];
export const ALIGNS = ['LEFT', 'CENTER', 'RIGHT'];

export const KIND_LABELS = {
  TEXT: 'Text',
  MULTILINE: 'Multi-line text',
  DATE: 'Date',
  NUMBER: 'Number',
  CHECKBOX: 'Checkbox',
  IMAGE: 'Photo / image',
  SIGNATURE: 'Signature',
};

/** Border / fill colours per region kind (text is always dark on the label tab). */
export const KIND_COLORS = {
  TEXT: '#1a73e8',
  MULTILINE: '#5e35b1',
  DATE: '#00897b',
  NUMBER: '#0277bd',
  CHECKBOX: '#2e7d32',
  IMAGE: '#ef6c00',
  SIGNATURE: '#ad1457',
};

export const EDIT_LABELS = {
  REMOVE: 'Removed content',
  REPLACE_TEXT: 'Replaced text',
  ADD_TEXT: 'Added text',
  ADD_IMAGE: 'Added image',
};

const round = (n) => Math.round(n * 1e5) / 1e5;
const clamp01 = (n) => Math.min(1, Math.max(0, n));

/** Keeps a box inside the page with sides of at least MIN_SIDE. */
export function clampBox({ fx, fy, fw, fh }) {
  const w = Math.min(1, Math.max(MIN_SIDE, fw));
  const h = Math.min(1, Math.max(MIN_SIDE, fh));
  return {
    fx: round(Math.min(1 - w, Math.max(0, fx))),
    fy: round(Math.min(1 - h, Math.max(0, fy))),
    fw: round(w),
    fh: round(h),
  };
}

/** The box moved by (dx, dy) fractions, kept on the page. */
export function moveBox(box, dx, dy) {
  return clampBox({ ...box, fx: box.fx + dx, fy: box.fy + dy });
}

/**
 * The box resized by dragging `corner` ('nw' | 'ne' | 'sw' | 'se') by
 * (dx, dy); the opposite corner stays put and the box stays on the page.
 */
export function resizeBox(box, corner, dx, dy) {
  let left = box.fx;
  let top = box.fy;
  let right = box.fx + box.fw;
  let bottom = box.fy + box.fh;
  if (corner.includes('w')) left = Math.min(right - MIN_SIDE, clamp01(left + dx));
  if (corner.includes('e')) right = Math.max(left + MIN_SIDE, clamp01(right + dx));
  if (corner.includes('n')) top = Math.min(bottom - MIN_SIDE, clamp01(top + dy));
  if (corner.includes('s')) bottom = Math.max(top + MIN_SIDE, clamp01(bottom + dy));
  return clampBox({ fx: left, fy: top, fw: right - left, fh: bottom - top });
}

/** The box spanned by two points (fractions), clamped to the page. */
export function boxFromPoints(a, b) {
  const x1 = clamp01(Math.min(a.x, b.x));
  const y1 = clamp01(Math.min(a.y, b.y));
  const x2 = clamp01(Math.max(a.x, b.x));
  const y2 = clamp01(Math.max(a.y, b.y));
  return { fx: round(x1), fy: round(y1), fw: round(x2 - x1), fh: round(y2 - y1) };
}

/** A pointer event's position as page fractions of `element`'s box. */
export function pointToFraction(event, element) {
  const rect = element.getBoundingClientRect();
  if (!rect.width || !rect.height) return { x: 0, y: 0 };
  return {
    x: clamp01((event.clientX - rect.left) / rect.width),
    y: clamp01((event.clientY - rect.top) / rect.height),
  };
}

/** Overlap area of two boxes on the same page (0 when they only touch). */
export function overlapArea(a, b) {
  if (a.page !== b.page) return 0;
  const w = Math.min(a.fx + a.fw, b.fx + b.fw) - Math.max(a.fx, b.fx);
  const h = Math.min(a.fy + a.fh, b.fy + b.fh) - Math.max(a.fy, b.fy);
  return w > 1e-6 && h > 1e-6 ? w * h : 0;
}

/** A field key from a label: [a-z0-9_]{1,64}. */
export function slugify(label) {
  const slug = String(label || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 64)
    .replace(/_+$/g, '');
  return slug || 'field';
}

export const KEY_PATTERN = /^[a-z0-9_]{1,64}$/;

/** `base` made unique against `taken` (a Set) with a _2, _3… suffix. */
export function uniqueKey(base, taken) {
  const root = slugify(base);
  if (!taken.has(root)) return root;
  for (let n = 2; ; n += 1) {
    const suffix = `_${n}`;
    const candidate = `${root.slice(0, 64 - suffix.length)}${suffix}`;
    if (!taken.has(candidate)) return candidate;
  }
}

export function newEditId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/**
 * The page as displayed: the backend renders page images already rotated,
 * so a 90°/270° page shows with width and height swapped.
 */
export function displaySize(page) {
  if (!page) return { width: 595.3, height: 841.9 };
  const turned = page.rotation === 90 || page.rotation === 270;
  return turned
    ? { width: page.height, height: page.width }
    : { width: page.width, height: page.height };
}

/** Points per average glyph at a font size (a Helvetica-ish 0.5 em). */
const AVG_GLYPH_EM = 0.5;
const LINE_HEIGHT_EM = 1.2;

/**
 * How many characters a region holds before the generator has to continue
 * on an added page: text is shrunk down to `minFontSize` first, so the
 * capacity is measured there. `page` is the PageInfo (points). A rough,
 * deliberately generous estimate — the server's `overflow` list is the
 * authority.
 */
export function textCapacity(region, page, minFontSize = 6) {
  if (!page || !['TEXT', 'MULTILINE', 'DATE', 'NUMBER'].includes(region.kind)) return Infinity;
  const size = displaySize(page);
  const widthPt = region.fw * size.width;
  const heightPt = region.fh * size.height;
  const perLine = Math.max(1, Math.floor(widthPt / (minFontSize * AVG_GLYPH_EM)));
  let lines = 1;
  if (region.kind === 'MULTILINE') {
    const fit = Math.max(1, Math.floor(heightPt / (minFontSize * LINE_HEIGHT_EM)));
    lines = region.maxLines > 0 ? Math.min(region.maxLines, fit) : fit;
  }
  return perLine * lines;
}

/** True when `value` probably does not fit `region` (see textCapacity). */
export function estimateOverflow(region, value, page, minFontSize = 6) {
  if (!value) return false;
  const capacity = textCapacity(region, page, minFontSize);
  if (!Number.isFinite(capacity)) return false;
  const text = String(value);
  if (region.kind !== 'MULTILINE') return text.length > capacity;
  const perLine = textCapacity({ ...region, kind: 'TEXT' }, page, minFontSize);
  // Every explicit line break starts a new line.
  const usedLines = text
    .split('\n')
    .reduce((sum, line) => sum + Math.max(1, Math.ceil(line.length / perLine)), 0);
  return usedLines * perLine > capacity;
}
