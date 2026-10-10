#!/usr/bin/env node
// Fake OpenAI-compatible LLM for the E2E stack (no network, no model, no key).
//
// The backend's Spring AI client calls POST {base-url}/v1/chat/completions;
// point MODOCUMENT_AI_BASE_URL at this server (no /v1 suffix). Behaviour is
// scripted per mode, switchable at runtime so one stack can drive the AI
// path, the code fallback and every failure class the phase-2 classifier
// must recognise:
//
//   ok           200, a deterministic, schema-valid JSON answer built from the
//                prompt itself (TableExtraction SCHEMA / FULL / ROW_BATCH /
//                EXTRACT_AND_MAP, AutoMapping; anything else gets "{}")
//   error500     500 with an OpenAI-style error body (transient upstream failure)
//   error429     429 rate_limit_exceeded (upstream quota)
//   timeout      accept the request and never answer (client read timeout)
//   badjson      200, valid envelope, but the completion is not JSON
//                (-> INVALID_OUTPUT in the parse step)
//   badenvelope  200 with a body that is not JSON at all (broken gateway)
//
// Control plane (never exposed outside the test network):
//   POST /__mode   {"mode":"error500","count":2}  count = apply to the next N
//                  completions, then fall back to the default mode; omit for
//                  "until changed". Optional "delayMs" delays every answer.
//   GET  /__mode   current mode
//   GET  /__stats  {calls, byKind, byMode, last:[{at, kind, mode, promptChars}]}
//   POST /__reset  default mode, zero counters
//   GET  /health   liveness
//
// Environment: FAKE_LLM_PORT (default 18089), FAKE_LLM_HOST (default
// 127.0.0.1; 0.0.0.0 inside compose), FAKE_LLM_MODE (default ok),
// FAKE_LLM_QUIET=1 to silence the per-request log line.
//
// Privacy: the stub never logs prompt or completion content - only the
// prompt kind and its length (the same evidence rule as the app's AI_CALL).

import http from 'node:http';
import crypto from 'node:crypto';

export const MODES = ['ok', 'error500', 'error429', 'timeout', 'badjson', 'badenvelope'];

const state = {
  defaultMode: process.env.FAKE_LLM_MODE || 'ok',
  mode: process.env.FAKE_LLM_MODE || 'ok',
  remaining: null,
  delayMs: 0,
  calls: 0,
  byKind: {},
  byMode: {},
  last: [],
};

if (!MODES.includes(state.mode)) {
  console.error(`fake-llm: unknown FAKE_LLM_MODE '${state.mode}' (one of ${MODES.join(', ')})`);
  process.exit(2);
}

const hanging = new Set();

// ---------------------------------------------------------------------------
// Prompt analysis — mirrors infrastructure/prompt/TableExtractionPrompt.java
// and AutoMappingPrompt.java (section markers are matched verbatim).
// ---------------------------------------------------------------------------

export function classifyPrompt(prompt) {
  if (/TASK: Return ONLY the column names/.test(prompt)) return 'TABLE_SCHEMA';
  if (/TASK: Extract data rows for this batch using the FIXED column schema/.test(prompt)) {
    return 'TABLE_ROW_BATCH';
  }
  if (/TASK: Extract the main data table and map columns to template fields/.test(prompt)) {
    return 'TABLE_EXTRACT_AND_MAP';
  }
  if (/TASK: Extract the main data table \(column names \+ all rows\)/.test(prompt)) {
    return 'TABLE_FULL';
  }
  if (/TASK: Map each SOURCE COLUMN to the best TEMPLATE FIELD/.test(prompt)) return 'AUTO_MAPPING';
  return 'OTHER';
}

/** Lines `N. "Name"` (optionally followed by ` | ...`) right after a header line. */
function numberedQuotedList(prompt, headerPattern) {
  const lines = prompt.split(/\r?\n/);
  const start = lines.findIndex((line) => headerPattern.test(line));
  if (start < 0) return [];
  const out = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const match = /^\s*\d+\.\s+"(.*?)"(.*)$/.exec(lines[i]);
    if (!match) break;
    out.push({ name: match[1], rest: match[2] });
  }
  return out;
}

/** Everything after the last line equal to `marker` (e.g. "DOCUMENT:"). */
function sectionAfter(prompt, marker) {
  const lines = prompt.split(/\r?\n/);
  let idx = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (lines[i].trim() === marker) idx = i;
  }
  return idx < 0 ? '' : lines.slice(idx + 1).join('\n');
}

const normalize = (value) => String(value ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

function splitCells(line, delimiter) {
  let text = line.replace(/^\s*Row\s+\d+:\s*/i, '');
  if (delimiter === '|') {
    text = text.trim().replace(/^\|/, '').replace(/\|$/, '');
    return text.split('|').map((cell) => cell.trim());
  }
  if (delimiter === 'spaces') return text.trim().split(/\s{2,}/).map((cell) => cell.trim());
  return text.split(delimiter).map((cell) => cell.trim().replace(/^"(.*)"$/, '$1'));
}

function pickDelimiter(lines) {
  for (const candidate of ['\t', '|', ';', ',']) {
    const counts = lines.map((line) => line.split(candidate).length - 1).filter((n) => n > 0);
    if (counts.length >= Math.max(1, Math.ceil(lines.length * 0.6))) return candidate;
  }
  const spaced = lines.filter((line) => /\S\s{2,}\S/.test(line.trim()));
  if (spaced.length >= Math.max(1, Math.ceil(lines.length * 0.6))) return 'spaces';
  return null;
}

const isSeparatorRow = (line) => /^\s*\|?\s*:?-{3,}/.test(line);

/**
 * key: value documents (one record per blank-line separated block).
 * Returns null unless most non-empty lines look like "Label: value".
 */
function parseKeyValue(text) {
  const lines = text.split(/\r?\n/);
  const nonEmpty = lines.filter((line) => line.trim());
  const kv = nonEmpty.filter((line) => /^\s*[^:|\t]{1,40}:\s*\S/.test(line));
  if (nonEmpty.length === 0 || kv.length < Math.ceil(nonEmpty.length * 0.6)) return null;
  const columns = [];
  const rows = [];
  let current = {};
  const flush = () => {
    if (Object.keys(current).length) rows.push(current);
    current = {};
  };
  for (const line of lines) {
    const match = /^\s*([^:|\t]{1,40}):\s*(.*?)\s*$/.exec(line);
    if (!line.trim()) {
      flush();
      continue;
    }
    if (!match) continue;
    const key = match[1].trim();
    if (Object.prototype.hasOwnProperty.call(current, key)) flush();
    if (!columns.includes(key)) columns.push(key);
    current[key] = match[2];
  }
  flush();
  return { columns, rows };
}

/** Best-effort table reader for the document text the prompt carries. */
export function readTable(text, preferredHeaders = []) {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.replace(/\s+$/, ''))
    .filter((line) => line.trim() && !isSeparatorRow(line));
  if (lines.length === 0) return { columns: [...preferredHeaders], rows: [] };

  const delimiter = pickDelimiter(lines);
  if (!delimiter) {
    const kv = parseKeyValue(text);
    if (kv) return kv;
    return { columns: [...preferredHeaders], rows: [] };
  }
  const cells = lines.map((line) => splitCells(line, delimiter));

  let headerIndex = 0;
  if (preferredHeaders.length) {
    const wanted = new Set(preferredHeaders.map(normalize));
    let best = -1;
    cells.forEach((row, i) => {
      const hits = row.filter((cell) => wanted.has(normalize(cell))).length;
      if (hits > best) {
        best = hits;
        headerIndex = i;
      }
    });
  }
  const columns = preferredHeaders.length ? [...preferredHeaders] : cells[headerIndex];
  const rows = cells.slice(headerIndex + 1).map((row) => {
    const record = {};
    columns.forEach((column, i) => {
      record[column] = row[i] ?? '';
    });
    return record;
  });
  return { columns, rows };
}

function bestField(column, fields, taken) {
  const col = normalize(column);
  if (!col) return null;
  const free = fields.filter((field) => !taken.has(field));
  const exact = free.find((field) => normalize(field) === col);
  if (exact) return exact;
  const partial = free.find((field) => {
    const f = normalize(field);
    return f && (f.includes(col) || col.includes(f));
  });
  return partial ?? null;
}

function mapColumns(columns, fields) {
  const taken = new Set();
  const mapping = {};
  for (const column of columns) {
    const field = bestField(column, fields, taken);
    if (field) taken.add(field);
    mapping[column] = field;
  }
  return mapping;
}

/** The 'ok' completion for a prompt: a JSON string the backend can parse. */
export function answerFor(prompt) {
  const kind = classifyPrompt(prompt);
  const authoritative = numberedQuotedList(prompt, /^AUTHORITATIVE COLUMN NAMES/).map((e) => e.name);
  switch (kind) {
    case 'TABLE_SCHEMA': {
      const table = readTable(sectionAfter(prompt, 'HEADER AND SAMPLE ROWS:'), authoritative);
      return JSON.stringify({ columns: table.columns });
    }
    case 'TABLE_FULL': {
      const table = readTable(sectionAfter(prompt, 'DOCUMENT:'), authoritative);
      return JSON.stringify({ columns: table.columns, rows: table.rows });
    }
    case 'TABLE_EXTRACT_AND_MAP': {
      const table = readTable(sectionAfter(prompt, 'DOCUMENT:'), authoritative);
      const fields = numberedQuotedList(prompt, /^TEMPLATE FIELDS \(name \| type\):/).map((e) => e.name);
      return JSON.stringify({
        columns: table.columns,
        mapping: mapColumns(table.columns, fields),
        rows: table.rows,
      });
    }
    case 'TABLE_ROW_BATCH': {
      const fixed = numberedQuotedList(prompt, /^FIXED COLUMN KEYS/).map((e) => e.name);
      const body = sectionAfter(prompt, 'BATCH ROWS:');
      const lines = body.split(/\r?\n/).filter((line) => line.trim() && !isSeparatorRow(line));
      const delimiter = pickDelimiter(lines) ?? '\t';
      const rows = lines
        .map((line) => splitCells(line, delimiter))
        // A repeated header line inside a batch is not data.
        .filter((row) => !(row.length && fixed.length && normalize(row[0]) === normalize(fixed[0])
          && normalize(row[row.length - 1]) === normalize(fixed[fixed.length - 1])))
        .map((row) => Object.fromEntries(fixed.map((column, i) => [column, row[i] ?? ''])));
      return JSON.stringify({ rows });
    }
    case 'AUTO_MAPPING': {
      const columns = numberedQuotedList(prompt, /^Source columns to map:/).map((e) => e.name);
      const availableLine = prompt
        .split(/\r?\n/)
        .find((_, i, all) => i > 0 && /^Available template fields/.test(all[i - 1]));
      const fields = !availableLine || availableLine.trim() === '(none)'
        ? []
        : availableLine.split(',').map((f) => f.trim()).filter(Boolean);
      return JSON.stringify(mapColumns(columns, fields));
    }
    default:
      return '{}';
  }
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 64 * 1024 * 1024) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function promptText(request) {
  const messages = Array.isArray(request?.messages) ? request.messages : [];
  const users = messages.filter((m) => m && m.role === 'user');
  const last = users.length ? users[users.length - 1] : messages[messages.length - 1];
  if (!last) return '';
  if (typeof last.content === 'string') return last.content;
  if (Array.isArray(last.content)) {
    return last.content
      .filter((part) => part && part.type === 'text')
      .map((part) => part.text)
      .join('\n');
  }
  return '';
}

function completionEnvelope(request, content) {
  const promptTokens = Math.ceil(promptText(request).length / 4);
  const completionTokens = Math.ceil(content.length / 4);
  return {
    id: `chatcmpl-${crypto.randomUUID()}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    // Echo whatever the caller asked for; the stub has no model of its own.
    model: typeof request?.model === 'string' ? request.model : 'fake',
    system_fingerprint: 'fake-llm',
    choices: [
      {
        index: 0,
        message: { role: 'assistant', content },
        logprobs: null,
        finish_reason: 'stop',
      },
    ],
    usage: {
      prompt_tokens: promptTokens,
      completion_tokens: completionTokens,
      total_tokens: promptTokens + completionTokens,
    },
  };
}

function sendStream(res, request, content) {
  const id = `chatcmpl-${crypto.randomUUID()}`;
  const created = Math.floor(Date.now() / 1000);
  const model = typeof request?.model === 'string' ? request.model : 'fake';
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  const chunk = (delta, finish) =>
    `data: ${JSON.stringify({
      id,
      object: 'chat.completion.chunk',
      created,
      model,
      choices: [{ index: 0, delta, finish_reason: finish }],
    })}\n\n`;
  res.write(chunk({ role: 'assistant', content }, null));
  res.write(chunk({}, 'stop'));
  res.end('data: [DONE]\n\n');
}

function consumeMode() {
  const mode = state.mode;
  if (state.remaining != null) {
    state.remaining -= 1;
    if (state.remaining <= 0) {
      state.mode = state.defaultMode;
      state.remaining = null;
    }
  }
  return mode;
}

function record(kind, mode, promptChars) {
  state.calls += 1;
  state.byKind[kind] = (state.byKind[kind] || 0) + 1;
  state.byMode[mode] = (state.byMode[mode] || 0) + 1;
  state.last.push({ at: new Date().toISOString(), kind, mode, promptChars });
  if (state.last.length > 50) state.last.shift();
  if (!process.env.FAKE_LLM_QUIET) {
    console.log(`fake-llm: ${mode} ${kind} promptChars=${promptChars}`);
  }
}

async function handleCompletion(req, res) {
  let request;
  try {
    request = JSON.parse(await readBody(req));
  } catch {
    sendJson(res, 400, { error: { message: 'invalid JSON body', type: 'invalid_request_error' } });
    return;
  }
  const prompt = promptText(request);
  const kind = classifyPrompt(prompt);
  const mode = consumeMode();
  record(kind, mode, prompt.length);

  if (state.delayMs > 0 && mode !== 'timeout') {
    await new Promise((resolve) => setTimeout(resolve, state.delayMs));
  }

  switch (mode) {
    case 'error500':
      sendJson(res, 500, {
        error: { message: 'fake-llm scripted internal error', type: 'server_error', code: null },
      });
      return;
    case 'error429':
      sendJson(res, 429, {
        error: { message: 'fake-llm scripted rate limit', type: 'rate_limit_exceeded', code: 'rate_limit_exceeded' },
      });
      return;
    case 'timeout':
      // Never answer; the client's read timeout (or a mode change + server
      // shutdown) ends it. Tracked so shutdown can close the sockets.
      hanging.add(res);
      res.on('close', () => hanging.delete(res));
      return;
    case 'badenvelope':
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"id": "chatcmpl-broken", "choices": [ <<<not json>>>');
      return;
    case 'badjson': {
      const content = 'Sure! Here is the table you asked for:\n| a | b |\n{"columns": ["a", "b"], "rows": [';
      sendJson(res, 200, completionEnvelope(request, content));
      return;
    }
    case 'ok':
    default: {
      const content = answerFor(prompt);
      if (request.stream === true) {
        sendStream(res, request, content);
      } else {
        sendJson(res, 200, completionEnvelope(request, content));
      }
    }
  }
}

async function handleControl(req, res, url) {
  if (url.pathname === '/__mode' && req.method === 'GET') {
    sendJson(res, 200, {
      mode: state.mode,
      defaultMode: state.defaultMode,
      remaining: state.remaining,
      delayMs: state.delayMs,
    });
    return;
  }
  if (url.pathname === '/__mode' && req.method === 'POST') {
    let body;
    try {
      body = JSON.parse((await readBody(req)) || '{}');
    } catch {
      sendJson(res, 400, { error: 'invalid JSON' });
      return;
    }
    if (!MODES.includes(body.mode)) {
      sendJson(res, 400, { error: `mode must be one of ${MODES.join(', ')}` });
      return;
    }
    state.mode = body.mode;
    state.remaining = Number.isInteger(body.count) && body.count > 0 ? body.count : null;
    if (body.default === true) state.defaultMode = body.mode;
    state.delayMs = Number.isInteger(body.delayMs) && body.delayMs > 0 ? body.delayMs : 0;
    if (state.mode !== 'timeout') {
      for (const pending of hanging) pending.destroy();
      hanging.clear();
    }
    sendJson(res, 200, { mode: state.mode, remaining: state.remaining, delayMs: state.delayMs });
    return;
  }
  if (url.pathname === '/__stats' && req.method === 'GET') {
    sendJson(res, 200, {
      calls: state.calls,
      byKind: state.byKind,
      byMode: state.byMode,
      last: state.last,
    });
    return;
  }
  if (url.pathname === '/__reset' && req.method === 'POST') {
    state.mode = process.env.FAKE_LLM_MODE || 'ok';
    state.defaultMode = state.mode;
    state.remaining = null;
    state.delayMs = 0;
    state.calls = 0;
    state.byKind = {};
    state.byMode = {};
    state.last = [];
    for (const pending of hanging) pending.destroy();
    hanging.clear();
    sendJson(res, 200, { mode: state.mode });
    return;
  }
  sendJson(res, 404, { error: 'not found' });
}

export function createServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://fake-llm');
    try {
      if (url.pathname === '/health') {
        sendJson(res, 200, { status: 'UP' });
        return;
      }
      if (url.pathname.startsWith('/__')) {
        await handleControl(req, res, url);
        return;
      }
      if (req.method === 'POST'
          && (url.pathname === '/v1/chat/completions' || url.pathname === '/chat/completions')) {
        await handleCompletion(req, res);
        return;
      }
      if (req.method === 'GET' && (url.pathname === '/v1/models' || url.pathname === '/models')) {
        sendJson(res, 200, { object: 'list', data: [] });
        return;
      }
      sendJson(res, 404, { error: { message: `no route ${req.method} ${url.pathname}` } });
    } catch (error) {
      if (!res.headersSent) sendJson(res, 500, { error: { message: String(error?.message || error) } });
    }
  });
}

const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const port = Number(process.env.FAKE_LLM_PORT || 18089);
  const host = process.env.FAKE_LLM_HOST || '127.0.0.1';
  const server = createServer();
  server.listen(port, host, () => {
    console.log(`fake-llm listening on http://${host}:${port} (mode ${state.mode})`);
  });
  const stop = () => {
    for (const pending of hanging) pending.destroy();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
