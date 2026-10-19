// MO-016 scenarios (S1–S6, soak) as functions over a seeded stack. Each one
// returns {windows: [{label, vus, start, end}], extra} — run.mjs turns the
// windows into per-stage statistics from the recorder and the monitor.
import { pick, runStages, sleep, think } from './lib/engine.mjs';
import {
  CSV_FIELDS,
  employeesCsv,
  formValues,
  keyValuePdf,
  largeForm,
  multipart,
  wideCsv,
} from './lib/fixtures.mjs';
import { confirmedBlueprint, createDocument, createFlowTemplate, ingest, rowsOf } from './lib/setup.mjs';

const DOCS = '/api/doccreation';

// --------------------------------------------------------------- helpers

/** Three documents per VU: the seeded ones for VU < users, created on first use above that. */
const vuDocs = new Map();
async function docsFor(env, ctx) {
  if (vuDocs.has(ctx.i)) return vuDocs.get(ctx.i);
  let docs;
  if (ctx.i < env.users.length) docs = ctx.user.docIds;
  else {
    docs = [];
    for (let d = 0; d < 3; d += 1) {
      const id = await createDocument(env.client, ctx.user, ctx.user.blueprintId, `Stress VU ${ctx.i} doc ${d}`, 'vu');
      docs.push(id);
    }
  }
  vuDocs.set(ctx.i, docs);
  return docs;
}

async function autosave(env, ctx, docId) {
  const n = ctx.i * 100000 + ctx.iter;
  const values = ctx.iter % 3 === 0 ? { notes: `Synthetic note ${n}` } : { name: `Stress Person ${n}`, reference: `REF-${n}` };
  return env.client.call(ctx.user, 'PUT', `${DOCS}/instances/${docId}/values`, {
    json: { values },
    name: 'doc.values.autosave',
    cls: 'write',
  });
}

/** Save & validate → generate → download, as the builder's buttons do. */
async function generateFlow(env, ctx, docId, prefix = 'doc') {
  const { client } = env;
  const v = await client.call(ctx.user, 'POST', `${DOCS}/instances/${docId}/validate`, { name: `${prefix}.validate`, cls: 'write' });
  if (!v.ok) return false;
  // An unchanged, already generated document stays GENERATED on validate (still generatable).
  if (v.body?.status !== 'VALIDATED' && v.body?.status !== 'GENERATED') {
    env.log(`[stress] validate gave ${v.body?.status} for doc ${docId}`);
    return false;
  }
  const g = await client.call(ctx.user, 'POST', `${DOCS}/instances/${docId}/generate`, { name: `${prefix}.generate`, cls: 'heavy', timeoutMs: 120_000 });
  if (!g.ok) return false;
  const d = await client.call(ctx.user, 'GET', `${DOCS}/instances/${docId}/download`, { name: `${prefix}.download`, cls: 'heavy', raw: true, timeoutMs: 120_000 });
  return d.ok;
}

/** Upload a fresh small CSV (wait=false) and poll the job until VALIDATED; records ingest.job.complete. */
async function uploadAndPoll(env, ctx, rows = 200) {
  const { client, rec } = env;
  const u = ctx.user;
  u.uploads = (u.uploads || 0) + 1;
  const tag = `${u.n}-${ctx.i}-${u.uploads}-${Date.now().toString(36)}`;
  const at = Date.now();
  const t0 = performance.now();
  const created = await ingest(client, u, {
    templateId: u.templateId,
    bytes: employeesCsv(rows, tag),
    fileName: `employees-${tag}.csv`,
    wait: false,
    name: 'ingest.upload',
    timeoutMs: 60_000,
  });
  if (!created.ok) return;
  const id = created.body.ingestionJobId;
  let job = created.body;
  const deadline = Date.now() + 120_000;
  while (job?.currentStep !== 'VALIDATED' && job?.status !== 'FAILED' && Date.now() < deadline) {
    await sleep(2000);
    const r = await client.call(u, 'GET', `/api/ingestion/jobs/${id}`, { name: 'ingest.job.poll', cls: 'read' });
    if (r.ok) job = r.body;
  }
  const ok = job?.currentStep === 'VALIDATED';
  rec.push({ at, name: 'ingest.job.complete', cls: 'job', ms: performance.now() - t0, status: ok ? 200 : 0, bytes: 0, ok, err: ok ? null : `job-${job?.status}` });
}

/** The UI mix of a signed-in user (S4 peak and the soak). */
async function peakAction(env, ctx) {
  const { client } = env;
  const u = ctx.user;
  const docs = await docsFor(env, ctx);
  const action = pick([
    [15, 'dashboard'],
    [12, 'jobs.list'],
    [10, 'docs.list'],
    [5, 'blueprints.list'],
    [13, 'job.poll'],
    [8, 'me'],
    [22, 'autosave'],
    [5, 'upload'],
    [5, 'generate'],
    [3, 'export'],
    [1, 'login'],
  ]);
  switch (action) {
    case 'dashboard':
      await client.call(u, 'GET', '/api/dashboard/summary', { name: 'ui.dashboard', cls: 'read' });
      break;
    case 'jobs.list':
      await client.call(u, 'GET', '/api/ingestion/jobs?view=summary&page=0&size=25', { name: 'ui.jobs.list', cls: 'read' });
      break;
    case 'docs.list':
      await client.call(u, 'GET', `${DOCS}/instances`, { name: 'ui.docs.list', cls: 'read' });
      break;
    case 'blueprints.list':
      await client.call(u, 'GET', `${DOCS}/blueprints`, { name: 'ui.blueprints.list', cls: 'read' });
      break;
    case 'job.poll':
      await client.call(u, 'GET', `/api/ingestion/jobs/${u.jobId}`, { name: 'ingest.job.get', cls: 'read' });
      break;
    case 'me':
      await client.call(u, 'GET', '/api/auth/me', { name: 'ui.me', cls: 'read' });
      await client.call(u, 'GET', '/api/announcements/active', { name: 'ui.announcements', cls: 'read' });
      break;
    case 'autosave':
      await autosave(env, ctx, docs[ctx.iter % 2]);
      break;
    case 'upload':
      await uploadAndPoll(env, ctx);
      break;
    case 'generate':
      await generateFlow(env, ctx, docs[2]);
      break;
    case 'export':
      await client.call(u, 'GET', `/api/ingestion/jobs/${u.exportJobId}/export/file?format=csv`, { name: 'ingest.export.csv', cls: 'heavy', raw: true });
      break;
    case 'login':
      await client.login(u, { name: 'auth.login' });
      break;
    default:
  }
  await think(1000, 3000);
}

// ------------------------------------------------------------- scenarios

/** S1 — concurrent data entry: autosave of document values + ingestion row edits. */
export async function s1DataEntry(env) {
  const { client, users } = env;
  const rowsCache = new Map();
  const stages = env.quick
    ? [{ vus: 5, seconds: 10 }]
    : [
        { vus: 10, seconds: 45, label: '10 users' },
        { vus: 25, seconds: 45, label: '25 users' },
        { vus: 50, seconds: 60, label: '50 users' },
      ];
  const { marks } = await runStages({
    stages,
    users,
    log: env.log,
    iterate: async (ctx) => {
      const u = ctx.user;
      const docs = await docsFor(env, ctx);
      await autosave(env, ctx, docs[0]);
      if (ctx.iter % 6 === 5) {
        await client.call(u, 'GET', `${DOCS}/instances/${docs[0]}`, { name: 'doc.instance.get', cls: 'read' });
      }
      if (ctx.iter % 10 === (ctx.i % 10)) {
        if (!rowsCache.has(u.n)) {
          const j = await client.call(u, 'GET', `/api/ingestion/jobs/${u.jobId}`, { name: 'ingest.job.get', cls: 'read' });
          rowsCache.set(u.n, j.ok ? rowsOf(j.body) : null);
        }
        const rows = rowsCache.get(u.n);
        if (rows?.length) {
          const edited = rows.map((r, idx) => (idx === ctx.iter % rows.length ? { ...r, FullName: `Edited ${ctx.iter}` } : r));
          const e = await client.call(u, 'PUT', `/api/ingestion/jobs/${u.jobId}/rows`, { json: { rows: edited }, name: 'ingest.rows.edit', cls: 'write' });
          // An edit sends the job back to MAPPED; the user then re-validates (as the job page does).
          if (e.ok) await client.call(u, 'POST', `/api/ingestion/jobs/${u.jobId}/revalidate`, { name: 'ingest.revalidate', cls: 'write' });
        }
      }
      await think(600, 1200);
    },
  });
  return { windows: marks };
}

/** S2 — concurrent report generation: validate → generate → download, plus ingestion CSV/XLSX export. */
export async function s2Generation(env) {
  const { client, users } = env;
  const stages = env.quick
    ? [{ vus: 3, seconds: 10 }]
    : [
        { vus: 5, seconds: 45, label: '5 users' },
        { vus: 10, seconds: 45, label: '10 users' },
        { vus: 20, seconds: 60, label: '20 users' },
        { vus: 40, seconds: 45, label: '40 users' },
      ];
  const { marks } = await runStages({
    stages,
    users,
    log: env.log,
    iterate: async (ctx) => {
      const u = ctx.user;
      const docs = await docsFor(env, ctx);
      const doc = docs[2] ?? docs[0];
      const t0 = performance.now();
      const at = Date.now();
      await client.call(u, 'PUT', `${DOCS}/instances/${doc}/values`, {
        json: { values: { notes: `Generation run ${ctx.iter}` } },
        name: 'doc.values.autosave',
        cls: 'write',
      });
      const ok = await generateFlow(env, ctx, doc);
      env.rec.push({ at, name: 'flow.save-generate-download', cls: 'flow', ms: performance.now() - t0, status: ok ? 200 : 0, bytes: 0, ok, err: ok ? null : 'flow-failed' });
      if (ctx.iter % 3 === 0) {
        const fmt = ctx.iter % 9 === 0 ? 'xlsx' : 'csv';
        await client.call(u, 'GET', `/api/ingestion/jobs/${u.exportJobId}/export/file?format=${fmt}`, { name: `ingest.export.${fmt}`, cls: 'heavy', raw: true });
      }
      await think(1000, 2000);
    },
  });
  return { windows: marks };
}

/** S2-break — generation only, no think time, stepped until it breaks (one-CPU run). */
export async function s2GenerationBreak(env) {
  const { client, users } = env;
  const steps = env.quick ? [2, 4] : [5, 10, 20, 40, 60];
  const { marks } = await runStages({
    stages: steps.map((vus) => ({ vus, seconds: env.quick ? 8 : 30, label: `${vus} users, no think time` })),
    users,
    log: env.log,
    iterate: async (ctx) => {
      const docs = await docsFor(env, ctx);
      const doc = docs[2] ?? docs[0];
      const t0 = performance.now();
      const at = Date.now();
      await client.call(ctx.user, 'PUT', `${DOCS}/instances/${doc}/values`, {
        json: { values: { notes: `Generation break ${ctx.iter}` } },
        name: 'doc.values.autosave',
        cls: 'write',
      });
      const ok = await generateFlow(env, ctx, doc);
      env.rec.push({ at, name: 'flow.save-generate-download', cls: 'flow', ms: performance.now() - t0, status: ok ? 200 : 0, bytes: 0, ok, err: ok ? null : 'flow-failed' });
    },
  });
  return { windows: marks };
}

/** S3 — large templates and files (single user timings, then a concurrent pass). */
export async function s3Large(env) {
  const { client, users, rec, log } = env;
  const out = { pdf: [], flow: [], csv: [] };
  const windows = [];
  const timed = async (label, fn) => {
    const start = Date.now();
    const result = await fn();
    windows.push({ label, vus: 1, start, end: Date.now() });
    return result;
  };
  const pages = env.quick ? 3 : 20;
  const perPage = env.quick ? 4 : 15;

  // (a) Large PDF form template: pages x perPage fields.
  const big = largeForm(pages, perPage);
  log(`[stress] S3a large PDF template ${pages} pages x ${perPage} = ${big.fields.length} fields, ${big.bytes.length} bytes`);
  const bigDocs = [];
  const reps = env.quick ? 1 : 3;
  for (let r = 0; r < reps; r += 1) {
    const u = users[r];
    const res = await timed(`S3a large PDF rep ${r + 1}`, async () => {
      const t = {};
      let t0 = performance.now();
      const bp = await confirmedBlueprint(client, u, {
        bytes: big.bytes,
        fileName: `large-form-${r}.pdf`,
        name: `Large form ${env.runId} ${r}`,
        fields: big.fields,
        tag: 'large',
      });
      t.uploadAndConfirmMs = Math.round(performance.now() - t0);
      const doc = await createDocument(client, u, bp, `Large doc ${r}`, 'large');
      const values = Object.fromEntries(big.fields.map((f, i) => [f.key, `Value ${i + 1}`]));
      const v = await client.call(u, 'PUT', `${DOCS}/instances/${doc}/values`, { json: { values }, name: 'large.values.all', cls: 'write' });
      t.valuesMs = Math.round(v.ms);
      t0 = performance.now();
      const ok = await generateFlow(env, { user: u }, doc, 'large');
      t.validateGenerateDownloadMs = Math.round(performance.now() - t0);
      const pg0 = await client.call(u, 'GET', `${DOCS}/instances/${doc}/pages/0`, { name: 'large.page.preview', cls: 'heavy', raw: true });
      const pgN = await client.call(u, 'GET', `${DOCS}/instances/${doc}/pages/${pages - 1}`, { name: 'large.page.preview', cls: 'heavy', raw: true });
      t.previewMs = [Math.round(pg0.ms), Math.round(pgN.ms)];
      bigDocs.push({ user: u, doc });
      return { ok, blueprintId: bp, docId: doc, fields: big.fields.length, pdfBytes: big.bytes.length, ...t };
    });
    out.pdf.push(res);
    log(`[stress]   ${JSON.stringify(res)}`);
  }
  // Concurrent generation on the large template.
  if (!env.quick) {
    const vus = 5;
    for (let i = bigDocs.length; i < vus; i += 1) {
      const u = users[i];
      const bp = out.pdf[0].blueprintId;
      // Templates are owner-scoped: each extra user gets their own copy.
      const own = await confirmedBlueprint(client, u, { bytes: big.bytes, fileName: `large-form-c${i}.pdf`, name: `Large form ${env.runId} c${i}`, fields: big.fields, tag: 'large' });
      const doc = await createDocument(client, u, own ?? bp, `Large doc c${i}`, 'large');
      const values = Object.fromEntries(big.fields.map((f, k) => [f.key, `Value ${k + 1}`]));
      await client.call(u, 'PUT', `${DOCS}/instances/${doc}/values`, { json: { values }, name: 'large.values.all', cls: 'write' });
      bigDocs.push({ user: u, doc });
    }
    const { marks } = await runStages({
      stages: [{ vus, seconds: 60, label: 'S3a 5 users generating the 300-field template' }],
      users: bigDocs.map((b) => b.user),
      log,
      iterate: async (ctx) => {
        const { doc } = bigDocs[ctx.i];
        await client.call(ctx.user, 'PUT', `${DOCS}/instances/${doc}/values`, { json: { values: { p1_f1: `Run ${ctx.iter}` } }, name: 'large.values.one', cls: 'write' });
        await generateFlow(env, ctx, doc, 'large');
        await think(500, 1000);
      },
    });
    windows.push(...marks);
  }

  // (b) Wide flow template: 120 fields, 1,000 rows.
  {
    const cols = env.quick ? 20 : 120;
    const rowsN = env.quick ? 100 : 1000;
    const u = users[5 % users.length];
    const wide = wideCsv(cols, rowsN, 'w');
    const res = await timed(`S3b wide template ${cols} fields x ${rowsN} rows`, async () => {
      const tpl = await createFlowTemplate(client, u, `Wide ${env.runId}`, wide.names);
      const r = await ingest(client, u, { templateId: tpl.templateId, bytes: wide.bytes, fileName: `wide-${cols}x${rowsN}.csv`, name: 'large.ingest.wide' });
      const job = r.body;
      const g = await client.call(u, 'GET', `/api/ingestion/jobs/${job?.ingestionJobId}`, { name: 'large.job.get.wide', cls: 'read' });
      const e = await client.call(u, 'GET', `/api/ingestion/jobs/${job?.ingestionJobId}/export/file?format=csv`, { name: 'large.export.wide', cls: 'heavy', raw: true });
      return {
        fields: cols,
        rows: rowsN,
        csvBytes: wide.bytes.length,
        status: r.status,
        step: job?.currentStep,
        jobStatus: job?.status,
        uploadToValidatedMs: Math.round(r.ms),
        jobGetMs: Math.round(g.ms),
        jobGetBytes: g.ok ? JSON.stringify(g.body).length : null,
        exportMs: Math.round(e.ms),
        exportStatus: e.status,
      };
    });
    out.flow.push(res);
    log(`[stress]   ${JSON.stringify(res)}`);
  }

  // (c) Large CSV ingestion: 1k, 10k, 50k rows (synthetic employees).
  const sizes = env.quick ? [1000] : [1000, 10000, 50000];
  for (const rowsN of sizes) {
    const u = users[6 % users.length];
    const bytes = employeesCsv(rowsN, `big-${rowsN}`);
    const res = await timed(`S3c CSV ${rowsN} rows`, async () => {
      const r = await ingest(client, u, { templateId: u.templateId, bytes, fileName: `employees-${rowsN}.csv`, name: `large.ingest.${rowsN}`, timeoutMs: 900_000 });
      const job = r.body;
      const g = await client.call(u, 'GET', `/api/ingestion/jobs/${job?.ingestionJobId}`, { name: `large.job.get.${rowsN}`, cls: 'read', timeoutMs: 300_000 });
      const sum = await client.call(u, 'GET', '/api/ingestion/jobs?view=summary&page=0&size=25', { name: 'ui.jobs.list', cls: 'read' });
      let e = await client.call(u, 'GET', `/api/ingestion/jobs/${job?.ingestionJobId}/export/file?format=csv`, {
        name: `large.export.${rowsN}`,
        cls: 'heavy',
        raw: true,
        expect: [200, 403],
        timeoutMs: 300_000,
      });
      let capRefused = false;
      if (e.status === 403) {
        capRefused = true;
        e = await client.call(u, 'POST', `/api/ingestion/jobs/${job?.ingestionJobId}/export/file`, {
          json: { format: 'csv', reason: 'Stress test MO-017: synthetic data, cap check' },
          name: `large.export.${rowsN}.with-reason`,
          cls: 'heavy',
          raw: true,
          timeoutMs: 300_000,
        });
      }
      return {
        rows: rowsN,
        csvBytes: bytes.length,
        status: r.status,
        step: job?.currentStep,
        jobStatus: job?.status,
        uploadToValidatedMs: Math.round(r.ms),
        jobGetMs: Math.round(g.ms),
        jobGetBytes: g.ok ? JSON.stringify(g.body).length : null,
        jobsListMs: Math.round(sum.ms),
        exportCapRefusedFirst: capRefused,
        exportMs: Math.round(e.ms),
        exportStatus: e.status,
        exportBytes: e.body?.length ?? null,
      };
    });
    out.csv.push(res);
    log(`[stress]   ${JSON.stringify(res)}`);
  }
  return { windows, extra: out };
}

/** S4 — peak mixed load: warm-up, 1x peak, 2x peak, spike 4x, recovery. */
export async function s4Peak(env, { stages } = {}) {
  const st =
    stages ??
    (env.quick
      ? [{ vus: 10, seconds: 15, label: 'quick' }]
      : [
          { vus: 25, seconds: 60, label: 'warm-up 25' },
          { vus: 50, seconds: 180, label: '1x peak 50' },
          { vus: 100, seconds: 180, label: '2x peak 100' },
          { vus: 200, seconds: 60, label: 'spike 200' },
          { vus: 25, seconds: 90, label: 'recovery 25' },
        ]);
  const { marks } = await runStages({ stages: st, users: env.users, log: env.log, iterate: (ctx) => peakAction(env, ctx) });
  return { windows: marks };
}

/** Soak (short): 1x peak for SOAK_S seconds, reported per minute. */
export async function soak(env) {
  const seconds = env.quick ? 30 : Number(process.env.STRESS_SOAK_S || 600);
  const vus = env.quick ? 10 : 50;
  const { marks } = await runStages({
    stages: [{ vus, seconds, label: `soak ${vus} VUs` }],
    users: env.users,
    log: env.log,
    iterate: (ctx) => peakAction(env, ctx),
  });
  // Per-minute windows for drift.
  const [m] = marks;
  const windows = [];
  for (let t = m.start; t < m.end; t += 60_000) {
    windows.push({ label: `minute ${windows.length + 1}`, vus, start: t, end: Math.min(m.end, t + 60_000) });
  }
  return { windows: [m, ...windows] };
}

/** S5b — AI degradation: slow model, failing model (circuit opens), code fallback. */
export async function s5Circuit(env) {
  const { client, users, llm, log } = env;
  const n = env.quick ? 4 : 10;
  const subjects = users.slice(10, 10 + n);
  for (const u of subjects) {
    if (!u.kvTemplateId) {
      const t = await client.call(u, 'POST', '/Templates/CreateWithFields', {
        json: {
          name: `Stress Remittance ${env.runId} ${u.n}`,
          description: 'stress (synthetic)',
          fields: [
            { fieldName: 'Reference', dataType: 'Text', rules: [], piiKind: null },
            { fieldName: 'Amount', dataType: 'Number', rules: [], piiKind: null },
            { fieldName: 'Currency', dataType: 'Text', rules: [], piiKind: null },
          ],
        },
        expect: [200, 201],
        name: 'setup.template.create',
        cls: 'write',
      });
      u.kvTemplateId = t.body.templateId;
    }
  }
  const jobs = [];
  const uploadKv = async (u, phase) => {
    const tag = `${u.n}-${phase}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
    const at = Date.now();
    const t0 = performance.now();
    const created = await ingest(client, u, { templateId: u.kvTemplateId, bytes: keyValuePdf(tag), fileName: `remittance-${tag}.pdf`, wait: false, name: 'ai.ingest.upload', timeoutMs: 60_000 });
    if (!created.ok) {
      jobs.push({ phase, ok: false, status: created.status });
      return;
    }
    let job = created.body;
    const deadline = Date.now() + 180_000;
    while (job?.currentStep !== 'VALIDATED' && job?.status !== 'FAILED' && Date.now() < deadline) {
      await sleep(1000);
      const r = await client.call(u, 'GET', `/api/ingestion/jobs/${job.ingestionJobId}`, { name: 'ai.job.poll', cls: 'read' });
      if (r.ok) job = r.body;
    }
    const ms = performance.now() - t0;
    const ok = job?.currentStep === 'VALIDATED';
    env.rec.push({ at, name: `ai.job.complete.${phase}`, cls: 'job', ms, status: ok ? 200 : 0, bytes: 0, ok, err: ok ? null : `job-${job?.status}` });
    jobs.push({ phase, ok, ms: Math.round(ms), step: job?.currentStep, status: job?.status, parsedBy: job?.parsedBy, reason: job?.parseFallbackReason, finishedAt: Date.now() });
  };
  const windows = [];
  const phase = async (label, fn) => {
    const start = Date.now();
    await fn();
    windows.push({ label, vus: subjects.length, start, end: Date.now() });
  };

  await llm.reset();
  await phase('model slow (2 s per call), 10 concurrent uploads', async () => {
    await llm.setMode('ok', { delayMs: 2000 });
    await Promise.all(subjects.map((u) => uploadKv(u, 'slow')));
  });
  const statsSlow = await llm.stats();
  await llm.reset();
  await phase('model failing (HTTP 500), 10 users x 2 uploads', async () => {
    await llm.setMode('error500');
    await Promise.all(subjects.map(async (u) => {
      await uploadKv(u, 'failing');
      await uploadKv(u, 'failing');
    }));
  });
  const statsFailing = await llm.stats();
  await llm.reset(); // model healthy again, breaker still open for its 120 s
  await phase('model healthy again, breaker open', async () => {
    await Promise.all(subjects.slice(0, 5).map((u) => uploadKv(u, 'after')));
  });
  const statsAfter = await llm.stats();
  const tally = (p) => {
    const list = jobs.filter((j) => j.phase === p);
    const by = {};
    for (const j of list) {
      const k = `${j.step}/${j.parsedBy}/${j.reason ?? '-'}`;
      by[k] = (by[k] || 0) + 1;
    }
    return { jobs: list.length, validated: list.filter((j) => j.ok).length, outcomes: by };
  };
  const extra = {
    slow: { ...tally('slow'), llmCalls: statsSlow.calls },
    failing: { ...tally('failing'), llmCalls: statsFailing.calls, llmByMode: statsFailing.byMode },
    after: { ...tally('after'), llmCalls: statsAfter.calls },
    jobs,
  };
  log(`[stress]   circuit: ${JSON.stringify({ slow: extra.slow, failing: extra.failing, after: extra.after })}`);
  return { windows, extra };
}

/** After the breaker's open duration: a healthy model is used again (half-open trial closes it). */
export async function s5CircuitRecovery(env) {
  const { client, users, llm } = env;
  await llm.reset();
  const u = users[10];
  if (!u.kvTemplateId) return { windows: [], extra: { skipped: 'no S5b template' } };
  const start = Date.now();
  const results = [];
  for (let k = 0; k < 3; k += 1) {
    const tag = `${u.n}-recover-${k}-${Date.now().toString(36)}`;
    const created = await ingest(client, u, { templateId: u.kvTemplateId, bytes: keyValuePdf(tag), fileName: `remittance-${tag}.pdf`, wait: true, name: 'ai.ingest.recovery', timeoutMs: 120_000 });
    results.push({ status: created.status, step: created.body?.currentStep, parsedBy: created.body?.parsedBy, reason: created.body?.parseFallbackReason ?? null, ms: Math.round(created.ms) });
  }
  const stats = await llm.stats();
  return { windows: [{ label: 'recovery uploads', vus: 1, start, end: Date.now() }], extra: { results, llmCalls: stats.calls } };
}

/** S5c — DB pool pressure: 200 VUs, no think time, DB-bound requests, 60 s. */
export async function s5DbPool(env) {
  const { client } = env;
  const vus = env.quick ? 20 : 200;
  const { marks } = await runStages({
    stages: [{ vus, seconds: env.quick ? 10 : 60, label: `${vus} VUs, no think time` }],
    users: env.users,
    log: env.log,
    iterate: async (ctx) => {
      const docs = await docsFor(env, ctx);
      const a = pick([[50, 'autosave'], [25, 'jobs'], [25, 'dash']]);
      if (a === 'autosave') await autosave(env, ctx, docs[ctx.iter % 2]);
      else if (a === 'jobs') await client.call(ctx.user, 'GET', '/api/ingestion/jobs?view=summary&page=0&size=25', { name: 'ui.jobs.list', cls: 'read' });
      else await client.call(ctx.user, 'GET', '/api/dashboard/summary', { name: 'ui.dashboard', cls: 'read' });
    },
  });
  return { windows: marks };
}

/** S6 — breakpoint: step VUs up until errors or latency break, then recover. */
export async function s6Breakpoint(env, { steps } = {}) {
  const { client } = env;
  const stepList = steps ?? (env.quick ? [5, 10] : [10, 25, 50, 100, 150, 200, 300, 400]);
  const seconds = env.quick ? 8 : Number(process.env.STRESS_STEP_S || 40);
  const stages = stepList.map((vus) => ({ vus, seconds, label: `${vus} VUs` }));
  const iterate = async (ctx) => {
    const u = ctx.user;
    const docs = await docsFor(env, ctx);
    const a = pick([
      [20, 'dash'],
      [15, 'jobs'],
      [15, 'docs'],
      [10, 'me'],
      [10, 'job'],
      [25, 'autosave'],
      [5, 'validate'],
    ]);
    if (a === 'dash') await client.call(u, 'GET', '/api/dashboard/summary', { name: 'ui.dashboard', cls: 'read' });
    else if (a === 'jobs') await client.call(u, 'GET', '/api/ingestion/jobs?view=summary&page=0&size=25', { name: 'ui.jobs.list', cls: 'read' });
    else if (a === 'docs') await client.call(u, 'GET', `${DOCS}/instances`, { name: 'ui.docs.list', cls: 'read' });
    else if (a === 'me') await client.call(u, 'GET', '/api/auth/me', { name: 'ui.me', cls: 'read' });
    else if (a === 'job') await client.call(u, 'GET', `/api/ingestion/jobs/${u.jobId}`, { name: 'ingest.job.get', cls: 'read' });
    else if (a === 'autosave') await autosave(env, ctx, docs[ctx.iter % 2]);
    else await client.call(u, 'POST', `${DOCS}/instances/${docs[2]}/validate`, { name: 'doc.validate', cls: 'write' });
    await think(200, 500);
  };
  const onStageEnd = (mark) => {
    const samples = env.rec.between(mark.start, mark.end);
    const errors = samples.filter((s) => !s.ok).length;
    const rate = samples.length ? errors / samples.length : 0;
    const sorted = samples.map((s) => s.ms).sort((a, b) => a - b);
    const p95 = sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)] ?? 0;
    env.log(`[stress]   -> ${samples.length} req, ${(samples.length / ((mark.end - mark.start) / 1000)).toFixed(1)} req/s, errors ${(rate * 100).toFixed(2)}%, p95 ${p95.toFixed(0)} ms`);
    return rate > 0.1 || p95 > 10_000 ? 'stop' : null;
  };
  const { marks } = await runStages({ stages, users: env.users, log: env.log, iterate, onStageEnd });
  const recovery = await runStages({ stages: [{ vus: 10, seconds: env.quick ? 8 : 60, label: 'recovery 10 VUs' }], users: env.users, log: env.log, iterate });
  return { windows: [...marks, ...recovery.marks] };
}

/** S5a — rate limiting with the DEFAULT limits: 429 + Retry-After, per-user isolation, shed cost. */
export async function s5RateLimit(env) {
  const { client, users, log } = env;
  const [a, b] = users;
  const results = {};
  const start = Date.now();
  const probeBucket = async (label, n, fn) => {
    const statuses = [];
    let firstLimited = null;
    let retryAfter = null;
    let body = null;
    for (let k = 1; k <= n; k += 1) {
      const r = await fn(k);
      statuses.push(r.status);
      if (r.status === 429 && firstLimited == null) {
        firstLimited = k;
        retryAfter = r.headers?.get('retry-after') ?? null;
        body = r.body;
      }
    }
    const counts = {};
    for (const s of statuses) counts[s] = (counts[s] || 0) + 1;
    results[label] = { attempts: n, counts, firstLimitedAt: firstLimited, retryAfterSeconds: retryAfter, body429: body };
    log(`[stress]   ${label}: ${JSON.stringify(results[label])}`);
  };

  // doc-generate: 30 per user per hour.
  const doc = a.docIds[0];
  await client.call(a, 'POST', `${DOCS}/instances/${doc}/validate`, { name: 'rl.validate', cls: 'write' });
  await probeBucket('doc-generate (limit 30/user/h)', 35, () =>
    client.call(a, 'POST', `${DOCS}/instances/${doc}/generate`, { name: 'rl.generate', cls: 'heavy', expect: [200, 429] })
  );
  // Another user of the same bank is not affected (per-user buckets).
  await client.call(b, 'POST', `${DOCS}/instances/${b.docIds[0]}/validate`, { name: 'rl.validate', cls: 'write' });
  const other = await client.call(b, 'POST', `${DOCS}/instances/${b.docIds[0]}/generate`, { name: 'rl.generate.other-user', cls: 'heavy', expect: [200] });
  results['doc-generate other user'] = { status: other.status };
  // ingestion: 60 per user per hour (autoRun=false keeps each call cheap; still metered).
  await probeBucket('ingestion (limit 60/user/h)', 63, (k) =>
    client.call(a, 'POST', '/api/ingestion/jobs', {
      form: multipart({ templateId: a.templateId, autoRun: false }, { bytes: employeesCsv(5, `rl-${k}`), name: `rl-${k}.csv` }),
      expect: [200, 201, 202, 429],
      name: 'rl.ingest',
      cls: 'heavy',
    })
  );
  // exports: 60 per user per hour.
  await probeBucket('exports (limit 60/user/h)', 63, () =>
    client.call(a, 'GET', `/api/ingestion/jobs/${a.exportJobId}/export/file?format=csv`, { name: 'rl.export', cls: 'heavy', raw: true, expect: [200, 429] })
  );
  // uploads (PDF templates): 20 per user per hour; the seed spent 1.
  await probeBucket('uploads (limit 20/user/h)', 22, (k) =>
    client.call(a, 'POST', `${DOCS}/blueprints`, {
      form: multipart({ flow: 'BLANK', name: `RL form ${k}` }, { bytes: env.formBytes, name: `rl-form-${k}.pdf` }),
      expect: [200, 201, 429],
      name: 'rl.blueprint.upload',
      cls: 'heavy',
    })
  );
  // Login per e-mail (the harness raises it to 200 per 15 min for the e2e suite).
  await probeBucket('auth login per e-mail (harness value 200/15 min)', 205, () => client.login(b, { name: 'rl.login' }));
  return { windows: [{ label: 'rate-limit probes', vus: 1, start, end: Date.now() }], extra: results };
}

export { CSV_FIELDS };
