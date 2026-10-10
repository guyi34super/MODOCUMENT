import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from './Modal';
import { Button, Callout, Field, inputClass } from './ui';
import { isPendingChange } from '../api/changes';
import { createConnection, isQueuedTest, testConnection, updateConnection } from '../api/integrations';
import { useToast } from '../context/ToastContext';
import {
  CONNECTION_TYPES,
  CONNECTION_TYPE_LABELS,
  DB_VENDORS,
  REST_AUTH_TYPES,
  SSL_MODES,
} from '../pages/IntegrationsCommon';

const STEPS = ['Type', 'Mode', 'Details', 'Credentials', 'Review & test'];

/** Non-secret config keys per connection type (ConnectionContext.config). */
const CONFIG_KEYS = {
  REST_API: ['baseUrl', 'username', 'apiKeyHeader', 'tokenUrl', 'clientId', 'scope'],
  DATABASE: ['vendor', 'host', 'port', 'database', 'schema', 'sslmode', 'username'],
  SFTP: ['host', 'port', 'folder', 'hostKeyFingerprint', 'username', 'sftpAuth'],
  WEBHOOK: ['url'],
};

/** Outbound auth type stored for the connection (REST picks it; the others are fixed). */
function authTypeOf(type, form) {
  if (type === 'REST_API') return form.authType || 'NONE';
  if (type === 'WEBHOOK') return 'HMAC_SIGNATURE';
  return 'BASIC';
}

/**
 * The write-only secret fields (SecretMaterial keys) a connection needs.
 * Empty for REST auth NONE.
 */
function secretFields(type, form) {
  switch (type) {
    case 'REST_API':
      switch (form.authType) {
        case 'BASIC':
          return [{ key: 'password', label: 'Password' }];
        case 'BEARER':
          return [{ key: 'token', label: 'Bearer token' }];
        case 'API_KEY_HEADER':
          return [{ key: 'apiKey', label: 'API key' }];
        case 'OAUTH2_CLIENT_CREDENTIALS':
          return [{ key: 'clientSecret', label: 'Client secret' }];
        case 'MTLS':
          return [
            { key: 'clientCertPem', label: 'Client certificate (PEM)', multiline: true },
            { key: 'clientKeyPem', label: 'Client private key (PEM)', multiline: true },
          ];
        default:
          return [];
      }
    case 'DATABASE':
      return [{ key: 'password', label: 'Database password' }];
    case 'SFTP':
      return form.config.sftpAuth === 'PRIVATE_KEY'
        ? [{ key: 'privateKeyPem', label: 'SSH private key (PEM)', multiline: true }]
        : [{ key: 'password', label: 'SFTP password' }];
    case 'WEBHOOK':
      return [{ key: 'hmacSecret', label: 'Signing secret (HMAC)' }];
    default:
      return [];
  }
}

function initialForm(connection) {
  if (!connection) {
    return {
      name: '',
      type: '',
      mode: 'DIRECT',
      agentId: '',
      authType: 'NONE',
      secretRef: '',
      config: { sslmode: 'require', sftpAuth: 'PASSWORD' },
    };
  }
  const config = {};
  Object.entries(connection.config || {}).forEach(([key, value]) => {
    config[key] = value === null || value === undefined ? '' : String(value);
  });
  return {
    name: connection.name || '',
    type: connection.type,
    mode: connection.mode || 'DIRECT',
    agentId: connection.agentId ? String(connection.agentId) : '',
    authType: connection.authType || 'NONE',
    // A secretRef is a NAME, not a secret: safe to show and edit.
    secretRef: connection.secretRef || '',
    config: { sftpAuth: 'PASSWORD', ...config },
  };
}

const SHA256_FINGERPRINT = /^SHA256:[A-Za-z0-9+/]{43}=?$/;

/**
 * The "new / edit connection" wizard (ws25, contracts §7.3): type → mode
 * (Direct, or through an on-prem Agent) → details per type → credentials →
 * review & test. Credentials are WRITE-ONLY: never prefilled; an existing
 * connection shows "Secret set ✓ — Replace", and only a replaced secret is
 * sent. In AGENT mode the dashboard stores only a `secretRef` name — the
 * secret itself lives in the agent's own store. Saving answers 202 (the
 * INTEGRATION_CONNECTION_CHANGE goes to a second administrator) or 200 for
 * a rename. Every secret typed here lives in this component's state only and
 * is wiped when the dialog closes.
 *
 * Props: `open`, `onClose()`, `connection` (ConnectionView to edit, or
 * null), `agents` (AgentView[] for the Agent picker), `onSaved(result)`.
 */
export default function ConnectionWizard({ open, onClose, connection, agents = [], onSaved }) {
  const toast = useToast();
  const formId = useId();
  const headingRef = useRef(null);
  const editing = Boolean(connection);

  const [step, setStep] = useState(0);
  const [form, setForm] = useState(() => initialForm(connection));
  const [secret, setSecret] = useState({});
  const [replacing, setReplacing] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null);

  // Fresh state on every open; on close the secret is dropped from memory.
  useEffect(() => {
    if (open) {
      setStep(editing ? 2 : 0);
      setForm(initialForm(connection));
    } else {
      setForm(initialForm(null));
    }
    setSecret({});
    setReplacing(false);
    setError('');
    setTestResult(null);
    setSaving(false);
  }, [open, connection, editing]);

  // Move focus to the step's heading so a screen reader announces it.
  useEffect(() => {
    if (open) headingRef.current?.focus();
  }, [step, open]);

  const setConfig = (key, value) =>
    setForm((current) => ({ ...current, config: { ...current.config, [key]: value } }));

  const authChanged =
    editing &&
    (form.type !== connection.type ||
      authTypeOf(form.type, form) !== (connection.authType || authTypeOf(connection.type, initialForm(connection))) ||
      (form.type === 'SFTP' && form.config.sftpAuth !== (connection.config?.sftpAuth || 'PASSWORD')) ||
      form.mode !== connection.mode);
  // A stored secret can be kept only while the auth scheme is unchanged.
  const secretKept = editing && connection.secretSet && !replacing && !authChanged;
  const fields = secretFields(form.type, form);
  const agent = form.mode === 'AGENT';

  const validate = (index) => {
    const c = form.config;
    switch (index) {
      case 0:
        if (!form.type) return 'Choose a connection type.';
        return '';
      case 1:
        if (agent && !form.agentId) return 'Choose the agent that reaches this system.';
        return '';
      case 2:
        if (!form.name.trim()) return 'Give the connection a name.';
        if (form.type === 'REST_API' && !/^https:\/\//i.test(c.baseUrl || ''))
          return 'The base URL must start with https://.';
        if (form.type === 'DATABASE' && (!c.vendor || !c.host || !c.port || !c.database))
          return 'Vendor, host, port and database are required.';
        if (form.type === 'SFTP') {
          if (!c.host || !c.port || !c.folder) return 'Host, port and folder are required.';
          if (!SHA256_FINGERPRINT.test((c.hostKeyFingerprint || '').trim()))
            return 'Pin the server host key as its SHA256 fingerprint (SHA256:… as printed by ssh-keygen -lf).';
        }
        if (form.type === 'WEBHOOK' && !/^https:\/\//i.test(c.url || ''))
          return 'The webhook URL must start with https://.';
        if (c.port && !/^\d{1,5}$/.test(c.port)) return 'The port must be a number.';
        return '';
      case 3:
        if (agent) {
          if (fields.length > 0 && !form.secretRef.trim())
            return "Name the secret in the agent's local store (secretRef).";
          return '';
        }
        if (secretKept || fields.length === 0) return '';
        if (fields.some((f) => !(secret[f.key] || '').trim())) return 'Enter every credential field.';
        return '';
      default:
        return '';
    }
  };

  const goTo = (target) => {
    for (let i = step; i < target; i += 1) {
      const message = validate(i);
      if (message) {
        setStep(i);
        setError(message);
        return;
      }
    }
    setError('');
    setStep(target);
  };

  const buildRequest = () => {
    const config = {};
    (CONFIG_KEYS[form.type] || []).forEach((key) => {
      const value = form.config[key];
      if (value !== undefined && value !== null && String(value).trim() !== '') {
        config[key] = String(value).trim();
      }
    });
    if (form.type === 'REST_API') {
      // Only the keys the chosen auth type uses.
      if (form.authType !== 'BASIC') delete config.username;
      if (form.authType !== 'API_KEY_HEADER') delete config.apiKeyHeader;
      if (form.authType !== 'OAUTH2_CLIENT_CREDENTIALS') {
        delete config.tokenUrl;
        delete config.clientId;
        delete config.scope;
      }
    }
    const request = {
      name: form.name.trim(),
      type: form.type,
      mode: form.mode,
      agentId: agent ? Number(form.agentId) : null,
      config,
      authType: authTypeOf(form.type, form),
    };
    if (agent) {
      request.secretRef = form.secretRef.trim() || null;
    } else if (!secretKept && fields.length > 0) {
      request.secret = Object.fromEntries(fields.map((f) => [f.key, secret[f.key]]));
    }
    if (editing) request.expectedVersion = connection.version;
    return request;
  };

  const save = async () => {
    for (let i = 0; i < 4; i += 1) {
      const message = validate(i);
      if (message) {
        setStep(i);
        setError(message);
        return;
      }
    }
    setSaving(true);
    setError('');
    try {
      const request = buildRequest();
      const result = editing
        ? await updateConnection(connection.id, request)
        : await createConnection(request);
      setSecret({});
      if (isPendingChange(result)) {
        toast.success(`Sent for approval (change #${result.pendingChangeId})`);
      } else {
        toast.success(`Connection "${request.name}" saved`);
      }
      onSaved?.(result, request);
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const runTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      setTestResult(await testConnection(connection.id));
    } catch (err) {
      setTestResult({ ok: false, message: err.message });
    } finally {
      setTesting(false);
    }
  };

  const onSubmit = (event) => {
    event.preventDefault();
    if (step < STEPS.length - 1) goTo(step + 1);
    else save();
  };

  const last = step === STEPS.length - 1;

  return (
    <Modal
      open={open}
      onClose={() => {
        if (!saving) onClose?.();
      }}
      title={editing ? `Edit connection "${connection.name}"` : 'New connection'}
      size="lg"
      closeOnBackdrop={false}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          {step > 0 && (
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                setError('');
                setStep(step - 1);
              }}
              disabled={saving}
            >
              Back
            </Button>
          )}
          {last && editing && (
            <Button type="button" variant="secondary" onClick={runTest} disabled={testing || saving}>
              {testing ? 'Testing…' : 'Test connection'}
            </Button>
          )}
          <Button type="submit" form={formId} disabled={saving}>
            {last ? (saving ? 'Saving…' : 'Save') : 'Next'}
          </Button>
        </>
      }
    >
      <ol aria-label="Wizard steps" className="mb-4 flex flex-wrap gap-1 text-xs">
        {STEPS.map((label, i) => (
          <li
            key={label}
            aria-current={i === step ? 'step' : undefined}
            className={`rounded-full px-2.5 py-0.5 ${
              i === step
                ? 'bg-accent text-white'
                : i < step
                  ? 'bg-pill text-accent'
                  : 'bg-neutral-100 text-muted'
            }`}
          >
            {i + 1}. {label}
          </li>
        ))}
      </ol>
      <h3 ref={headingRef} tabIndex={-1} className="mb-3 text-base font-medium text-ink outline-none">
        {STEPS[step]}
      </h3>

      <form id={formId} onSubmit={onSubmit} noValidate className="space-y-3">
        {step === 0 && (
          <fieldset>
            <legend className="mb-2 text-sm text-muted">What kind of system is it?</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {CONNECTION_TYPES.map((t) => (
                <label
                  key={t.value}
                  className={`flex cursor-pointer gap-2 rounded border p-3 ${
                    form.type === t.value ? 'border-accent bg-pill/40' : 'border-line'
                  } ${editing ? 'cursor-not-allowed opacity-60' : ''}`}
                >
                  <input
                    type="radio"
                    name="connection-type"
                    value={t.value}
                    checked={form.type === t.value}
                    disabled={editing}
                    onChange={() => setForm({ ...form, type: t.value })}
                  />
                  <span>
                    <span className="block font-medium">{t.label}</span>
                    <span className="block text-xs text-muted">{t.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        {step === 1 && (
          <>
            <fieldset>
              <legend className="mb-2 text-sm text-muted">How does MoDocument reach it?</legend>
              <div className="space-y-2">
                <label className="flex gap-2">
                  <input
                    type="radio"
                    name="connection-mode"
                    value="DIRECT"
                    checked={form.mode === 'DIRECT'}
                    onChange={() => setForm({ ...form, mode: 'DIRECT' })}
                  />
                  <span>
                    <span className="block font-medium">Direct</span>
                    <span className="block text-xs text-muted">
                      From the MoDocument cloud. Private addresses need an approved egress
                      destination.
                    </span>
                  </span>
                </label>
                <label className="flex gap-2">
                  <input
                    type="radio"
                    name="connection-mode"
                    value="AGENT"
                    checked={form.mode === 'AGENT'}
                    onChange={() => setForm({ ...form, mode: 'AGENT' })}
                  />
                  <span>
                    <span className="block font-medium">Agent</span>
                    <span className="block text-xs text-muted">
                      Through a connector agent inside your network; credentials stay with the
                      agent.
                    </span>
                  </span>
                </label>
              </div>
            </fieldset>
            {agent && (
              <Field label="Agent">
                <select
                  className={inputClass}
                  value={form.agentId}
                  onChange={(e) => setForm({ ...form, agentId: e.target.value })}
                >
                  <option value="">Choose an agent…</option>
                  {agents
                    .filter((a) => a.status !== 'REVOKED')
                    .map((a) => (
                      <option key={a.id} value={String(a.id)}>
                        {a.name} ({a.status})
                      </option>
                    ))}
                </select>
              </Field>
            )}
            {agent && agents.length === 0 && (
              <p className="text-xs text-muted">
                No agents yet —{' '}
                <Link to="/admin/integrations/agents" className="text-accent underline">
                  register one
                </Link>{' '}
                first.
              </p>
            )}
          </>
        )}

        {step === 2 && (
          <DetailsStep form={form} setForm={setForm} setConfig={setConfig} />
        )}

        {step === 3 && (
          <CredentialsStep
            agent={agent}
            fields={fields}
            form={form}
            setForm={setForm}
            secret={secret}
            setSecret={setSecret}
            secretKept={secretKept}
            canKeep={editing && connection.secretSet && !authChanged}
            onReplace={() => setReplacing(true)}
            onKeep={() => {
              setReplacing(false);
              setSecret({});
            }}
          />
        )}

        {step === 4 && (
          <div className="space-y-3">
            <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[9rem_1fr]">
              <dt className="text-muted">Name</dt>
              <dd>{form.name}</dd>
              <dt className="text-muted">Type</dt>
              <dd>{CONNECTION_TYPE_LABELS[form.type]}</dd>
              <dt className="text-muted">Mode</dt>
              <dd>
                {agent
                  ? `Agent: ${agents.find((a) => String(a.id) === form.agentId)?.name || form.agentId}`
                  : 'Direct'}
              </dd>
              <dt className="text-muted">Credentials</dt>
              <dd>
                {agent
                  ? form.secretRef
                    ? `Agent secret "${form.secretRef}"`
                    : 'None'
                  : fields.length === 0
                    ? 'None'
                    : secretKept
                      ? 'Secret set ✓ (unchanged)'
                      : 'New secret (write-only)'}
              </dd>
            </dl>
            <Callout variant="info">
              Saving sends the connection to a second administrator for approval; a rename alone
              applies straight away.
            </Callout>
            {!editing && (
              <p className="text-xs text-muted">
                Test connection is available once the connection is approved (from the
                connections list).
              </p>
            )}
            {editing && (
              <p className="text-xs text-muted">Test connection checks the saved settings.</p>
            )}
            <TestResult result={testResult} />
          </div>
        )}

        {error && (
          <p role="alert" className="text-sm text-danger-ink">
            {error}
          </p>
        )}
        {/* Enter in a field advances the wizard. */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

function DetailsStep({ form, setForm, setConfig }) {
  const c = form.config;
  const input = (key, props = {}) => (
    <input
      className={inputClass}
      value={c[key] || ''}
      onChange={(e) => setConfig(key, e.target.value)}
      {...props}
    />
  );
  return (
    <>
      <Field label="Connection name">
        <input
          className={inputClass}
          maxLength={200}
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
      </Field>
      {form.type === 'REST_API' && (
        <>
          <Field label="Base URL" hint="HTTPS only, e.g. https://api.bank.example/v1">
            {input('baseUrl', { type: 'url', placeholder: 'https://' })}
          </Field>
          <Field label="Outbound authentication">
            <select
              className={inputClass}
              value={form.authType}
              onChange={(e) => setForm({ ...form, authType: e.target.value })}
            >
              {REST_AUTH_TYPES.map((a) => (
                <option key={a.value} value={a.value}>
                  {a.label}
                </option>
              ))}
            </select>
          </Field>
          {form.authType === 'BASIC' && <Field label="Username">{input('username')}</Field>}
          {form.authType === 'API_KEY_HEADER' && (
            <Field label="Header name" hint="e.g. X-Api-Key">
              {input('apiKeyHeader')}
            </Field>
          )}
          {form.authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
            <>
              <Field label="Token URL">{input('tokenUrl', { type: 'url' })}</Field>
              <Field label="Client ID">{input('clientId')}</Field>
              <Field label="Scope" hint="Optional">
                {input('scope')}
              </Field>
            </>
          )}
        </>
      )}
      {form.type === 'DATABASE' && (
        <>
          <Field label="Database vendor">
            <select
              className={inputClass}
              value={c.vendor || ''}
              onChange={(e) => {
                const vendor = DB_VENDORS.find((v) => v.value === e.target.value);
                setForm({
                  ...form,
                  config: {
                    ...c,
                    vendor: e.target.value,
                    port: c.port || (vendor ? String(vendor.port) : ''),
                  },
                });
              }}
            >
              <option value="">Choose…</option>
              {DB_VENDORS.map((v) => (
                <option key={v.value} value={v.value}>
                  {v.label}
                </option>
              ))}
            </select>
          </Field>
          <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
            <Field label="Host">{input('host')}</Field>
            <Field label="Port">{input('port', { inputMode: 'numeric' })}</Field>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Database">{input('database')}</Field>
            <Field label="Schema" hint="Optional">
              {input('schema')}
            </Field>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="SSL mode">
              <select
                className={inputClass}
                value={c.sslmode || 'require'}
                onChange={(e) => setConfig('sslmode', e.target.value)}
              >
                {SSL_MODES.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Username">{input('username', { autoComplete: 'off' })}</Field>
          </div>
        </>
      )}
      {form.type === 'SFTP' && (
        <>
          <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
            <Field label="Host">{input('host')}</Field>
            <Field label="Port">{input('port', { inputMode: 'numeric', placeholder: '22' })}</Field>
          </div>
          <Field label="Folder" hint="Reads and writes stay inside this folder.">
            {input('folder', { placeholder: '/outbound/modocument' })}
          </Field>
          <Field
            label="Pinned host-key fingerprint"
            hint="SHA256 fingerprint of the server's host key (ssh-keygen -lf). Unknown keys are refused."
          >
            {input('hostKeyFingerprint', { placeholder: 'SHA256:…', spellCheck: false })}
          </Field>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Username">{input('username', { autoComplete: 'off' })}</Field>
            <Field label="Sign in with">
              <select
                className={inputClass}
                value={c.sftpAuth || 'PASSWORD'}
                onChange={(e) => setConfig('sftpAuth', e.target.value)}
              >
                <option value="PASSWORD">Password</option>
                <option value="PRIVATE_KEY">Private key</option>
              </select>
            </Field>
          </div>
        </>
      )}
      {form.type === 'WEBHOOK' && (
        <Field
          label="Webhook URL"
          hint="Each call is signed: X-MoDocument-Signature (HMAC-SHA256) + X-MoDocument-Timestamp."
        >
          {input('url', { type: 'url', placeholder: 'https://' })}
        </Field>
      )}
    </>
  );
}

function CredentialsStep({
  agent,
  fields,
  form,
  setForm,
  secret,
  setSecret,
  secretKept,
  canKeep,
  onReplace,
  onKeep,
}) {
  if (fields.length === 0) {
    return <p className="text-muted">This connection needs no credentials.</p>;
  }
  if (agent) {
    return (
      <Field
        label="Secret reference"
        hint="The NAME of the secret in the agent's local store (env, file or your vault). The secret itself never leaves your network."
      >
        <input
          className={inputClass}
          value={form.secretRef}
          autoComplete="off"
          spellCheck={false}
          placeholder="e.g. CORE_DB_PASSWORD"
          onChange={(e) => setForm({ ...form, secretRef: e.target.value })}
        />
      </Field>
    );
  }
  if (secretKept) {
    return (
      <div className="flex flex-wrap items-center gap-3 rounded border border-line p-3">
        <span className="text-success-ink">Secret set ✓</span>
        <span className="text-xs text-muted">Stored encrypted; it is never shown again.</span>
        <Button type="button" size="sm" variant="secondary" onClick={onReplace}>
          Replace
        </Button>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        Write-only: stored encrypted and never shown again — not even to administrators.
      </p>
      {fields.map((f) => (
        <Field key={f.key} label={f.label}>
          {f.multiline ? (
            <textarea
              rows={4}
              spellCheck={false}
              autoComplete="off"
              className={`${inputClass} font-mono text-xs`}
              value={secret[f.key] || ''}
              onChange={(e) => setSecret({ ...secret, [f.key]: e.target.value })}
            />
          ) : (
            <input
              type="password"
              autoComplete="new-password"
              className={inputClass}
              value={secret[f.key] || ''}
              onChange={(e) => setSecret({ ...secret, [f.key]: e.target.value })}
            />
          )}
        </Field>
      ))}
      {canKeep && (
        <Button type="button" size="sm" variant="secondary" onClick={onKeep}>
          Keep the current secret
        </Button>
      )}
    </div>
  );
}

/** `{ok, message, latencyMillis}` or, for an AGENT connection, the queued run. */
export function TestResult({ result }) {
  if (!result) return null;
  if (isQueuedTest(result)) {
    return (
      <div role="status">
        <Callout variant="info" title="Test queued on the agent">
          The agent runs the test.{' '}
          <Link to={`/admin/integrations/runs/${result.runId}`} className="underline">
            Follow run #{result.runId}
          </Link>
          .
        </Callout>
      </div>
    );
  }
  return (
    <div role="status">
      <Callout
        variant={result.ok ? 'success' : 'error'}
        title={result.ok ? 'Connection OK' : 'Connection failed'}
      >
        {result.message}
        {typeof result.latencyMillis === 'number' && ` (${result.latencyMillis} ms)`}
      </Callout>
    </div>
  );
}
