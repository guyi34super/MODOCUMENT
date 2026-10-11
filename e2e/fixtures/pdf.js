// Minimal single-page PDF writer (Helvetica, one text line per entry) — just
// enough for PDFBox to extract the text. Free-text PDFs are what the
// deterministic materializer cannot handle, so they exercise the AI path
// (and, from Wave 2, the code fallback) of the ingestion parse step.
import fs from 'node:fs';

const escapePdf = (text) => String(text).replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');

/** @param {string[]} lines @returns {Buffer} */
export function textPdf(lines) {
  const ops = ['BT', '/F1 11 Tf', '14 TL', '72 740 Td'];
  for (const line of lines) {
    ops.push(line === '' ? 'T*' : `(${escapePdf(line)}) Tj T*`);
  }
  ops.push('ET');
  const stream = ops.join('\n');

  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R ' +
      '/Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  ];

  let out = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((body, i) => {
    offsets.push(Buffer.byteLength(out, 'latin1'));
    out += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefAt = Buffer.byteLength(out, 'latin1');
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefAt}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

export function writeTextPdf(filePath, lines) {
  fs.writeFileSync(filePath, textPdf(lines));
  return filePath;
}
