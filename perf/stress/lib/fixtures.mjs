// Synthetic inputs for the stress runs (MO-016 §5): no real names, accounts
// or identifiers. CSVs of made-up employees, generated PDF forms (the e2e
// suite's PDF writer) and their template field regions.
import { buildPdf } from '../../../e2e/fixtures/pdf-form.js';
import { textPdf } from '../../../e2e/fixtures/pdf.js';

export const PAGE_W = 595.28;
export const PAGE_H = 841.89;

export const CSV_FIELDS = ['EmployeeId', 'FullName', 'Department', 'Email', 'Salary', 'StartDate'];
const DEPARTMENTS = ['Engineering', 'Research', 'Finance', 'Operations', 'Legal', 'Sales'];

/** `rows` synthetic employee rows; `tag` makes the bytes unique (duplicate-upload gate C6). */
export function employeesCsv(rows, tag) {
  const lines = [CSV_FIELDS.join(',')];
  for (let i = 1; i <= rows; i += 1) {
    const day = String((i % 28) + 1).padStart(2, '0');
    const month = String((i % 12) + 1).padStart(2, '0');
    lines.push(
      [
        `S-${tag}-${100000 + i}`,
        `Stress Person ${i}`,
        DEPARTMENTS[i % DEPARTMENTS.length],
        `stress.person${i}@example.test`,
        (30000 + ((i * 7919) % 90000)).toFixed(2),
        `20${String(10 + (i % 15)).padStart(2, '0')}-${month}-${day}`,
      ].join(',')
    );
  }
  return Buffer.from(lines.join('\n') + '\n');
}

/** A wide CSV: `cols` text columns F001..Fnnn, `rows` rows. */
export function wideCsv(cols, rows, tag) {
  const names = Array.from({ length: cols }, (_, c) => `F${String(c + 1).padStart(3, '0')}`);
  const lines = [names.join(',')];
  for (let r = 1; r <= rows; r += 1) {
    lines.push(names.map((_, c) => `v${tag}-${r}-${c + 1}`).join(','));
  }
  return { names, bytes: Buffer.from(lines.join('\n') + '\n') };
}

/** The doc-creation form fields (one page): label + box per field, PDF points. */
export const FORM_FIELDS = [
  { key: 'name', label: 'Name', kind: 'TEXT', text: 'Name:', box: [170, 700, 260, 20] },
  { key: 'email', label: 'E-mail', kind: 'TEXT', text: 'E-mail:', box: [170, 665, 260, 20] },
  { key: 'start_date', label: 'Start date', kind: 'DATE', text: 'Start date:', box: [170, 630, 140, 20] },
  { key: 'end_date', label: 'End date', kind: 'DATE', text: 'End date:', box: [170, 595, 140, 20] },
  { key: 'status', label: 'Status', kind: 'TEXT', text: 'Status:', box: [170, 560, 140, 20] },
  { key: 'reference', label: 'Reference', kind: 'TEXT', text: 'Reference:', box: [170, 525, 200, 20] },
  { key: 'branch', label: 'Branch', kind: 'TEXT', text: 'Branch:', box: [170, 490, 200, 20] },
  { key: 'notes', label: 'Notes', kind: 'TEXT', text: 'Notes:', box: [170, 400, 330, 70] },
];

export function formValues(n) {
  return {
    name: `Stress Person ${n}`,
    email: `stress.person${n}@example.test`,
    start_date: '2026-01-15',
    end_date: '2026-12-31',
    status: 'Active',
    reference: `REF-${n}`,
    branch: `Branch ${n % 7}`,
    notes: `Synthetic note ${n}`,
  };
}

/** One-page form PDF with FORM_FIELDS. */
export function contractForm() {
  return buildPdf((addPage) => {
    const p = addPage(PAGE_W, PAGE_H);
    p.text(72, 780, 'Stress Contract Form', { size: 16, font: 'F2' });
    for (const f of FORM_FIELDS) {
      const [x, y, w, h] = f.box;
      p.text(72, y + h - 14, f.text);
      p.rect(x, y, w, h);
    }
    p.text(72, 300, 'Synthetic test document - no real data.');
  });
}

/**
 * A large form: `pages` A4 pages, `perPage` labelled boxes each (two
 * columns), e.g. 20 x 15 = 300 fields. Returns {bytes, fields}.
 */
export function largeForm(pages, perPage) {
  const fields = [];
  const bytes = buildPdf((addPage) => {
    for (let p = 0; p < pages; p += 1) {
      const page = addPage(PAGE_W, PAGE_H);
      page.text(72, 800, `Large stress form - page ${p + 1}`, { size: 14, font: 'F2' });
      for (let i = 0; i < perPage; i += 1) {
        const col = i % 2;
        const row = Math.floor(i / 2);
        const x = col === 0 ? 120 : 380;
        const y = 760 - row * 44;
        const key = `p${p + 1}_f${i + 1}`;
        page.text(col === 0 ? 40 : 300, y + 6, `Field ${p + 1}.${i + 1}:`, { size: 8 });
        page.rect(x, y, 170, 18);
        fields.push({ key, label: `Field ${p + 1}.${i + 1}`, kind: 'TEXT', page: p, box: [x, y, 170, 18] });
      }
    }
  });
  return { bytes, fields };
}

/** The overlay region of a field: its box as top-left page fractions. */
export function regionOf(field, index) {
  const [x, y, w, h] = field.box;
  return {
    key: field.key,
    label: field.label,
    group: null,
    kind: field.kind,
    page: field.page ?? 0,
    fx: x / PAGE_W,
    fy: (PAGE_H - (y + h)) / PAGE_H,
    fw: w / PAGE_W,
    fh: h / PAGE_H,
    fontName: null,
    fontSize: 0,
    color: null,
    align: 'LEFT',
    maxLines: field.key === 'notes' ? 4 : 1,
    acroFieldName: null,
    required: false,
    sortOrder: index,
    source: 'MANUAL',
    confidence: 1,
    suggested: false,
    props: null,
  };
}

/** A free-text key:value PDF: no deterministic parser reads it, so ingestion asks the model first. */
export function keyValuePdf(tag) {
  return textPdf([
    'Remittance register (synthetic)',
    '',
    `Reference: INV-${tag}-A`,
    'Amount: 125.50',
    'Currency: MUR',
    '',
    `Reference: INV-${tag}-B`,
    'Amount: 80.00',
    'Currency: EUR',
  ]);
}

export function multipart(fields, file) {
  return () => {
    const form = new FormData();
    if (file) form.append(file.field || 'file', new Blob([file.bytes]), file.name);
    for (const [k, v] of Object.entries(fields)) form.append(k, String(v));
    return form;
  };
}
