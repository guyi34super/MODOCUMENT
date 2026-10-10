import { useCallback, useEffect, useId, useState } from 'react';
import { isPendingChange } from '../api/changes';
import {
  createApiClient,
  getApiClientInitialSecret,
  listApiClients,
  revokeApiClient,
  rotateApiClient,
} from '../api/integrations';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import Modal from '../components/Modal';
import ConfirmDialog from '../components/ConfirmDialog';
import { Button, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import {
  API_AUTH_METHODS,
  API_SCOPES,
  OneTimeSecret,
  PendingTag,
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
  formatDateTime,
} from './IntegrationsCommon';

const AUTH_LABELS = Object.fromEntries(API_AUTH_METHODS.map((m) => [m.value, m.label]));
const SCOPE_LABELS = Object.fromEntries(API_SCOPES.map((s) => [s.value, s.label]));

const EMPTY = {
  name: '',
  authMethod: 'OAUTH2_CLIENT_CREDENTIALS',
  scopes: ['INTEGRATION_INGEST', 'INTEGRATION_RESULTS'],
  cidrs: '',
  expiresOn: '',
  certificatePem: '',
};

const CIDR = /^[0-9a-fA-F:.]+(\/\d{1,3})?$/;

/** The client's identifier for the list: client id, key prefix or certificate. */
function identifier(client) {
  if (client.authMethod === 'API_KEY') return client.keyPrefix ? `${client.keyPrefix}.…` : '—';
  if (client.authMethod === 'MTLS') return client.certSubject || client.certFingerprint || '—';
  return client.clientId || '—';
}

/** A one-time secret is only issued for secret-based methods. */
const hasSecret = (client) => client.authMethod !== 'MTLS';

/**
 * `/admin/integrations/api-clients` (ws26): the systems that may call the
 * Integration API. Creating one goes to a second administrator (202); after
 * approval the maker reveals the secret ONCE ("Reveal secret (once)" →
 * `GET …/initial-secret`). Rotate shows the new secret once (the old one
 * keeps working for the overlap); Revoke is immediate. A shown secret lives
 * in this page's state only and is wiped when its dialog closes.
 */
export default function IntegrationApiClientsPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations', to: '/admin/integrations' }, { label: 'API clients' }]}
        title="API clients"
        subtitle="Systems that may call the MoDocument Integration API"
      />
      <RequireIntegrationAdmin>
        <ApiClients />
      </RequireIntegrationAdmin>
    </div>
  );
}

function ApiClients() {
  const toast = useToast();
  const formId = useId();
  const [clients, setClients] = useState(null);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [sent, setSent] = useState(null);
  // { title, clientId, secret } — shown once, cleared on close.
  const [revealed, setRevealed] = useState(null);
  const [revoking, setRevoking] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = useCallback(() => {
    listApiClients()
      .then((rows) => {
        setClients(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setForm(EMPTY);
    setFormError('');
    setCreating(true);
  };

  const submit = async (event) => {
    event.preventDefault();
    const name = form.name.trim();
    const allowedCidrs = form.cidrs
      .split(/[\s,]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (!name) return setFormError('Give the client a name.');
    if (form.scopes.length === 0) return setFormError('Pick at least one scope.');
    const bad = allowedCidrs.find((c) => !CIDR.test(c));
    if (bad) return setFormError(`"${bad}" is not an IP address or CIDR range.`);
    if (form.authMethod === 'MTLS' && !form.certificatePem.includes('BEGIN CERTIFICATE'))
      return setFormError('Paste the client certificate in PEM format (-----BEGIN CERTIFICATE-----).');
    setFormError('');
    setSaving(true);
    try {
      const result = await createApiClient({
        name,
        authMethod: form.authMethod,
        scopes: form.scopes,
        allowedCidrs,
        // End of the chosen day, UTC.
        expiresAt: form.expiresOn ? `${form.expiresOn}T23:59:59Z` : null,
        certificatePem: form.certificatePem.trim(),
      });
      if (isPendingChange(result)) {
        toast.success(`Sent for approval (change #${result.pendingChangeId})`);
        setSent({ what: `Creating API client "${name}"`, change: result });
      } else {
        toast.success(`API client "${name}" created`);
      }
      setCreating(false);
      setForm(EMPTY);
      load();
    } catch (err) {
      setFormError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const reveal = async (client) => {
    setBusy(client.id);
    try {
      const result = await getApiClientInitialSecret(client.id);
      setRevealed({ title: `Secret for "${client.name}"`, ...result });
      load();
    } catch (err) {
      toast.error(
        err.status === 404 || err.status === 410
          ? 'The one-time secret is no longer available (already shown, or older than 24 hours). Rotate the client to get a new one.'
          : err.message
      );
    } finally {
      setBusy(null);
    }
  };

  const rotate = async (client) => {
    setBusy(client.id);
    try {
      const result = await rotateApiClient(client.id);
      setRevealed({
        title: `New secret for "${client.name}"`,
        note: 'The previous secret keeps working for the overlap period, then stops.',
        ...result,
      });
      load();
    } catch (err) {
      toast.error(err.message);
    } finally {
      setBusy(null);
    }
  };

  const confirmRevoke = async () => {
    setBusy(revoking.id);
    try {
      await toast.promise(revokeApiClient(revoking.id), {
        loading: 'Revoking…',
        success: `API client "${revoking.name}" revoked`,
      });
      setRevoking(null);
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(null);
    }
  };

  const columns = [
    {
      key: 'name',
      label: 'Name',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="font-medium">{row.name}</span>
          {row.kind === 'AGENT' && <Tag tone="info">Agent</Tag>}
          {row.pendingChangeId && <PendingTag changeId={row.pendingChangeId} />}
        </span>
      ),
    },
    { key: 'authMethod', label: 'Auth', render: (row) => AUTH_LABELS[row.authMethod] || row.authMethod },
    {
      key: 'identifier',
      label: 'Identifier',
      render: (row) => <span className="break-all font-mono text-xs">{identifier(row)}</span>,
    },
    {
      key: 'scopes',
      label: 'Scopes',
      render: (row) => (row.scopes || []).map((s) => SCOPE_LABELS[s] || s).join(', ') || '—',
    },
    {
      key: 'allowedCidrs',
      label: 'IP allowlist',
      render: (row) =>
        (row.allowedCidrs || []).length ? (
          <span className="font-mono text-xs">{row.allowedCidrs.join(', ')}</span>
        ) : (
          'Any'
        ),
    },
    { key: 'expiresAt', label: 'Expires', render: (row) => formatDateTime(row.expiresAt) },
    {
      key: 'status',
      label: 'Status',
      render: (row) => (
        <div>
          <StatusChip status={row.status} />
          {row.lastUsedAt && (
            <p className="mt-0.5 text-xs text-muted">Used {formatDateTime(row.lastUsedAt)}</p>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      label: '',
      render: (row) =>
        row.status === 'ACTIVE' && row.kind !== 'AGENT' ? (
          <div className="flex flex-wrap justify-end gap-1">
            {hasSecret(row) && row.initialSecretAvailable !== false && !row.pendingChangeId && (
              <Button
                size="sm"
                onClick={() => reveal(row)}
                disabled={busy === row.id}
                aria-label={`Reveal secret (once) for ${row.name}`}
              >
                Reveal secret (once)
              </Button>
            )}
            {hasSecret(row) && (
              <Button
                size="sm"
                variant="secondary"
                onClick={() => rotate(row)}
                disabled={busy === row.id}
                aria-label={`Rotate ${row.name}`}
              >
                Rotate
              </Button>
            )}
            <Button
              size="sm"
              variant="danger"
              onClick={() => setRevoking(row)}
              disabled={busy === row.id}
              aria-label={`Revoke ${row.name}`}
            >
              Revoke
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <>
      <div className="mb-4 flex justify-end">
        <Button onClick={openCreate}>New API client</Button>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!clients && !error && <Spinner label="Loading API clients…" />}
      {clients && (
        <DataTable columns={columns} rows={clients} emptyMessage="No API clients yet." />
      )}

      <Modal
        open={creating}
        onClose={() => !saving && setCreating(false)}
        title="New API client"
        description="A second administrator approves it; then you reveal its secret once."
        size="lg"
        closeOnBackdrop={false}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setCreating(false)} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" form={formId} disabled={saving}>
              Send for approval
            </Button>
          </>
        }
      >
        <form id={formId} onSubmit={submit} noValidate className="space-y-3">
          <Field label="Name">
            <input
              className={inputClass}
              maxLength={200}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </Field>
          <fieldset>
            <legend className="mb-1 font-medium">Authentication method</legend>
            <div className="space-y-1">
              {API_AUTH_METHODS.map((m) => (
                <label key={m.value} className="flex gap-2">
                  <input
                    type="radio"
                    name="auth-method"
                    value={m.value}
                    checked={form.authMethod === m.value}
                    onChange={() => setForm({ ...form, authMethod: m.value })}
                  />
                  <span>
                    {m.label} <span className="text-xs text-muted">— {m.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          {form.authMethod === 'MTLS' && (
            <Field label="Client certificate (PEM)" hint="The public certificate only — never the private key.">
              <textarea
                rows={5}
                spellCheck={false}
                className={`${inputClass} font-mono text-xs`}
                placeholder="-----BEGIN CERTIFICATE-----"
                value={form.certificatePem}
                onChange={(e) => setForm({ ...form, certificatePem: e.target.value })}
              />
            </Field>
          )}
          <fieldset>
            <legend className="mb-1 font-medium">Scopes</legend>
            <div className="grid gap-1 sm:grid-cols-2">
              {API_SCOPES.map((s) => (
                <label key={s.value} className="flex gap-2">
                  <input
                    type="checkbox"
                    checked={form.scopes.includes(s.value)}
                    onChange={() =>
                      setForm({
                        ...form,
                        scopes: form.scopes.includes(s.value)
                          ? form.scopes.filter((v) => v !== s.value)
                          : [...form.scopes, s.value],
                      })
                    }
                  />
                  <span>
                    {s.label} <span className="text-xs text-muted">— {s.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <Field label="IP allowlist" hint="One IP or CIDR range per line; blank allows any address.">
            <textarea
              rows={3}
              spellCheck={false}
              className={`${inputClass} font-mono text-xs`}
              placeholder="203.0.113.0/24"
              value={form.cidrs}
              onChange={(e) => setForm({ ...form, cidrs: e.target.value })}
            />
          </Field>
          <Field label="Expires on" hint="Optional; the client stops working at the end of this day (UTC).">
            <input
              type="date"
              className={`${inputClass} max-w-48`}
              value={form.expiresOn}
              onChange={(e) => setForm({ ...form, expiresOn: e.target.value })}
            />
          </Field>
          {formError && (
            <p role="alert" className="text-sm text-danger-ink">
              {formError}
            </p>
          )}
        </form>
      </Modal>

      <Modal
        open={Boolean(revealed)}
        onClose={() => setRevealed(null)}
        title={revealed?.title || 'Secret'}
        size="lg"
        closeOnBackdrop={false}
        footer={
          <Button type="button" onClick={() => setRevealed(null)}>
            I have stored it
          </Button>
        }
      >
        {revealed && (
          <>
            <OneTimeSecret
              items={[
                { label: 'Client ID', value: String(revealed.clientId ?? '') },
                { label: 'Client secret', value: String(revealed.secret ?? '') },
              ]}
            />
            {revealed.note && <p className="mt-3 text-xs text-muted">{revealed.note}</p>}
          </>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(revoking)}
        title={`Revoke "${revoking?.name || ''}"`}
        tone="danger"
        confirmLabel="Revoke"
        busy={busy === revoking?.id}
        onConfirm={confirmRevoke}
        onCancel={() => setRevoking(null)}
        message="The client's tokens and keys stop working immediately. This cannot be undone."
      />
    </>
  );
}
