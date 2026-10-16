// Document-creation helpers (exact PDF templates, docs/doccreation/
// pdf-templates-contract.md §5). Everything goes through the public API on
// the SPA origin, as the SPA calls it; binary answers (PNG pages, PDFs) are
// read as bytes.
import { expect } from '@playwright/test';
import { decodePng, diffPixels } from '../fixtures/png.js';

const BASE = '/api/doccreation';

/** GET with the client's token, as bytes: {status, contentType, bytes}. */
export async function getBytes(api, url) {
  const res = await fetch(api.baseURL + url, { headers: { Authorization: `Bearer ${api.token}` } });
  return {
    status: res.status,
    contentType: res.headers.get('content-type') || '',
    bytes: Buffer.from(await res.arrayBuffer()),
  };
}

/** POST /blueprints (multipart): the raw response, so specs can assert refusals too. */
export async function uploadBlueprint(api, { bytes, fileName, flow = 'AUTO', name }) {
  const form = new FormData();
  form.append('file', new Blob([bytes]), fileName);
  form.append('flow', flow);
  if (name) form.append('name', name);
  return api.request('POST', `${BASE}/blueprints`, { form });
}

export async function getBlueprint(api, id) {
  return api.get(`${BASE}/blueprints/${id}`);
}

/**
 * Confirms a draft as proposed: every region and edit sent back as received.
 * Suggested removals (`applied: false`, BUG-PDF-01) and their regions
 * (`suggested: true`) are then ignored by the server, i.e. NOT applied —
 * unless `applySuggestions`, which accepts every proposal, guesses included.
 */
export async function confirmAsProposed(api, blueprint, { applySuggestions = false } = {}) {
  const strip = ({ sampleValue, removedText, ...rest }) => rest;
  return api.put(`${BASE}/blueprints/${blueprint.id}/overlay`, {
    baseVersion: blueprint.currentVersion ?? blueprint.version,
    regions: blueprint.regions.map(strip).map((r) => (applySuggestions ? { ...r, suggested: false } : r)),
    edits: blueprint.edits.map(strip).map((e) => (applySuggestions ? { ...e, applied: true } : e)),
    confirm: true,
  });
}

/** One template page of a version as a decoded PNG (backend rendering, default dpi). */
export async function templatePage(api, blueprintId, page, { variant = 'template', version } = {}) {
  const query = `variant=${variant}${version ? `&version=${version}` : ''}`;
  const res = await getBytes(api, `${BASE}/blueprints/${blueprintId}/pages/${page}?${query}`);
  expect(res.status, `page ${page} of template ${blueprintId}`).toBe(200);
  expect(res.contentType).toContain('image/png');
  return decodePng(res.bytes);
}

/**
 * Asserts two templates render identically on every page: the same page
 * count and 0 differing pixels per page (the contract's "exact").
 */
export async function expectSameRendering(api, blueprintA, blueprintB, pageCount) {
  for (let p = 0; p < pageCount; p += 1) {
    const a = await templatePage(api, blueprintA, p);
    const b = await templatePage(api, blueprintB, p);
    const diff = diffPixels(a, b);
    expect(diff.differing, `page ${p + 1}: ${diff.differing} differing pixels, first at ${JSON.stringify(diff.firstDiff)}`).toBe(0);
  }
}

/** The template PDF (or the original upload) of a blueprint as bytes. */
export async function blueprintFile(api, id, variant = 'template') {
  const res = await getBytes(api, `${BASE}/blueprints/${id}/file?variant=${variant}`);
  expect(res.status).toBe(200);
  expect(res.contentType).toContain('application/pdf');
  return res.bytes;
}

/** A minimal .docx (a ZIP: "PK" magic + an OOXML part name) — enough for the magic-byte check. */
export function fakeDocx() {
  return Buffer.concat([
    Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00]),
    Buffer.from('[Content_Types].xml word/document.xml', 'latin1'),
    Buffer.alloc(64),
  ]);
}

/**
 * The builder's Save (field-validation-contract §4/§5): flushes the autosave,
 * then POST /instances/{id}/validate. Waits for the validate answer and, when
 * `expectValidated` (the default), asserts it passed: no errors, the status
 * badge shows VALIDATED and Generate is enabled. Returns the InstanceDto.
 */
export async function saveAndValidate(page, { expectValidated = true } = {}) {
  const validated = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/api\/doccreation\/instances\/\d+\/validate$/.test(new URL(r.url()).pathname)
  );
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const response = await validated;
  expect(response.status(), 'POST /validate').toBe(200);
  const dto = await response.json();
  if (expectValidated) {
    expect(dto.validation?.errors ?? [], 'validation errors after Save').toEqual([]);
    expect(dto.status).toBe('VALIDATED');
    await expect(page.getByText('VALIDATED', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generate', exact: true })).toBeEnabled();
  }
  return dto;
}
