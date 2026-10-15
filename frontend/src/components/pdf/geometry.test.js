import { describe, expect, it } from 'vitest';
import {
  boxFromPoints,
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
