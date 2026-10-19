// Seeding for the stress runs, on a fresh e2e/run-local.sh stack.
//
// 1. The bootstrap admin signs in (forced password change + MFA enrolment)
//    and the e2e READ_WRITE operator is created (lib/seed.js, as the e2e
//    suite does).
// 2. STRESS_USERS synthetic READ_WRITE users are added in SQL as copies of
//    the operator's row (same bank, the operator's CURRENT password hash,
//    no forced change) — the product cannot create users without a second
//    approving admin, and the e2e suite breaks that deadlock the same way
//    (e2e/lib/db.js seedClonedUser). Synthetic e-mails only.
// 3. Through the API, per user: a flow template, one 200-row ingestion job
//    run to VALIDATED, a confirmed one-page PDF form template and
//    STRESS_DOCS_PER_USER documents with values.
import { bootstrapAdmin, operator, OPERATOR } from '../../../e2e/lib/seed.js';
import { getAccount } from '../../../e2e/lib/accounts.js';
import { withDb } from '../../../e2e/lib/db.js';
import { CSV_FIELDS, FORM_FIELDS, contractForm, employeesCsv, formValues, multipart, regionOf } from './fixtures.mjs';

const DOCS = '/api/doccreation';

async function inPool(items, size, fn) {
  const results = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(size, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return results;
}

function must(r, what) {
  if (!r.ok) throw new Error(`${what}: ${r.status} ${r.err ?? ''} ${JSON.stringify(r.body)?.slice(0, 300)}`);
  return r.body;
}

export async function seedUsers({ count, runId }) {
  await bootstrapAdmin();
  await operator();
  const opEmail = OPERATOR();
  const password = getAccount(opEmail).password;
  const emails = Array.from({ length: count }, (_, i) => `stress-u${String(i + 1).padStart(3, '0')}-${runId}@e2e.modocument.test`);
  await withDb(async (db) => {
    for (const email of emails) {
      await db.query(
        `INSERT INTO AppUsers (Email, PasswordHash, DisplayName, SettingsJson, CreatedDate,
                               UpdatedDate, BankId, Role, MustChangePassword)
         SELECT $1, PasswordHash, $2, '{}', now(), now(), BankId, 'READ_WRITE', false
           FROM AppUsers WHERE lower(Email) = lower($3)
         ON CONFLICT (Email) DO NOTHING`,
        [email, `Stress User ${email.slice(8, 12)}`, opEmail]
      );
    }
  });
  return emails.map((email, i) => ({ n: i + 1, email, password, token: null, exp: 0 }));
}

/** Logs every user in once (concurrency `pool`); returns the login samples' wall time. */
export async function loginAll(client, users, pool = 10) {
  const t0 = Date.now();
  await inPool(users, pool, async (u) => must(await client.login(u, { name: 'auth.login.initial' }), `login ${u.email}`));
  return Date.now() - t0;
}

export async function createFlowTemplate(client, user, name, fields = CSV_FIELDS) {
  return must(
    await client.call(user, 'POST', '/Templates/CreateWithFields', {
      json: {
        name,
        description: 'stress test (synthetic)',
        fields: fields.map((f) => ({ fieldName: f, dataType: 'Text', rules: [], piiKind: null })),
      },
      expect: [200, 201],
      name: 'setup.template.create',
      cls: 'write',
    }),
    'create template'
  );
}

/** Upload + run to VALIDATED, waiting in the request (wait=true). */
export async function ingest(client, user, { templateId, bytes, fileName, wait = true, name = 'setup.ingest', timeoutMs = 300_000 }) {
  return client.call(user, 'POST', '/api/ingestion/jobs', {
    form: multipart({ templateId, autoRun: true, runUntil: 'VALIDATED', wait }, { bytes, name: fileName }),
    expect: [200, 201, 202],
    name,
    cls: 'heavy',
    timeoutMs,
  });
}

/** Uploads `bytes` as a PDF form template and confirms `fields` as its regions. Returns the blueprint id. */
export async function confirmedBlueprint(client, user, { bytes, fileName, name, fields, cls = 'heavy', tag = 'setup' }) {
  const up = must(
    await client.call(user, 'POST', `${DOCS}/blueprints`, {
      form: multipart({ flow: 'BLANK', name }, { bytes, name: fileName }),
      expect: [200, 201],
      name: `${tag}.blueprint.upload`,
      cls,
      timeoutMs: 300_000,
    }),
    'blueprint upload'
  );
  const id = up.id;
  const draft = must(await client.call(user, 'GET', `${DOCS}/blueprints/${id}`, { name: `${tag}.blueprint.get`, cls: 'read' }), 'blueprint get');
  must(
    await client.call(user, 'PUT', `${DOCS}/blueprints/${id}/overlay`, {
      json: { baseVersion: draft.currentVersion ?? draft.version, regions: fields.map(regionOf), edits: [], confirm: true },
      expect: [200],
      name: `${tag}.blueprint.confirm`,
      cls: 'write',
      timeoutMs: 300_000,
    }),
    'blueprint confirm'
  );
  return id;
}

export async function createDocument(client, user, blueprintId, title, tag = 'setup') {
  return must(
    await client.call(user, 'POST', `${DOCS}/instances`, {
      json: { blueprintId, title },
      expect: [200, 201],
      name: `${tag}.instance.create`,
      cls: 'write',
    }),
    'create instance'
  ).id;
}

/** Per-user working set for the scenarios. */
export async function seedWorkingSet(client, users, { runId, docsPerUser = 2, rows = 200, pool = 8, log = console.log }) {
  const formBytes = contractForm();
  let done = 0;
  await inPool(users, pool, async (u) => {
    const template = await createFlowTemplate(client, u, `Stress Employees ${runId} ${u.n}`);
    u.templateId = template.templateId;
    const job = must(
      await ingest(client, u, { templateId: u.templateId, bytes: employeesCsv(rows, `${u.n}-seed`), fileName: `employees-${u.n}-seed.csv` }),
      'seed ingest'
    );
    u.jobId = job.ingestionJobId;
    // A second job only for exports: row edits send a job back to MAPPED, and exports need VALIDATED.
    const exportJob = must(
      await ingest(client, u, { templateId: u.templateId, bytes: employeesCsv(rows, `${u.n}-export`), fileName: `employees-${u.n}-export.csv` }),
      'seed export ingest'
    );
    u.exportJobId = exportJob.ingestionJobId;
    u.blueprintId = await confirmedBlueprint(client, u, {
      bytes: formBytes,
      fileName: `stress-form-${u.n}.pdf`,
      name: `Stress form ${runId} ${u.n}`,
      fields: FORM_FIELDS,
    });
    u.docIds = [];
    for (let d = 0; d < docsPerUser; d += 1) {
      const id = await createDocument(client, u, u.blueprintId, `Stress doc ${u.n}.${d + 1}`);
      must(
        await client.call(u, 'PUT', `${DOCS}/instances/${id}/values`, {
          json: { values: formValues(u.n * 10 + d) },
          name: 'setup.instance.values',
          cls: 'write',
        }),
        'seed values'
      );
      u.docIds.push(id);
    }
    u.uploads = 0;
    done += 1;
    if (done % 10 === 0) log(`[stress] seeded ${done}/${users.length} users`);
  });
}

/** The job's mapped rows as the row-edit body: [{field: value}]. */
export function rowsOf(job) {
  const t = job.mappedRows || job.parsedTable;
  if (!t) return null;
  if (Array.isArray(t.rows) && t.rows.length && !Array.isArray(t.rows[0])) return t.rows;
  const headers = t.headers || t.columns;
  if (Array.isArray(t.rows) && Array.isArray(headers)) {
    return t.rows.map((r) => Object.fromEntries(headers.map((h, i) => [h, r[i] ?? ''])));
  }
  return null;
}
