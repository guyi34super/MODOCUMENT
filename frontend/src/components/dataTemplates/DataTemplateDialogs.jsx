import { useEffect, useId, useState } from 'react';
import Modal from '../Modal';
import { Button, Field, inputClass } from '../ui';
import { createDataTemplate } from '../../api/dataTemplates';

const NAME_MAX = 200;
const DESCRIPTION_MAX = 1000;

/** "New data template": a name and an optional description → `onCreated(dto)`. */
export function NewDataTemplateDialog({ open, onClose, onCreated }) {
  const formId = useId();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return;
    setName('');
    setDescription('');
    setProblem('');
  }, [open]);

  const submit = async (event) => {
    event.preventDefault();
    if (!name.trim()) {
      setProblem('Give the data template a name.');
      return;
    }
    setBusy(true);
    setProblem('');
    try {
      onCreated(await createDataTemplate({ name: name.trim(), description: description.trim() }));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New data template"
      description="A data template lists the fields a document collects and the rules each must pass."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form={formId} disabled={busy}>
            {busy ? 'Creating…' : 'Create'}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="space-y-3" noValidate>
        <Field label="Name">
          <input className={inputClass} value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Description" hint="Optional">
          <textarea
            className={inputClass}
            rows={2}
            value={description}
            maxLength={DESCRIPTION_MAX}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>
        {problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}
      </form>
    </Modal>
  );
}
