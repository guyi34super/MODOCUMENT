import { useId } from 'react';

/**
 * A row of step pills: the guided flows (the ingestion wizard, a PDF
 * template's Upload → Validation → Map fields → Documents, the document
 * builder's Choose data → … → Download).
 *
 * `steps`: [{ id, label, done, locked, reason, describedBy }]
 *   - `done` adds a ✓ (not on the current step);
 *   - `locked` disables the step; `reason` says why (title, and for
 *     `tabs` a visible line under the row unless `describedBy` names an
 *     element elsewhere on the page that already says it).
 * `current`: the id of the step on screen. `onSelect(id)`.
 *
 * Without `tabs` the pills are plain buttons (the ingestion wizard). With
 * `tabs` the row is a tablist: numbered pills, role="tab" with
 * aria-selected, arrow keys / Home / End move between open steps, and
 * `panelId(id)` names the tabpanel each step controls. A tab's accessible
 * name is its label alone; ✓ and the number are decoration.
 */
export default function Stepper({ steps, current, onSelect, tabs = false, label, idPrefix, panelId }) {
  const autoId = useId();
  const prefix = idPrefix || `stepper${autoId.replace(/:/g, '')}`;

  if (!tabs) {
    return (
      <ol className="flex flex-wrap items-center gap-1 text-sm">
        {steps.map((step, i) => {
          const isActive = step.id === current;
          const enabled = !step.locked;
          const isDone = Boolean(step.done);
          return (
            <li key={step.id} className="flex items-center gap-1">
              {i > 0 && <span className="text-muted">›</span>}
              <button
                type="button"
                disabled={!enabled}
                onClick={() => enabled && onSelect(step.id)}
                // A locked pill that says nothing is just a dead end.
                title={step.reason || undefined}
                className={`rounded-full px-3 py-1.5 font-medium ${
                  isActive
                    ? 'bg-accent text-white'
                    : isDone
                      ? 'bg-success-bg text-success-ink'
                      : enabled
                        ? 'bg-neutral-100 text-ink hover:bg-pill'
                        : 'bg-neutral-100 text-muted'
                } ${enabled ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
              >
                {isDone && !isActive ? '✓ ' : ''}
                {step.label}
              </button>
            </li>
          );
        })}
      </ol>
    );
  }

  const tabId = (id) => `${prefix}-tab-${id}`;
  const reasonId = (id) => `${prefix}-reason-${id}`;
  const open = steps.filter((s) => !s.locked);

  const move = (event, step) => {
    const index = open.findIndex((s) => s.id === step.id);
    let target = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      const delta = event.key === 'ArrowRight' ? 1 : -1;
      target = open[(index + delta + open.length) % open.length];
    } else if (event.key === 'Home') {
      target = open[0];
    } else if (event.key === 'End') {
      target = open[open.length - 1];
    }
    if (target) {
      event.preventDefault();
      onSelect(target.id);
      document.getElementById(tabId(target.id))?.focus();
    }
  };

  const lockedWithReason = steps.filter((s) => s.locked && s.reason && !s.describedBy);

  return (
    <div className="mb-4">
      <ol role="tablist" aria-label={label} className="flex flex-wrap items-center gap-1 text-sm">
        {steps.map((step, i) => {
          const isActive = step.id === current;
          const isDone = Boolean(step.done) && !isActive;
          const describedBy = step.locked && step.reason ? step.describedBy || reasonId(step.id) : undefined;
          return (
            <li key={step.id} role="presentation" className="flex items-center gap-1">
              {i > 0 && (
                <span aria-hidden="true" className="text-muted">
                  ›
                </span>
              )}
              <button
                type="button"
                role="tab"
                id={tabId(step.id)}
                aria-selected={isActive}
                aria-controls={isActive && panelId ? panelId(step.id) : undefined}
                aria-disabled={step.locked ? true : undefined}
                aria-describedby={describedBy}
                aria-label={step.label}
                tabIndex={isActive ? 0 : -1}
                title={step.locked ? step.reason || undefined : undefined}
                data-step={step.id}
                data-done={step.done ? 'true' : undefined}
                onClick={() => !step.locked && onSelect(step.id)}
                onKeyDown={(event) => move(event, step)}
                className={`flex items-center gap-1.5 rounded-full px-3 py-1.5 font-medium ${
                  isActive
                    ? 'bg-accent text-white'
                    : isDone
                      ? 'bg-success-bg text-success-ink hover:bg-pill'
                      : step.locked
                        ? 'cursor-not-allowed bg-neutral-100 text-muted opacity-70'
                        : 'cursor-pointer bg-neutral-100 text-ink hover:bg-pill'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`flex size-5 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ${
                    isActive ? 'bg-white text-accent' : isDone ? 'bg-success-ink text-white' : 'bg-white/80 text-muted'
                  }`}
                >
                  {isDone ? '✓' : i + 1}
                </span>
                <span aria-hidden="true">{step.label}</span>
              </button>
            </li>
          );
        })}
      </ol>
      {lockedWithReason.length > 0 && (
        <div className="mt-1 space-y-0.5 text-xs text-muted">
          {lockedWithReason.map((s) => (
            <p key={s.id} id={reasonId(s.id)}>
              {s.label}: {s.reason}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
