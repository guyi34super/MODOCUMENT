#!/usr/bin/env node
// Renders the docs/compliance pack to PDF: one PDF per checklist document, one
// "Control evidence" volume per workbook, and a combined pack.
//
//   NODE_PATH=<dir with marked, mermaid, pdf-lib, playwright> \
//     node scripts/compliance/render_pdf.mjs --out compliance-deliverables/pdf \
//     [--style scripts/compliance/pdf-style.css] [--letterhead template.pdf]
//
// Markdown -> HTML (marked) -> Chromium print (Playwright). Mermaid blocks are
// rendered in the page before printing. With --letterhead, page 1 of that PDF is
// drawn under every page (pdf-lib).
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(path.join(process.env.NODE_PATH?.split(':')[0] || process.cwd(), 'x.js'));
const { marked } = require('marked');
const { chromium } = require('playwright');
const { PDFDocument } = require('pdf-lib');
const yaml = (() => { try { return require('yaml'); } catch { return null; } })();

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PACK = path.join(ROOT, 'docs/compliance');
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) =>
  (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]?.startsWith('--') || all[i + 1] === undefined ? true : all[i + 1]]] : acc), []));
const OUT = path.resolve(args.out || 'compliance-deliverables/pdf');
const STYLE = fs.readFileSync(args.style || path.join(ROOT, 'scripts/compliance/pdf-style.css'), 'utf8');
const MERMAID = fs.readFileSync(require.resolve('mermaid/dist/mermaid.min.js'), 'utf8');
const META = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/compliance/pdf-meta.json'), 'utf8'));
fs.mkdirSync(OUT, { recursive: true });

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const slug = (s) => s.toLowerCase().replace(/<[^>]+>/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

// Parse the simple YAML the pack uses (crosswalk + control data). Falls back to python if no yaml lib.
function readYaml(file) {
  if (yaml) return yaml.parse(fs.readFileSync(file, 'utf8'));
  const { execFileSync } = require('node:child_process');
  return JSON.parse(execFileSync('python3', ['-c',
    'import sys,json,yaml,datetime;print(json.dumps(yaml.safe_load(open(sys.argv[1])),default=str))', file]).toString());
}

function mdToHtml(md, prefix) {
  const headings = [];
  const renderer = new marked.Renderer();
  renderer.heading = function ({ tokens, depth }) {
    const text = this.parser.parseInline(tokens);
    const id = `${prefix}-${slug(text)}-${headings.length}`;
    if (depth <= 3) headings.push({ depth, text, id });
    return `<h${depth} id="${id}">${text}</h${depth}>\n`;
  };
  renderer.code = ({ text, lang }) => lang === 'mermaid'
    ? `<div class="diagram"><pre class="mermaid">${esc(text)}</pre></div>`
    : `<pre class="code"><code>${esc(text)}</code></pre>`;
  // Links between pack files become plain text references in print.
  renderer.link = function ({ href, tokens }) {
    const text = this.parser.parseInline(tokens);
    return /^https?:/.test(href) ? `<a href="${esc(href)}">${text}</a>` : `<span class="ref">${text}</span>`;
  };
  const html = marked.parse(md, { renderer, gfm: true });
  return { html, headings };
}

function coverHtml(doc) {
  return `<section class="cover">
  <div class="cover-org">${esc(META.organisation)}</div>
  <div class="cover-product">${esc(META.product)}</div>
  <h1 class="cover-title">${esc(doc.title)}</h1>
  ${doc.subtitle ? `<div class="cover-subtitle">${esc(doc.subtitle)}</div>` : ''}
  <table class="cover-meta">
    ${doc.id ? `<tr><th>Reference</th><td>${esc(doc.id)}</td></tr>` : ''}
    <tr><th>Version</th><td>${esc(META.version)}</td></tr>
    <tr><th>Date</th><td>${esc(META.date)}</td></tr>
    <tr><th>Prepared by</th><td>${esc(META.preparedBy)}</td></tr>
    <tr><th>Classification</th><td>${esc(META.classification)}</td></tr>
  </table>
  <div class="cover-note">${esc(META.coverNote)}</div>
</section>`;
}

function tocHtml(headings, maxDepth = 2) {
  const items = headings.filter((h) => h.depth <= maxDepth)
    .map((h) => `<li class="toc-l${h.depth}"><a href="#${h.id}">${h.text.replace(/<[^>]+>/g, '')}</a></li>`).join('');
  return `<section class="toc"><h2 class="toc-title">Contents</h2><ul>${items}</ul></section>`;
}

function page(doc, bodyParts) {
  const all = bodyParts.map((p, i) => mdToHtml(p.md, `s${i}`));
  const headings = all.flatMap((a) => a.headings);
  const body = all.map((a, i) => `<section class="part${bodyParts[i].breakBefore ? ' break' : ''}">${a.html}</section>`).join('\n');
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(doc.title)}</title>
<style>${STYLE}</style></head><body>
${coverHtml(doc)}
${doc.toc === false ? '' : tocHtml(headings, doc.tocDepth || 2)}
<main>${body}</main>
<script>${MERMAID}</script>
<script>
  mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict', flowchart: { htmlLabels: false } });
  mermaid.run({ querySelector: '.mermaid' }).then(() => { document.body.dataset.ready = '1'; })
    .catch((e) => { console.error(e); document.body.dataset.ready = 'err'; });
</script></body></html>`;
}

async function render(browser, doc, parts, file) {
  const html = page(doc, parts);
  const p = await browser.newPage();
  await p.setContent(html, { waitUntil: 'load' });
  await p.waitForFunction(() => document.body.dataset.ready, null, { timeout: 60000 });
  if (await p.evaluate(() => document.body.dataset.ready) === 'err') console.warn(`  ! mermaid error in ${file}`);
  const footer = `<div style="font-size:7.5pt;width:100%;padding:0 14mm;color:#555;display:flex;justify-content:space-between;font-family:Helvetica,Arial,sans-serif">
    <span>${esc(META.organisation)} · ${esc(doc.id || doc.title)} · ${esc(META.classification)}</span>
    <span>Page <span class="pageNumber"></span> of <span class="totalPages"></span></span></div>`;
  const header = `<div style="font-size:7.5pt;width:100%;padding:0 14mm;color:#555;font-family:Helvetica,Arial,sans-serif">${esc(doc.title)}</div>`;
  const out = path.join(OUT, file);
  await p.pdf({ path: out, format: 'A4', printBackground: true, displayHeaderFooter: true,
    headerTemplate: header, footerTemplate: footer, margin: { top: '20mm', bottom: '18mm', left: '16mm', right: '16mm' } });
  await p.close();
  if (args.letterhead) await underlay(out, args.letterhead);
  const pages = (await PDFDocument.load(fs.readFileSync(out))).getPageCount();
  console.log(`${file}: ${pages} pages`);
  return { file, pages, title: doc.title };
}

async function underlay(file, letterhead) {
  const pdf = await PDFDocument.load(fs.readFileSync(file));
  const lh = await PDFDocument.load(fs.readFileSync(letterhead));
  const [bg] = await pdf.embedPdf(lh, [0]);
  const merged = await PDFDocument.create();
  for (const pg of pdf.getPages()) {
    const { width, height } = pg.getSize();
    const np = merged.addPage([width, height]);
    np.drawPage(bg, { x: 0, y: 0, width, height });
    const [emb] = await merged.embedPages([pg]);
    np.drawPage(emb, { x: 0, y: 0, width, height });
  }
  fs.writeFileSync(file, await merged.save());
}

const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const firstHeading = (md) => (md.match(/^#\s+(.+)$/m) || [])[1]?.trim();

function volumeParts(cw, key) {
  const controls = cw.controls.filter((c) => c.workbook === key);
  const rows = controls.map((c) => {
    let d = {};
    if (fs.existsSync(path.join(ROOT, c.data))) d = readYaml(path.join(ROOT, c.data)) || {};
    return `| ${c.id} | ${c.name.replace(/\|/g, '/')} | ${d.status || '—'} | ${d.evidence?.status || '—'} | ${d.test?.result || '—'} |`;
  });
  const summary = `# Summary of controls\n\n| Control | Name | Status (repo-verified) | Evidence | Test |\n|---|---|---|---|---|\n${rows.join('\n')}\n`;
  const pages = controls.filter((c) => fs.existsSync(path.join(ROOT, c.page)) || (console.warn(`  ! missing ${c.page}`), false));
  return [{ md: summary }, ...pages.map((c) => ({ md: read(c.page), breakBefore: true }))];
}

const cw = readYaml(path.join(PACK, 'crosswalk.yaml'));
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const made = [];
const combined = [];
const only = args.only ? String(args.only).split(',') : null;

// Index first, then checklist documents in task order.
const indexMd = fs.existsSync(path.join(PACK, '00-index.md')) ? read('docs/compliance/00-index.md') : null;
if (indexMd && (!only || only.includes('index'))) {
  made.push(await render(browser, { title: 'Documentation pack — index', id: 'Pack index' }, [{ md: indexMd }], '00-Documentation-Pack-Index.pdf'));
}
if (indexMd) combined.push({ md: indexMd });
for (const t of cw.tasks) {
  if (!fs.existsSync(path.join(ROOT, t.doc))) { console.warn(`  ! missing ${t.doc}`); continue; }
  const md = read(t.doc);
  const extra = (META.attachments?.[t.id] || []).filter((rel) => fs.existsSync(path.join(ROOT, rel)))
    .map((rel) => ({ md: read(rel), breakBefore: true }));
  const doc = { id: t.id, title: (firstHeading(md) || t.task).replace(/^MO-\d+\s*[—–-]\s*/, ''), subtitle: t.deliverable };
  if (!only || only.includes(t.id)) made.push(await render(browser, doc, [{ md }, ...extra], `${t.id}-${slug(doc.title)}.pdf`));
  combined.push({ md, breakBefore: true }, ...extra);
}
for (const [key, name] of Object.entries(cw.workbooks)) {
  const parts = volumeParts(cw, key);
  const doc = { id: `Workbook ${key.slice(0, 2)}`, title: `Control evidence — ${name.replace(/^\d+\s*/, '')}`, tocDepth: 1 };
  if (!only || only.includes(key)) made.push(await render(browser, doc, parts, `Controls-${key}.pdf`));
  combined.push(...parts.map((p, i) => ({ ...p, breakBefore: true })));
}
const policies = (META.policyOrder || []).filter((rel) => fs.existsSync(path.join(ROOT, rel)));
if (policies.length) {
  const parts = policies.map((rel, i) => ({ md: read(rel), breakBefore: i > 0 }));
  if (!only || only.includes('policies')) made.push(await render(browser, { id: 'Policies', title: 'Policies, procedures and registers', tocDepth: 1 }, parts, 'Policies-and-procedures.pdf'));
  combined.push(...parts.map((p) => ({ ...p, breakBefore: true })));
}
if (!only || only.includes('combined')) {
  made.push(await render(browser, { id: 'Complete pack', title: 'MoDocument technology-risk documentation pack', subtitle: 'All documents, control evidence and policies', tocDepth: 1 }, combined, 'MoDocument-Technology-Risk-Documentation-Pack.pdf'));
}
await browser.close();
fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(made, null, 2));
