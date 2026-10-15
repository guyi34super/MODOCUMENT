// Hand-written multi-page PDF writer for the exact-PDF-template specs:
// Helvetica text at positions, stroked lines and boxes, and embedded raster
// images (a logo, a photo) — the shapes the template engine detects. Content
// streams are written UNCOMPRESSED so a spec can see what a file carries.
//
// employeeForm({ filled }) is the spec's form: the same two-page "Employee
// details" form, blank or filled with FORM_VALUES. The filled twin draws
// exactly the blank's content plus the data, so removing the data must give
// the blank twin back, pixel for pixel.
import zlib from 'node:zlib';
import { raster } from './png.js';

// Helvetica advance widths (AFM, 1/1000 em) for printable ASCII 32..126.
const HELVETICA = [
  278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // space .. /
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0 .. ?
  1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ .. O
  667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P .. _
  333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` .. o
  556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, // p .. ~
];

/** Width of `text` in Helvetica at `size` points. */
export function helveticaWidth(text, size) {
  let units = 0;
  for (const ch of String(text)) {
    const code = ch.charCodeAt(0);
    units += code >= 32 && code <= 126 ? HELVETICA[code - 32] : 556;
  }
  return (units / 1000) * size;
}

const escapePdf = (text) => String(text).replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
const n = (value) => Number(value.toFixed(3)).toString();

/** One page under construction: drawing calls append content-stream operators. */
class PageBuilder {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.ops = [];
    this.images = [];
  }

  /** Text at baseline (x, y); font 'F1' Helvetica or 'F2' Helvetica-Bold. */
  text(x, y, value, { size = 10, font = 'F1' } = {}) {
    this.ops.push(`BT /${font} ${n(size)} Tf ${n(x)} ${n(y)} Td (${escapePdf(value)}) Tj ET`);
    return this;
  }

  rect(x, y, w, h, { lineWidth = 0.8 } = {}) {
    this.ops.push(`${n(lineWidth)} w ${n(x)} ${n(y)} ${n(w)} ${n(h)} re S`);
    return this;
  }

  line(x0, y0, x1, y1, { lineWidth = 0.8 } = {}) {
    this.ops.push(`${n(lineWidth)} w ${n(x0)} ${n(y0)} m ${n(x1)} ${n(y1)} l S`);
    return this;
  }

  /** An RGB raster (pixelWidth x pixelHeight, 3 bytes per pixel) drawn into the box. */
  image(x, y, w, h, { pixelWidth, pixelHeight, rgb }) {
    const name = `Im${this.images.length + 1}`;
    this.images.push({ name, pixelWidth, pixelHeight, rgb });
    this.ops.push(`q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(y)} cm /${name} Do Q`);
    return this;
  }
}

/** Serialises pages built by `draw(addPage)` into a PDF 1.4 file. */
export function buildPdf(draw) {
  const pages = [];
  draw((width = 595.28, height = 841.89) => {
    const page = new PageBuilder(width, height);
    pages.push(page);
    return page;
  });

  const objects = []; // index i holds object number i + 1
  const reserve = () => objects.push(null);
  const set = (num, body) => {
    objects[num - 1] = body;
  };
  const catalog = reserve();
  const pagesRoot = reserve();
  const helvetica = reserve();
  const helveticaBold = reserve();
  set(helvetica, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');
  set(helveticaBold, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>');

  const kids = [];
  for (const page of pages) {
    const xobjects = [];
    for (const image of page.images) {
      const data = zlib.deflateSync(Buffer.from(image.rgb));
      const num = reserve();
      set(num, {
        dict:
          `<< /Type /XObject /Subtype /Image /Width ${image.pixelWidth} /Height ${image.pixelHeight} ` +
          `/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${data.length} >>`,
        data,
      });
      xobjects.push(`/${image.name} ${num} 0 R`);
    }
    const content = Buffer.from(page.ops.join('\n'), 'latin1');
    const contentNum = reserve();
    set(contentNum, { dict: `<< /Length ${content.length} >>`, data: content });
    const pageNum = reserve();
    set(
      pageNum,
      `<< /Type /Page /Parent ${pagesRoot} 0 R /MediaBox [0 0 ${n(page.width)} ${n(page.height)}] ` +
        `/Contents ${contentNum} 0 R /Resources << /Font << /F1 ${helvetica} 0 R /F2 ${helveticaBold} 0 R >>` +
        `${xobjects.length ? ` /XObject << ${xobjects.join(' ')} >>` : ''} >> >>`
    );
    kids.push(`${pageNum} 0 R`);
  }
  set(catalog, `<< /Type /Catalog /Pages ${pagesRoot} 0 R >>`);
  set(pagesRoot, `<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${kids.length} >>`);

  const chunks = [Buffer.from('%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', 'latin1')];
  let length = chunks[0].length;
  const offsets = [];
  objects.forEach((body, i) => {
    offsets.push(length);
    const parts =
      typeof body === 'string'
        ? [Buffer.from(`${i + 1} 0 obj\n${body}\nendobj\n`, 'latin1')]
        : [
            Buffer.from(`${i + 1} 0 obj\n${body.dict}\nstream\n`, 'latin1'),
            body.data,
            Buffer.from('\nendstream\nendobj\n', 'latin1'),
          ];
    for (const part of parts) {
      chunks.push(part);
      length += part.length;
    }
  });
  let trailer = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) trailer += `${String(offset).padStart(10, '0')} 00000 n \n`;
  trailer += `trailer\n<< /Size ${objects.length + 1} /Root ${catalog} 0 R >>\nstartxref\n${length}\n%%EOF\n`;
  chunks.push(Buffer.from(trailer, 'latin1'));
  return Buffer.concat(chunks);
}

// ------------------------------------------------------------- the form

/** The data of the filled twin (also what a spec types into a new document). */
export const FORM_VALUES = {
  fullName: 'John Smith',
  dateOfBirth: '14/02/1985',
  employeeId: 'EMP-20931',
  department: 'Finance',
  signatureDate: '01/03/2024',
  // Free-standing on page 2, next to no label: proposed with low confidence.
  contact: 'john.smith@example.com',
};

const LOGO = {
  pixelWidth: 48,
  pixelHeight: 48,
  rgb: raster(48, 48, (x, y) => {
    const dx = x - 23.5;
    const dy = y - 23.5;
    if (Math.abs(x - (47 - y)) < 3 && x > 10 && x < 38) return [240, 160, 30];
    return dx * dx + dy * dy < 22 * 22 ? [31, 78, 121] : [255, 255, 255];
  }),
};

const PHOTO = {
  pixelWidth: 84,
  pixelHeight: 94,
  rgb: raster(84, 94, (x, y) => {
    const dx = x - 42;
    const dy = y - 32;
    if (dx * dx + dy * dy < 20 * 20) return [240, 220, 200];
    const t = (x + y) / 178;
    return [Math.round(70 + 130 * t), Math.round(110 + 60 * t), Math.round(160 - 40 * t)];
  }),
};

/**
 * The "Employee details" form, A4, two pages:
 *   page 1 — logo + title, Full name / Date of birth on underscore leaders,
 *            Employee ID / Department boxes, a Photo box, Signature / Date lines;
 *   page 2 — a large Notes box.
 * `filled` draws FORM_VALUES (and a photo) on top of the identical blank form.
 */
export function employeeForm({ filled = false } = {}) {
  return buildPdf((addPage) => {
    const p1 = addPage();
    p1.image(72, 770, 40, 40, LOGO);
    p1.text(122, 784, 'Employee Details Form', { size: 16, font: 'F2' });
    p1.text(72, 740, 'Personal information', { size: 12, font: 'F2' });
    const nameLeader = '______________________________';
    p1.text(72, 710, `Full name: ${nameLeader}`);
    const nameX = 72 + helveticaWidth('Full name: ', 10);
    const dobLeader = '____________';
    p1.text(72, 685, `Date of birth: ${dobLeader}`);
    const dobX = 72 + helveticaWidth('Date of birth: ', 10);
    p1.text(72, 660, 'Employee ID:');
    p1.rect(150, 655, 150, 18);
    p1.text(320, 660, 'Department:');
    p1.rect(390, 655, 150, 18);
    p1.text(460, 796, 'Photo:');
    p1.rect(460, 690, 90, 100);
    p1.text(72, 590, 'Declaration', { size: 12, font: 'F2' });
    p1.text(72, 570, 'I confirm that the information given in this form is true and complete.');
    p1.line(72, 520, 250, 520);
    p1.text(72, 508, 'Signature', { size: 9 });
    p1.line(320, 520, 450, 520);
    p1.text(320, 508, 'Date', { size: 9 });
    if (filled) {
      p1.text(nameX + 4, 712, FORM_VALUES.fullName);
      p1.text(dobX + 4, 687, FORM_VALUES.dateOfBirth);
      p1.text(154, 660, FORM_VALUES.employeeId);
      p1.text(394, 660, FORM_VALUES.department);
      p1.image(463, 693, 84, 94, PHOTO);
      p1.text(325, 523, FORM_VALUES.signatureDate);
    }

    const p2 = addPage();
    p2.text(72, 780, 'Additional information', { size: 12, font: 'F2' });
    p2.text(72, 750, 'Notes:');
    p2.rect(72, 680, 450, 60);
    p2.text(72, 300, 'Thank you for keeping your details up to date.');
    if (filled) {
      p2.text(330, 420, FORM_VALUES.contact);
    }
  });
}
