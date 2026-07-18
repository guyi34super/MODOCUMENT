/** Small shared UI primitives carrying the docs-site look. */

export function Button({ variant = 'primary', className = '', ...props }) {
  const base =
    'inline-flex items-center gap-1.5 rounded px-4 py-1.5 text-sm font-medium transition-colors ' +
    'disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer';
  const variants = {
    primary: 'bg-accent text-white hover:bg-accent-hover',
    secondary: 'border border-line text-accent bg-white hover:bg-pill/50',
    danger: 'border border-line text-danger-ink bg-white hover:bg-danger-bg',
  };
  return <button className={`${base} ${variants[variant]} ${className}`} {...props} />;
}

export function Card({ className = '', children }) {
  return <div className={`rounded-lg border border-line bg-white p-5 ${className}`}>{children}</div>;
}

export function Callout({ variant = 'success', title, children }) {
  const variants = {
    success: 'bg-success-bg border-l-success-ink text-success-ink',
    info: 'bg-pill border-l-accent text-ink',
    error: 'bg-danger-bg border-l-danger-ink text-danger-ink',
  };
  return (
    <div className={`rounded-md border-l-4 px-4 py-3 ${variants[variant]}`}>
      {title && <p className="mb-1 text-sm font-semibold">{title}</p>}
      <div className="text-sm">{children}</div>
    </div>
  );
}

export function Badge({ status }) {
  const colors = {
    COMPLETED: 'bg-success-bg text-success-ink',
    COMPLETED_WITH_ERRORS: 'bg-amber-100 text-amber-800',
    RUNNING: 'bg-pill text-accent',
    PENDING: 'bg-neutral-100 text-muted',
    PAUSED: 'bg-amber-100 text-amber-800',
    FAILED: 'bg-danger-bg text-danger-ink',
    CANCELLED: 'bg-neutral-200 text-muted',
    UPLOADED: 'bg-neutral-100 text-muted',
    PARSED: 'bg-pill text-accent',
    MAPPED: 'bg-pill text-accent',
    VALIDATED: 'bg-success-bg text-success-ink',
    EXPORTED: 'bg-success-bg text-success-ink',
  };
  return (
    <span
      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${
        colors[status] || 'bg-neutral-100 text-muted'
      }`}
    >
      {status || '—'}
    </span>
  );
}

export function Spinner({ label = 'Loading…' }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-muted">
      <span className="size-4 animate-spin rounded-full border-2 border-line border-t-accent" />
      {label}
    </div>
  );
}

export function ErrorBanner({ message, onDismiss }) {
  if (!message) return null;
  return (
    <div className="mb-4 flex items-start justify-between rounded-md bg-danger-bg px-4 py-3 text-sm text-danger-ink">
      <span>{message}</span>
      {onDismiss && (
        <button onClick={onDismiss} className="ml-4 cursor-pointer font-bold" aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  );
}

export function Field({ label, hint, children }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-ink">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-muted">{hint}</span>}
    </label>
  );
}

export const inputClass =
  'w-full rounded border border-line px-3 py-1.5 text-sm text-ink outline-none ' +
  'focus:border-accent focus:ring-2 focus:ring-pill';

export function StatCard({ label, value, hint }) {
  return (
    <Card className="flex-1 min-w-40">
      <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
      <p className="mt-1 text-3xl font-semibold text-ink">{value}</p>
      {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </Card>
  );
}
