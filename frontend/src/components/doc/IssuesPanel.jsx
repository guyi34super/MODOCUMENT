import { useState } from 'react';
import { Button } from '../ui';

/**
 * The validation issues of a document after Save: errors first, then
 * warnings, each with "Go to field"; "Next issue" walks through them.
 * `issues`: [{ id, level: 'error' | 'warning', regionKey, label, message }].
 */
export default function IssuesPanel({ issues, onGoTo }) {
  const [next, setNext] = useState(0);
  const errors = issues.filter((i) => i.level === 'error').length;
  const warnings = issues.length - errors;
  if (issues.length === 0) return null;

  const goNext = () => {
    const issue = issues[next % issues.length];
    setNext((n) => (n + 1) % issues.length);
    onGoTo(issue.regionKey);
  };

  return (
    <section aria-labelledby="issues-heading" className="rounded-lg border border-danger-ink/40 bg-white p-3" data-testid="issues-panel">
      <div className="mb-2 flex items-center gap-2">
        <h2 id="issues-heading" className="flex-1 text-sm font-medium">
          Issues ({errors} error{errors === 1 ? '' : 's'}
          {warnings ? `, ${warnings} warning${warnings === 1 ? '' : 's'}` : ''})
        </h2>
        <Button size="sm" variant="secondary" onClick={goNext}>
          Next issue
        </Button>
      </div>
      <ul className="max-h-60 space-y-1 overflow-y-auto text-xs">
        {issues.map((issue) => (
          <li key={issue.id} className="flex items-start gap-2">
            <span
              className={`mt-0.5 shrink-0 rounded px-1 text-[10px] font-semibold uppercase ${
                issue.level === 'error' ? 'bg-danger-bg text-danger-ink' : 'bg-amber-100 text-amber-800'
              }`}
            >
              {issue.level === 'error' ? 'Error' : 'Warning'}
            </span>
            <span className="flex-1">{issue.message}</span>
            {issue.regionKey && (
              <button
                type="button"
                className="shrink-0 text-accent hover:underline"
                aria-label={`Go to field: ${issue.label}`}
                onClick={() => onGoTo(issue.regionKey)}
              >
                Go to field
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
