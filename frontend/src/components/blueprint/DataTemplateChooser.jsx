import { useCallback, useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { listDataTemplates, setBlueprintDataTemplate } from '../../api/dataTemplates';
import { useToast } from '../../context/ToastContext';
import ConfirmDialog from '../ConfirmDialog';
import BuildFromFlowDialog from '../dataTemplates/BuildFromFlowDialog';
import { NewDataTemplateDialog } from '../dataTemplates/DataTemplateDialogs';
import { Button, Card, ErrorBanner, Spinner, inputClass } from '../ui';

/**
 * The Validation step's data template card (data-templates contract §3,
 * §6): choose one of the user's data templates, or create one / build one
 * from Document Flow templates right here; then its summary (fields, rules) with a
 * link to edit it. Choosing pre-fills the mapping on the server; changing
 * a choice that has mapped fields asks first. `onChanged(mappingDto)`.
 */
export default function DataTemplateChooser({ blueprintId, mapping, readOnly = false, onChanged, onNext }) {
  const toast = useToast();
  const selectId = useId();
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(null);
  const [newOpen, setNewOpen] = useState(false);
  const [copyOpen, setCopyOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      setTemplates((await listDataTemplates()) || []);
    } catch (err) {
      setTemplates([]);
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const currentId = mapping?.dataTemplateId ?? null;
  const current = (templates || []).find((t) => String(t.id) === String(currentId));

  const choose = async (dataTemplateId, { created = false } = {}) => {
    setBusy(true);
    setError('');
    try {
      const dto = await setBlueprintDataTemplate(blueprintId, dataTemplateId);
      if (created) await load();
      onChanged?.(dto);
      if (dataTemplateId == null || dataTemplateId === '') {
        toast.success('Data template removed from this PDF');
      } else {
        const mapped = dto?.mappedCount ?? 0;
        const total = dto?.mappableCount ?? 0;
        toast.success(`Data template chosen — ${mapped} of ${total} fields matched automatically`);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
      setPending(null);
    }
  };

  const request = (value) => {
    const next = value === '' ? null : Number(value);
    if (String(next) === String(currentId)) return;
    if (currentId != null && (mapping?.mappedCount ?? 0) > 0) setPending({ id: next });
    else choose(next);
  };

  return (
    <Card padding="compact" className="space-y-3">
      <div data-testid="data-template-chooser" className="space-y-3">
        <h2 className="text-base font-medium">Data template</h2>
        <p className="text-xs text-muted">
          The data template lists the fields this document collects and the rules they must pass. Its rules apply to
          every PDF field mapped to its fields; add rules only this PDF needs below.
        </p>
        <ErrorBanner message={error} onDismiss={() => setError('')} />
        {!templates ? (
          <Spinner label="Loading your data templates…" />
        ) : (
          <div className="flex flex-wrap items-end gap-2">
            <label htmlFor={selectId} className="block min-w-60 flex-1 text-sm">
              <span className="mb-1 block font-medium">Data template</span>
              <select
                id={selectId}
                className={inputClass}
                value={currentId ?? ''}
                disabled={readOnly || busy}
                onChange={(e) => request(e.target.value)}
              >
                <option value="">None chosen</option>
                {templates.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
                {currentId != null && !current && <option value={currentId}>{mapping?.dataTemplateName || `#${currentId}`}</option>}
              </select>
            </label>
            {!readOnly && (
              <>
                <Button variant="secondary" onClick={() => setNewOpen(true)} disabled={busy}>
                  Create new
                </Button>
                <Button variant="secondary" onClick={() => setCopyOpen(true)} disabled={busy}>
                  Build from Document Flow templates
                </Button>
              </>
            )}
          </div>
        )}
        {currentId != null && (
          <div className="flex flex-wrap items-center gap-3 rounded bg-pill/40 px-3 py-2 text-sm" data-testid="data-template-summary">
            <span className="font-medium">{mapping?.dataTemplateName || current?.name}</span>
            <span className="text-muted">
              {current ? `${current.fieldCount ?? 0} fields · ${current.ruleCount ?? 0} rules` : `${mapping?.fields?.length ?? 0} fields`}
              {mapping ? ` · ${mapping.mappedCount ?? 0} of ${mapping.mappableCount ?? 0} PDF fields mapped` : ''}
            </span>
            <Link to={`/creation/data-templates?template=${currentId}`} className="text-accent hover:underline">
              Edit the data template
            </Link>
            <span className="flex-1" />
            {onNext && (
              <Button size="sm" onClick={onNext}>
                Next: map fields
              </Button>
            )}
          </div>
        )}
      </div>

      <NewDataTemplateDialog
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onCreated={(dto) => {
          setNewOpen(false);
          choose(dto.id, { created: true });
        }}
      />
      <BuildFromFlowDialog
        open={copyOpen}
        onClose={() => setCopyOpen(false)}
        onSaved={(dto) => {
          setCopyOpen(false);
          choose(dto.id, { created: true });
        }}
      />
      <ConfirmDialog
        open={Boolean(pending)}
        title="Change the data template?"
        confirmLabel={pending?.id == null ? 'Remove it' : 'Change it'}
        busy={busy}
        onConfirm={() => choose(pending.id)}
        onCancel={() => setPending(null)}
      >
        {mapping?.mappedCount ?? 0} PDF fields are mapped to {mapping?.dataTemplateName}. Changing the data template
        replaces that mapping (and the rules that come with it).
      </ConfirmDialog>
    </Card>
  );
}
