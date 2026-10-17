import { useId, useState } from 'react';
import { Button, Callout, inputClass } from '../ui';

const PHOTO = new Set(['IMAGE', 'SIGNATURE']);

/**
 * "Test with sample values": an input per field (`fields`: { key, label,
 * kind }), then `onTest(values)` → a ValidationResultDto whose errors and
 * warnings are shown under each field (matched on `regionKey`, which is
 * the field's key — a region key or a data field name). Nothing is stored.
 * `dirty` says the unsaved rules are the ones tested.
 */
export default function RulesTestPanel({ fields, onTest, dirty = false, photoAsCheckbox = false }) {
  const [values, setValues] = useState({});
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');
  const ids = useId();

  const run = async (event) => {
    event.preventDefault();
    setBusy(true);
    setProblem('');
    try {
      setResult(await onTest(values));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  const keyOf = (item) => item.regionKey ?? item.fieldName;
  const issuesFor = (key) => ({
    errors: (result?.errors || []).filter((e) => keyOf(e) === key),
    warnings: (result?.warnings || []).filter((w) => keyOf(w) === key),
  });
  const errorCount = result?.errors?.length || 0;
  const warningCount = result?.warnings?.length || 0;

  return (
    <form onSubmit={run} className="space-y-3" data-testid="rules-test">
      <p className="text-xs text-muted">
        Type sample values and run the rules on them. Nothing is stored.
        {dirty ? ' Your unsaved rules are tested.' : ''}
      </p>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        {fields
          // A photo field has no sample value, unless the server reads a non-blank value as "a photo is placed".
          .filter((f) => photoAsCheckbox || !PHOTO.has(f.kind))
          .map((f) => {
            const { errors, warnings } = issuesFor(f.key);
            const messageId = `${ids}-${f.key}`;
            const described = errors.length || warnings.length ? messageId : undefined;
            const name = `Sample value for ${f.label || f.key}`;
            return (
              <div key={f.key}>
                {PHOTO.has(f.kind) ? (
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={Boolean(values[f.key])}
                      aria-describedby={described}
                      onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.checked ? 'photo' : '' }))}
                    />
                    {f.label || f.key}: a photo is placed
                  </label>
                ) : f.kind === 'CHECKBOX' ? (
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={values[f.key] === 'true'}
                      aria-describedby={described}
                      onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.checked ? 'true' : '' }))}
                    />
                    {name}
                  </label>
                ) : (
                  <label className="block text-sm">
                    <span className="mb-0.5 block">{name}</span>
                    <input
                      className={inputClass}
                      type={f.kind === 'DATE' ? 'date' : 'text'}
                      value={values[f.key] || ''}
                      aria-invalid={errors.length ? true : undefined}
                      aria-describedby={described}
                      onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                    />
                  </label>
                )}
                {described && (
                  <ul id={messageId} className="mt-0.5 text-xs">
                    {errors.map((e, i) => (
                      <li key={`e${i}`} className="text-danger-ink">
                        {e.message}
                      </li>
                    ))}
                    {warnings.map((w, i) => (
                      <li key={`w${i}`} className="text-amber-800">
                        Warning: {w.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? 'Testing…' : 'Run the rules'}
        </Button>
        {result && (
          <span role="status" className={`text-sm ${errorCount ? 'text-danger-ink' : 'text-success-ink'}`}>
            {errorCount === 0 && warningCount === 0
              ? 'No errors or warnings'
              : `${errorCount} error${errorCount === 1 ? '' : 's'}, ${warningCount} warning${warningCount === 1 ? '' : 's'}`}
          </span>
        )}
      </div>
      {result?.setupWarnings?.length > 0 && (
        <Callout variant="info" title="Rules that were skipped">
          <ul className="list-inside list-disc">
            {result.setupWarnings.map((w, i) => (
              <li key={i}>{w.message}</li>
            ))}
          </ul>
        </Callout>
      )}
      {problem && (
        <p role="alert" className="text-sm text-danger-ink">
          {problem}
        </p>
      )}
    </form>
  );
}
