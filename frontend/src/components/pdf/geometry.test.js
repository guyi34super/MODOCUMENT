import { describe, expect, it } from 'vitest';
import {
  alignBoxes,
  boxFromPoints,
  distribute,
  matchSize,
  snapBox,
  clampBox,
  displaySize,
  estimateOverflow,
  moveBox,
  overlapArea,
  resizeBox,
  slugify,
  uniqueKey,
} from './geometry';

const A4 = { index: 0, width: 595, height: 842, rotation: 0 };

describe('pdf geometry', () => {
  it('keeps boxes on the page', () => {
    expect(clampBox({ fx: -0.1, fy: 0.95, fw: 0.2, fh: 0.1 })).toEqual({ fx: 0, fy: 0.9, fw: 0.2, fh: 0.1 });
    expect(moveBox({ fx: 0.8, fy: 0.1, fw: 0.3, fh: 0.1 }, 0.5, -0.5)).toEqual({ fx: 0.7, fy: 0, fw: 0.3, fh: 0.1 });
  });

  it('resizes from a corner, keeping the opposite corner fixed and a minimum size', () => {
    const box = { fx: 0.2, fy: 0.2, fw: 0.2, fh: 0.1 };
    expect(resizeBox(box, 'se', 0.1, 0.05)).toEqual({ fx: 0.2, fy: 0.2, fw: 0.3, fh: 0.15 });
    expect(resizeBox(box, 'nw', -0.1, -0.1)).toEqual({ fx: 0.1, fy: 0.1, fw: 0.3, fh: 0.2 });
    expect(resizeBox(box, 'ne', -0.5, 0).fw).toBe(0.005);
    expect(resizeBox(box, 'se', 2, 2)).toEqual({ fx: 0.2, fy: 0.2, fw: 0.8, fh: 0.8 });
  });

  it('draws a box from any two corners', () => {
    expect(boxFromPoints({ x: 0.5, y: 0.6 }, { x: 0.2, y: 0.1 })).toEqual({ fx: 0.2, fy: 0.1, fw: 0.3, fh: 0.5 });
  });

  it('measures overlaps only on the same page', () => {
    const a = { page: 0, fx: 0, fy: 0, fw: 0.2, fh: 0.2 };
    expect(overlapArea(a, { ...a, fx: 0.1 })).toBeCloseTo(0.02);
    expect(overlapArea(a, { ...a, fx: 0.2 })).toBe(0);
    expect(overlapArea(a, { ...a, page: 1 })).toBe(0);
  });

  it('slugs labels into unique keys', () => {
    expect(slugify('Date of Birth (DD/MM)')).toBe('date_of_birth_dd_mm');
    expect(slugify('Prénom')).toBe('prenom');
    expect(slugify('  ')).toBe('field');
    expect(uniqueKey('Name', new Set(['name', 'name_2']))).toBe('name_3');
  });

  it('swaps width and height of rotated pages', () => {
    expect(displaySize({ ...A4, rotation: 90 })).toEqual({ width: 842, height: 595 });
  });

  it('estimates when text will not fit even at the minimum font size', () => {
    // 0.1 × 595 pt ≈ 59.5 pt wide → 19 chars per line at 6 pt.
    const text = { kind: 'TEXT', fw: 0.1, fh: 0.02, maxLines: 1 };
    expect(estimateOverflow(text, 'a'.repeat(19), A4)).toBe(false);
    expect(estimateOverflow(text, 'a'.repeat(20), A4)).toBe(true);
    const notes = { kind: 'MULTILINE', fw: 0.1, fh: 0.02, maxLines: 2 };
    expect(estimateOverflow(notes, 'short\nlines', A4)).toBe(false);
    expect(estimateOverflow(notes, 'a\nb\nc', A4)).toBe(true);
    expect(estimateOverflow({ kind: 'CHECKBOX', fw: 0.01, fh: 0.01 }, 'true', A4)).toBe(false);
  });
});

describe('arranging boxes', () => {
  const a = { _uid: 'a', fx: 0.1, fy: 0.1, fw: 0.2, fh: 0.05 };
  const b = { _uid: 'b', fx: 0.4, fy: 0.3, fw: 0.1, fh: 0.1 };
  const c = { _uid: 'c', fx: 0.25, fy: 0.6, fw: 0.3, fh: 0.02 };

  it('aligns edges and centres to the common bounds, keeping sizes and other properties', () => {
    expect(alignBoxes([a, b, c], 'left').map((x) => x.fx)).toEqual([0.1, 0.1, 0.1]);
    expect(alignBoxes([a, b, c], 'right').map((x) => x.fx + x.fw)).toEqual([0.55, 0.55, 0.55]);
    const centred = alignBoxes([a, b], 'hcenter');
    expect(centred[0].fx + centred[0].fw / 2).toBeCloseTo(0.3);
    expect(centred[1].fx + centred[1].fw / 2).toBeCloseTo(0.3);
    expect(alignBoxes([a, b, c], 'top').map((x) => x.fy)).toEqual([0.1, 0.1, 0.1]);
    expect(alignBoxes([a, b, c], 'bottom').map((x) => x.fy + x.fh)).toEqual([0.62, 0.62, 0.62]);
    const middle = alignBoxes([a, c], 'vcenter');
    expect(middle[0].fy + middle[0].fh / 2).toBeCloseTo(middle[1].fy + middle[1].fh / 2);
    expect(alignBoxes([a, b], 'left')[1]).toMatchObject({ _uid: 'b', fw: 0.1, fh: 0.1, fy: 0.3 });
    expect(alignBoxes([a], 'left')).toEqual([a]);
    expect(() => alignBoxes([a, b], 'diagonal')).toThrow();
  });

  it('distributes three or more boxes with equal gaps, the outer ones staying put', () => {
    const spread = distribute([b, a, c], 'x'); // order by fx: a (0.1), c (0.25), b (0.4)
    const [db, da, dc] = spread;
    expect(da.fx).toBe(0.1);
    expect(db.fx).toBe(0.4);
    // span 0.1..0.5 = 0.4, sizes 0.6 → gaps of -0.1 (they overlap evenly)
    expect(dc.fx).toBeCloseTo(0.2);
    const rows = distribute(
      [
        { fx: 0, fy: 0, fw: 0.1, fh: 0.1 },
        { fx: 0, fy: 0.15, fw: 0.1, fh: 0.1 },
        { fx: 0, fy: 0.7, fw: 0.1, fh: 0.1 },
      ],
      'y'
    );
    expect(rows.map((r) => r.fy)).toEqual([0, 0.35, 0.7]);
    expect(distribute([a, b], 'x')).toEqual([a, b]);
  });

  it('gives every box the width, height or size of the reference box', () => {
    expect(matchSize([a, b], 'width').map((x) => x.fw)).toEqual([0.2, 0.2]);
    expect(matchSize([a, b], 'height', b).map((x) => x.fh)).toEqual([0.1, 0.1]);
    expect(matchSize([a, b, c], 'both', c).map((x) => [x.fw, x.fh])).toEqual([
      [0.3, 0.02],
      [0.3, 0.02],
      [0.3, 0.02],
    ]);
    expect(matchSize([a, b], 'width')[1]).toMatchObject({ fx: 0.4, fy: 0.3 });
  });

  it('snaps a moved box to other boxes edges and centres with guides, else to the grid', () => {
    const other = { fx: 0.5, fy: 0.5, fw: 0.2, fh: 0.1 };
    // Left edge 0.497 → 0.5 (other's left), top 0.302 → the 1 % grid.
    const left = snapBox({ fx: 0.497, fy: 0.302, fw: 0.1, fh: 0.05 }, [other]);
    expect(left.box).toMatchObject({ fx: 0.5, fy: 0.3 });
    expect(left.guides).toEqual([{ axis: 'x', at: 0.5 }]);
    // Centre 0.598 → other's centre 0.6.
    const centre = snapBox({ fx: 0.548, fy: 0.2, fw: 0.1, fh: 0.05 }, [other]);
    expect(centre.box.fx).toBeCloseTo(0.55);
    expect(centre.guides).toEqual([{ axis: 'x', at: 0.6 }]);
    // Bottom edge 0.598 → other's bottom 0.6.
    const bottom = snapBox({ fx: 0.123, fy: 0.548, fw: 0.1, fh: 0.05 }, [other], { grid: 0 });
    expect(bottom.box.fy).toBeCloseTo(0.55);
    expect(bottom.box.fx).toBe(0.123);
    expect(bottom.guides).toEqual([{ axis: 'y', at: 0.6 }]);
    // Too far from anything: unchanged without a grid.
    expect(snapBox({ fx: 0.123, fy: 0.234, fw: 0.1, fh: 0.05 }, [other], { grid: 0 }).box).toMatchObject({
      fx: 0.123,
      fy: 0.234,
    });
  });

  it('snaps only the dragged edges of a resized box', () => {
    const other = { fx: 0.5, fy: 0.5, fw: 0.2, fh: 0.1 };
    const { box, guides } = snapBox({ fx: 0.1, fy: 0.1, fw: 0.397, fh: 0.0 + 0.123 }, [other], { mode: 'se', grid: 0 });
    expect(box.fx).toBe(0.1);
    expect(box.fx + box.fw).toBeCloseTo(0.5);
    expect(box.fh).toBeCloseTo(0.123);
    expect(guides).toEqual([{ axis: 'x', at: 0.5 }]);
  });
});
