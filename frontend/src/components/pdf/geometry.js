// Page geometry for the PDF template editor and builder. Every box is in
// top-left page fractions: { fx, fy, fw, fh } in 0..1 (contract §2).

/** Smallest box side a user can draw or resize to (0.5 % of the page). */
export const MIN_SIDE = 0.005;
/** One arrow-key step: 0.5 % of the page. */
export const KEY_STEP = 0.005;
/** Below this confidence a proposal is flagged for review. */
export const LOW_CONFIDENCE = 0.6;

export const REGION_KINDS = ['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHOICE', 'CHECKBOX', 'IMAGE', 'SIGNATURE'];
export const ALIGNS = ['LEFT', 'CENTER', 'RIGHT'];

export const KIND_LABELS = {
  TEXT: 'Text',
  MULTILINE: 'Multi-line text',
  DATE: 'Date',
  NUMBER: 'Number',
  CHOICE: 'Dropdown',
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
  CHOICE: '#6d4c41',
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
  if (!page || !['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHOICE'].includes(region.kind)) return Infinity;
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

// ------------------------------------------------- arranging several boxes

/** Arrow-key steps: Alt = fine, plain = KEY_STEP, Shift = coarse. */
export const NUDGE_STEPS = { fine: 0.001, normal: KEY_STEP, coarse: 0.025 };

const withBox = (item, box) => ({ ...item, ...clampBox(box) });

/**
 * The boxes lined up on one `edge` of their common bounds: left | hcenter |
 * right (horizontal) or top | vcenter | bottom (vertical). Sizes never
 * change; the result keeps the input order and every other property.
 */
export function alignBoxes(boxes, edge) {
  if (boxes.length < 2) return boxes.map((b) => ({ ...b }));
  const left = Math.min(...boxes.map((b) => b.fx));
  const right = Math.max(...boxes.map((b) => b.fx + b.fw));
  const top = Math.min(...boxes.map((b) => b.fy));
  const bottom = Math.max(...boxes.map((b) => b.fy + b.fh));
  return boxes.map((b) => {
    let { fx, fy } = b;
    if (edge === 'left') fx = left;
    else if (edge === 'right') fx = right - b.fw;
    else if (edge === 'hcenter') fx = (left + right) / 2 - b.fw / 2;
    else if (edge === 'top') fy = top;
    else if (edge === 'bottom') fy = bottom - b.fh;
    else if (edge === 'vcenter') fy = (top + bottom) / 2 - b.fh / 2;
    else throw new Error(`Unknown edge: ${edge}`);
    return withBox(b, { fx, fy, fw: b.fw, fh: b.fh });
  });
}

/**
 * The boxes spread evenly along `axis` ('x' = horizontally, 'y' =
 * vertically): the first and last stay put and the gaps between
 * neighbours become equal. Needs three boxes; fewer come back unchanged.
 */
export function distribute(boxes, axis) {
  if (boxes.length < 3) return boxes.map((b) => ({ ...b }));
  const pos = axis === 'x' ? 'fx' : 'fy';
  const size = axis === 'x' ? 'fw' : 'fh';
  const order = boxes.map((b, i) => ({ b, i })).sort((a, c) => a.b[pos] - c.b[pos] || a.i - c.i);
  const first = order[0].b;
  const last = order[order.length - 1].b;
  const span = last[pos] + last[size] - first[pos];
  const sizes = order.reduce((sum, { b }) => sum + b[size], 0);
  const gap = (span - sizes) / (order.length - 1);
  const result = boxes.map((b) => ({ ...b }));
  let cursor = first[pos];
  for (const { b, i } of order) {
    result[i] = withBox(b, { ...b, [pos]: cursor });
    cursor += b[size] + gap;
  }
  return result;
}

/**
 * The boxes given the width ('width'), height ('height') or both ('both')
 * of `refBox` (default: the first box). Each keeps its top-left corner.
 */
export function matchSize(boxes, dim, refBox = boxes[0]) {
  if (!refBox) return [];
  return boxes.map((b) =>
    withBox(b, {
      fx: b.fx,
      fy: b.fy,
      fw: dim === 'width' || dim === 'both' ? refBox.fw : b.fw,
      fh: dim === 'height' || dim === 'both' ? refBox.fh : b.fh,
    })
  );
}

/** Grid step and pull distance of the Snap option (page fractions). */
export const SNAP_GRID = 0.01;
export const SNAP_THRESHOLD = 0.006;

/** The closest of `lines` to any of `anchors` within `threshold`: { delta, at } or null. */
function nearest(anchors, lines, threshold) {
  let best = null;
  for (const anchor of anchors) {
    for (const line of lines) {
      const delta = line - anchor;
      if (Math.abs(delta) <= threshold && (!best || Math.abs(delta) < Math.abs(best.delta))) {
        best = { delta, at: line };
      }
    }
  }
  return best;
}

/**
 * Snaps a box being moved (`mode` 'move') or resized from a corner
 * (`mode` 'nw' | 'ne' | 'sw' | 'se') to the edges and centres of `others`,
 * else to the `grid`. Returns `{ box, guides }`, where each guide is
 * `{ axis: 'x' | 'y', at }`: a vertical (x) or horizontal (y) line the box
 * snapped to, for drawing. Grid snaps draw no guide.
 */
export function snapBox(box, others = [], { grid = SNAP_GRID, threshold = SNAP_THRESHOLD, mode = 'move' } = {}) {
  const guides = [];
  const xLines = others.flatMap((o) => [o.fx, o.fx + o.fw / 2, o.fx + o.fw]);
  const yLines = others.flatMap((o) => [o.fy, o.fy + o.fh / 2, o.fy + o.fh]);
  const toGrid = (value) => (grid > 0 ? Math.round(value / grid) * grid - value : 0);
  const pull = (anchors, lines, axis, gridAnchor) => {
    const hit = nearest(anchors, lines, threshold);
    if (hit) {
      guides.push({ axis, at: round(hit.at) });
      return hit.delta;
    }
    const delta = toGrid(gridAnchor);
    return Math.abs(delta) <= threshold ? delta : 0;
  };

  let left = box.fx;
  let top = box.fy;
  let right = box.fx + box.fw;
  let bottom = box.fy + box.fh;
  if (mode === 'move') {
    const dx = pull([left, (left + right) / 2, right], xLines, 'x', left);
    const dy = pull([top, (top + bottom) / 2, bottom], yLines, 'y', top);
    return { box: { ...box, ...clampBox({ fx: left + dx, fy: top + dy, fw: box.fw, fh: box.fh }) }, guides };
  }
  if (mode.includes('w')) left += pull([left], xLines, 'x', left);
  if (mode.includes('e')) right += pull([right], xLines, 'x', right);
  if (mode.includes('n')) top += pull([top], yLines, 'y', top);
  if (mode.includes('s')) bottom += pull([bottom], yLines, 'y', bottom);
  return {
    box: { ...box, ...clampBox({ fx: left, fy: top, fw: right - left, fh: bottom - top }) },
    guides,
  };
}
