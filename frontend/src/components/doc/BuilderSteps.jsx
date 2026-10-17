import { Button, Callout } from '../ui';

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

/**
 * The builder's step 3, "Save & validate": Save stores the values and runs
 * the checks (the data template's rules and the PDF's own); the result is
 * the errors with "Go to field" (back to step 2), or "Validated" and the way
 * on to Download.
 */
export function SaveStep({ validation, checkedNow, validating, disabled, gate, issues, onSave, onGoTo, onNext }) {
  const errors = issues.filter((i) => i.level === 'error');
  const warnings = issues.filter((i) => i.level === 'warning');
  const showResult = checkedNow && validation?.checked;
  return (
    <section aria-labelledby="save-step-heading" className="space-y-3 rounded-lg border border-line bg-white p-4" data-testid="save-step">
      <h2 id="save-step-heading" className="text-base font-medium">
        Save &amp; validate
      </h2>
      <p className="text-sm text-muted">
        Save stores your values and checks them against the rules of the data template and of this PDF. Only a document
        that passes every check can be saved as final and downloaded.
      </p>
      <div>
        <Button onClick={onSave} disabled={validating || disabled}>
          {validating ? 'Saving…' : 'Save and validate'}
        </Button>
      </div>
      {!showResult ? (
        <p className="text-sm text-muted" data-testid="save-result">
          {validation?.checked ? 'Changed since the last check — save again to check it.' : 'Not checked yet.'}
        </p>
      ) : errors.length > 0 ? (
        <div data-testid="save-result" className="space-y-2">
          <Callout variant="error" title={`${plural(errors.length, 'error')} to fix`}>
            The document stays a draft until these are fixed and it is saved again.
          </Callout>
          <ul className="space-y-1 text-sm" aria-label="Errors to fix">
            {errors.map((issue) => (
              <li key={issue.id} className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{issue.label}:</span>
                <span className="flex-1">{issue.message}</span>
                <Button size="sm" variant="secondary" aria-label={`Go to field: ${issue.label}`} onClick={() => onGoTo(issue.regionKey)}>
                  Go to field
                </Button>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div data-testid="save-result" className="space-y-2">
          <Callout variant="success" title="Validated">
            {warnings.length ? `It passed, with ${plural(warnings.length, 'warning')}.` : 'It passed every check.'}
          </Callout>
          {warnings.length > 0 && (
            <ul className="space-y-1 text-sm text-amber-800" aria-label="Warnings">
              {warnings.map((issue) => (
                <li key={issue.id}>
                  {issue.label}: {issue.message}
                </li>
              ))}
            </ul>
          )}
          {!gate.locked && (
            <Button onClick={onNext}>Continue to Download</Button>
          )}
        </div>
      )}
    </section>
  );
}

/** The builder's step 4, "Download": Generate, Download PDF and (once generated) Submit for release. */
export function DownloadStep({ status, docLocked, generating, submitting, onGenerate, onDownload, onSubmit, onPreview }) {
  return (
    <section aria-labelledby="download-step-heading" className="space-y-3 rounded-lg border border-line bg-white p-4" data-testid="download-step">
      <h2 id="download-step-heading" className="text-base font-medium">
        Download
      </h2>
      <p className="text-sm text-muted">
        The document passed its checks. Generate the final PDF, download it, or hand it to an approver for release.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={onPreview}>
          Preview
        </Button>
        {!docLocked && (
          <Button variant="secondary" onClick={onGenerate} disabled={generating}>
            {generating ? 'Generating…' : 'Generate'}
          </Button>
        )}
        <Button onClick={onDownload}>Download PDF</Button>
        {status === 'GENERATED' && (
          <Button variant="secondary" onClick={onSubmit} disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit for release'}
          </Button>
        )}
      </div>
      {status === 'SUBMITTED' && <p className="text-sm text-muted">Submitted — waiting for an approver to release it.</p>}
    </section>
  );
}
