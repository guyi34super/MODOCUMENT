import { Button, inputClass } from '../components/ui';

// Shared bits of the platform console pages (ws21).

/** No 0/O, 1/l/I lookalikes: the password is read out or typed by a person. */
const PASSWORD_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

/** Four hyphenated groups of four random characters (~90 bits, 19 chars). */
export function generateTemporaryPassword() {
  const random = new Uint32Array(16);
  crypto.getRandomValues(random);
  let password = '';
  random.forEach((value, i) => {
    if (i > 0 && i % 4 === 0) password += '-';
    password += PASSWORD_ALPHABET[value % PASSWORD_ALPHABET.length];
  });
  return password;
}

export function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** "62%" or "—" when the bank has no users yet. */
export function formatPercent(value) {
  return value === null || value === undefined ? '—' : `${value}%`;
}

/** ACTIVE / SUSPENDED pill. */
export function BankStatusBadge({ status }) {
  const suspended = status === 'SUSPENDED';
  return (
    <span
      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${
        suspended ? 'bg-danger-bg text-danger-ink' : 'bg-success-bg text-success-ink'
      }`}
    >
      {suspended ? 'Suspended' : 'Active'}
    </span>
  );
}

/**
 * A temporary-password field with a Generate button. Shown in clear text on
 * purpose: the platform admin hands it to the person over a separate channel;
 * the server keeps only its hash and never shows it again.
 */
export function TemporaryPasswordInput({ id, label, value, onChange }) {
  return (
    <div className="flex gap-2">
      <input
        id={id}
        aria-label={label}
        type="text"
        required
        minLength={12}
        autoComplete="off"
        spellCheck={false}
        className={`${inputClass} font-mono`}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <Button
        type="button"
        variant="secondary"
        aria-label={`Generate ${label.toLowerCase()}`}
        onClick={() => onChange(generateTemporaryPassword())}
      >
        Generate
      </Button>
    </div>
  );
}
