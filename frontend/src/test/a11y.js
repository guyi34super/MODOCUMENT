import { expect } from 'vitest';
import { computeAccessibleName } from 'dom-accessibility-api';

/**
 * Targeted accessibility checks for rendered pages (no axe dependency):
 * the rules the phase-2 pages are held to. Each problem is a readable line,
 * so a failing assertion lists everything at once.
 *
 *  - every visible form control, button, link and widget role has an
 *    accessible name;
 *  - every table is named (caption, aria-label or aria-labelledby);
 *  - role="img" and <img> carry a text alternative;
 *  - aria-labelledby / aria-describedby / aria-controls point at real ids,
 *    and ids are unique;
 *  - headings never skip a level (h1 → h3), per page and per dialog (a
 *    dialog's title is its h2), and a page has at most one h1.
 */
const NAMED =
  'input:not([type="hidden"]), select, textarea, button, a[href], ' +
  '[role="button"], [role="switch"], [role="checkbox"], [role="radio"], [role="tab"], ' +
  '[role="combobox"], [role="dialog"], [role="slider"], [role="progressbar"]';

function hiddenFromAt(el) {
  return Boolean(el.closest('[hidden], [aria-hidden="true"], [inert]'));
}

function describe(el) {
  const html = el.outerHTML.replace(/\s+/g, ' ');
  return html.length > 140 ? `${html.slice(0, 140)}…` : html;
}

function headingProblems(scope, startLevel, label, problems) {
  const headings = Array.from(scope.querySelectorAll('h1, h2, h3, h4, h5, h6')).filter(
    (h) => !hiddenFromAt(h) && (label !== 'page' || !h.closest('[role="dialog"]'))
  );
  let previous = startLevel;
  let h1s = 0;
  for (const h of headings) {
    const level = Number(h.tagName[1]);
    if (level === 1) h1s += 1;
    if (level > previous + 1) {
      problems.push(`${label}: heading level skips from h${previous} to h${level}: "${h.textContent.trim()}"`);
    }
    previous = level;
  }
  if (h1s > 1) problems.push(`${label}: ${h1s} h1 headings`);
}

export function a11yProblems(root = document.body) {
  const problems = [];

  for (const el of root.querySelectorAll(NAMED)) {
    if (hiddenFromAt(el)) continue;
    if (!computeAccessibleName(el).trim()) problems.push(`unnamed control: ${describe(el)}`);
  }

  for (const table of root.querySelectorAll('table')) {
    if (hiddenFromAt(table)) continue;
    if (!computeAccessibleName(table).trim()) {
      problems.push(`table without caption or aria-label: ${describe(table)}`);
    }
  }

  for (const el of root.querySelectorAll('img, [role="img"]')) {
    if (hiddenFromAt(el)) continue;
    if (!computeAccessibleName(el).trim()) problems.push(`image without a text alternative: ${describe(el)}`);
  }

  for (const el of root.querySelectorAll('[aria-labelledby], [aria-describedby], [aria-controls]')) {
    for (const attr of ['aria-labelledby', 'aria-describedby', 'aria-controls']) {
      const ids = (el.getAttribute(attr) || '').split(/\s+/).filter(Boolean);
      for (const id of ids) {
        if (!el.ownerDocument.getElementById(id)) {
          problems.push(`${attr}="${id}" points at no element: ${describe(el)}`);
        }
      }
    }
  }

  const seen = new Map();
  for (const el of root.querySelectorAll('[id]')) {
    seen.set(el.id, (seen.get(el.id) || 0) + 1);
  }
  for (const [id, count] of seen) {
    if (count > 1) problems.push(`duplicate id "${id}" (${count}×)`);
  }

  headingProblems(root, 1, 'page', problems);
  for (const dialog of root.ownerDocument.querySelectorAll('[role="dialog"]')) {
    headingProblems(dialog, 1, `dialog "${computeAccessibleName(dialog)}"`, problems);
  }
  return problems;
}

/** Asserts the rendered document has none of the problems above. */
export function expectAccessible(root = document.body) {
  expect(a11yProblems(root)).toEqual([]);
}
