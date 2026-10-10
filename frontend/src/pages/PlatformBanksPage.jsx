import { useCallback, useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { listBanks, onboardBank } from '../api/platform';
import { isPendingChange } from '../api/changes';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import Modal from '../components/Modal';
import { Button, Callout, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import {
  BankStatusBadge,
  TemporaryPasswordInput,
  formatDateTime,
  formatPercent,
} from './PlatformCommon';

/** The bank roles a first user can hold (never a platform role). */
const BANK_ROLES = ['ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY'];
const MIN_USERS = 2;
const MAX_USERS = 20;

const blankUser = (role) => ({ email: '', displayName: '', role, temporaryPassword: '' });
const blankForm = () => ({
  code: '',
  name: '',
  // Two administrator rows to start: each can be the other's checker.
  users: [blankUser('ADMIN'), blankUser('ADMIN')],
});

/**
 * Client-side mirror of the server's onboarding rule (the server decides):
 * at least two users, at least one ADMIN, and two who can approve.
 */
function onboardingProblem(users) {
  if (users.length < MIN_USERS) return 'Add at least two first users.';
  if (!users.some((u) => u.role === 'ADMIN')) return 'At least one first user must be an ADMIN.';
  const approvers = users.filter((u) => u.role === 'ADMIN' || u.role === 'APPROVER').length;
  if (approvers < 2) {
    return (
      'At least two first users must be ADMIN or APPROVER, so the bank can approve its ' +
      'own changes.'
    );
  }
  return '';
}

/**
 * The platform's bank registry (ws21, `/platform/banks`): every bank with
 * counts-only figures, and onboarding. Onboarding is maker/checker
 * (BANK_CREATE): this page submits it and says "Sent for approval"; a second
 * platform administrator approves it under Approvals, and only then do the
 * bank and its first users exist.
 */
export default function PlatformBanksPage() {
  const toast = useToast();
  const formId = useId();
  const [banks, setBanks] = useState(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(blankForm);
  const [problem, setProblem] = useState('');
  const [saving, setSaving] = useState(false);
  const [submitted, setSubmitted] = useState(null);

  const load = useCallback(() => {
    listBanks()
      .then(setBanks)
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openOnboarding = () => {
    setForm(blankForm());
    setProblem('');
    setOpen(true);
  };

  const close = () => {
    if (!saving) setOpen(false);
  };

  const setUser = (index, patch) =>
    setForm((current) => ({
      ...current,
      users: current.users.map((u, i) => (i === index ? { ...u, ...patch } : u)),
    }));

  const addUser = () =>
    setForm((current) => ({ ...current, users: [...current.users, blankUser('READ_ONLY')] }));

  const removeUser = (index) =>
    setForm((current) => ({ ...current, users: current.users.filter((_, i) => i !== index) }));

  const submit = async (event) => {
    event.preventDefault();
    const issue = onboardingProblem(form.users);
    setProblem(issue);
    if (issue) return;
    setSaving(true);
    try {
      const result = await toast.promise(onboardBank(form), {
        loading: 'Submitting the onboarding…',
        success: (change) =>
          isPendingChange(change)
            ? `Sent for approval: onboarding ${form.code.toUpperCase()} (change #${change.pendingChangeId})`
            : `Bank ${form.code.toUpperCase()} onboarded`,
      });
      setSubmitted({ code: form.code.toUpperCase(), change: result });
      setOpen(false);
      load();
    } catch {
      // The error toast is already shown; the dialog stays open to fix the input.
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Platform', to: '/platform' }, { label: 'Banks' }]}
        title="Banks"
        subtitle="Onboard, suspend and reactivate customer banks."
        actions={<Button onClick={openOnboarding}>Onboard bank</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {submitted && (
        <div className="mb-4">
          <Callout variant="success" title="Sent for approval">
            Onboarding <strong>{submitted.code}</strong> is change #
            {submitted.change?.pendingChangeId}. A second platform administrator must approve it
            under <Link to="/admin/changes" className="underline">Approvals</Link>; the bank and its
            first users are created on approval. Hand each temporary password to its user over a
            separate channel.
          </Callout>
        </div>
      )}

      {!banks && !error && <Spinner />}
      {banks && (
        <div className="overflow-x-auto rounded-lg border border-line">
          <table aria-label="Banks" className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="bg-sidebar text-left">
                {['Code', 'Name', 'Kind', 'Status', 'Users', 'MFA enrolled', 'Last sign-in'].map(
                  (label) => (
                    <th
                      key={label}
                      className="border-b border-line px-3 py-2 font-medium text-muted"
                    >
                      {label}
                    </th>
                  )
                )}
              </tr>
            </thead>
            <tbody>
              {banks.map((bank) => (
                <tr key={bank.bankId} className="hover:bg-sidebar/60">
                  <td className="border-b border-line px-3 py-2 font-mono">
                    <Link
                      to={`/platform/banks/${bank.bankId}`}
                      className="text-accent hover:underline"
                    >
                      {bank.code}
                    </Link>
                  </td>
                  <td className="border-b border-line px-3 py-2">{bank.name}</td>
                  <td className="border-b border-line px-3 py-2">
                    {bank.kind === 'PLATFORM' ? 'Platform' : 'Customer'}
                  </td>
                  <td className="border-b border-line px-3 py-2">
                    <BankStatusBadge status={bank.status} />
                  </td>
                  <td className="border-b border-line px-3 py-2">{bank.users}</td>
                  <td className="border-b border-line px-3 py-2">
                    {formatPercent(bank.mfaEnrolledPct)}
                  </td>
                  <td className="border-b border-line px-3 py-2">
                    {formatDateTime(bank.lastActivityAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={open}
        onClose={close}
        title="Onboard bank"
        description="A second platform administrator must approve the onboarding."
        size="lg"
        closeOnBackdrop={false}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={close} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" form={formId} disabled={saving}>
              Send for approval
            </Button>
          </>
        }
      >
        <form id={formId} onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-[10rem_1fr]">
            <Field label="Code" hint="2–40 letters, digits, - or _">
              <input
                className={`${inputClass} font-mono uppercase`}
                required
                maxLength={40}
                value={form.code}
                onChange={(e) => setForm({ ...form, code: e.target.value })}
              />
            </Field>
            <Field label="Name">
              <input
                className={inputClass}
                required
                maxLength={200}
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </Field>
          </div>

          <fieldset>
            <legend className="mb-1 text-sm font-medium text-ink">First users</legend>
            <p className="mb-2 text-xs text-muted">
              At least two, with at least one ADMIN and two who can approve (ADMIN or APPROVER).
              Everyone must change the temporary password at first sign-in; administrators also
              enrol two-factor authentication.
            </p>
            <ul className="space-y-3">
              {form.users.map((user, index) => {
                const n = index + 1;
                return (
                  <li
                    key={index}
                    aria-label={`First user ${n}`}
                    className="rounded border border-line p-3"
                  >
                    <div className="grid gap-2 sm:grid-cols-2">
                      <input
                        aria-label={`Email of user ${n}`}
                        type="email"
                        required
                        maxLength={256}
                        placeholder="Email"
                        className={inputClass}
                        value={user.email}
                        onChange={(e) => setUser(index, { email: e.target.value })}
                      />
                      <input
                        aria-label={`Display name of user ${n}`}
                        maxLength={200}
                        placeholder="Display name"
                        className={inputClass}
                        value={user.displayName}
                        onChange={(e) => setUser(index, { displayName: e.target.value })}
                      />
                      <select
                        aria-label={`Role of user ${n}`}
                        className={inputClass}
                        value={user.role}
                        onChange={(e) => setUser(index, { role: e.target.value })}
                      >
                        {BANK_ROLES.map((role) => (
                          <option key={role} value={role}>
                            {role}
                          </option>
                        ))}
                      </select>
                      <TemporaryPasswordInput
                        label={`Temporary password of user ${n}`}
                        value={user.temporaryPassword}
                        onChange={(value) => setUser(index, { temporaryPassword: value })}
                      />
                    </div>
                    {form.users.length > MIN_USERS && (
                      <div className="mt-2 text-right">
                        <Button
                          type="button"
                          size="sm"
                          variant="danger"
                          onClick={() => removeUser(index)}
                        >
                          Remove user {n}
                        </Button>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
            {form.users.length < MAX_USERS && (
              <Button type="button" size="sm" variant="secondary" className="mt-2" onClick={addUser}>
                Add user
              </Button>
            )}
          </fieldset>
          {problem && (
            <p role="alert" className="text-sm text-danger-ink">
              {problem}
            </p>
          )}
        </form>
      </Modal>
    </div>
  );
}
