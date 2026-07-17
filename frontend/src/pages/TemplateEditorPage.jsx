import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getTemplateDataTypes } from '../api/cache';
import {
  createField,
  createWithFields,
  deleteField,
  getAllFields,
  getTemplate,
  updateTemplate,
} from '../api/templates';
import PageHeader from '../components/PageHeader';
import { Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';

export default function TemplateEditorPage() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();

  const [dataTypes, setDataTypes] = useState([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // New-template mode keeps draft rows locally; edit mode works on saved fields.
  const [draftFields, setDraftFields] = useState([{ fieldName: '', dataType: 'Text' }]);
  const [savedFields, setSavedFields] = useState([]);
  const [newField, setNewField] = useState({ fieldName: '', templateDataTypeId: 1 });
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(!isNew);

  const loadExisting = useCallback(async () => {
    setLoading(true);
    try {
      const [template, allFields] = await Promise.all([getTemplate(id), getAllFields()]);
      setName(template.name || '');
      setDescription(template.description || '');
      setSavedFields(allFields.filter((f) => f.templateId === Number(id)));
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    getTemplateDataTypes().then(setDataTypes).catch((err) => setError(err.message));
    if (!isNew) loadExisting();
  }, [isNew, loadExisting]);

  const typeName = (typeId) =>
    dataTypes.find((t) => t.templateDataTypeId === typeId)?.dataType ?? typeId;

  const submitNew = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const payload = {
        name,
        description,
        fields: draftFields
          .filter((f) => f.fieldName.trim())
          .map((f) => ({ fieldName: f.fieldName.trim(), dataType: f.dataType, rules: [] })),
      };
      const created = await createWithFields(payload);
      navigate(`/templates/${created.templateId}`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const saveDetails = async () => {
    setBusy(true);
    setError('');
    setSaved(false);
    try {
      await updateTemplate({ templateId: Number(id), name, description });
      setSaved(true);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const addField = async (e) => {
    e.preventDefault();
    if (!newField.fieldName.trim()) return;
    setBusy(true);
    setError('');
    try {
      await createField({
        templateId: Number(id),
        fieldName: newField.fieldName.trim(),
        templateDataTypeId: Number(newField.templateDataTypeId),
      });
      setNewField({ fieldName: '', templateDataTypeId: 1 });
      await loadExisting();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const removeField = async (field) => {
    if (!window.confirm(`Delete field "${field.fieldName}"?`)) return;
    setError('');
    try {
      await deleteField(field.templateFieldsId);
      setSavedFields((fields) => fields.filter((f) => f.templateFieldsId !== field.templateFieldsId));
    } catch (err) {
      setError(err.message);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'Templates', to: '/templates' }, { label: isNew ? 'New' : name }]}
        title={isNew ? 'New Template' : `Edit: ${name}`}
        subtitle={isNew ? 'Create a template together with its fields' : 'Update details and manage fields'}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      {saved && (
        <div className="mb-4">
          <Callout variant="success" title="Saved">Template details updated.</Callout>
        </div>
      )}

      {isNew ? (
        <form onSubmit={submitNew} className="space-y-5">
          <Card className="space-y-4">
            <Field label="Name">
              <input required className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label="Description">
              <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
          </Card>

          <Card>
            <h2 className="mb-3 text-lg font-normal">Fields</h2>
            <div className="space-y-2">
              {draftFields.map((field, i) => (
                <div key={i} className="flex gap-2">
                  <input
                    placeholder="Field name"
                    className={inputClass}
                    value={field.fieldName}
                    onChange={(e) =>
                      setDraftFields((fields) =>
                        fields.map((f, j) => (j === i ? { ...f, fieldName: e.target.value } : f))
                      )
                    }
                  />
                  <select
                    className={`${inputClass} w-40`}
                    value={field.dataType}
                    onChange={(e) =>
                      setDraftFields((fields) =>
                        fields.map((f, j) => (j === i ? { ...f, dataType: e.target.value } : f))
                      )
                    }
                  >
                    {dataTypes.map((t) => (
                      <option key={t.templateDataTypeId} value={t.dataType}>
                        {t.dataType}
                      </option>
                    ))}
                  </select>
                  <Button
                    type="button"
                    variant="danger"
                    onClick={() => setDraftFields((fields) => fields.filter((_, j) => j !== i))}
                    disabled={draftFields.length === 1}
                  >
                    ×
                  </Button>
                </div>
              ))}
            </div>
            <Button
              type="button"
              variant="secondary"
              className="mt-3"
              onClick={() => setDraftFields((fields) => [...fields, { fieldName: '', dataType: 'Text' }])}
            >
              + Add field
            </Button>
          </Card>

          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>
              {busy ? 'Creating…' : 'Create template'}
            </Button>
            <Button type="button" variant="secondary" onClick={() => navigate('/templates')}>
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <div className="space-y-5">
          <Card className="space-y-4">
            <Field label="Name">
              <input required className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label="Description">
              <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
            <Button onClick={saveDetails} disabled={busy}>
              {busy ? 'Saving…' : 'Save details'}
            </Button>
          </Card>

          <Card>
            <h2 className="mb-3 text-lg font-normal">Fields ({savedFields.length})</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-sidebar text-left">
                    <th className="border-b border-line px-3 py-2 font-medium text-muted">Field name</th>
                    <th className="border-b border-line px-3 py-2 font-medium text-muted">Data type</th>
                    <th className="border-b border-line px-3 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {savedFields.map((field) => (
                    <tr key={field.templateFieldsId} className="hover:bg-sidebar/60">
                      <td className="border-b border-line px-3 py-2">{field.fieldName}</td>
                      <td className="border-b border-line px-3 py-2">{typeName(field.templateDataTypeId)}</td>
                      <td className="border-b border-line px-3 py-2 text-right">
                        <Button variant="danger" onClick={() => removeField(field)}>
                          Delete
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <form onSubmit={addField} className="mt-4 flex gap-2">
              <input
                placeholder="New field name"
                className={inputClass}
                value={newField.fieldName}
                onChange={(e) => setNewField((f) => ({ ...f, fieldName: e.target.value }))}
              />
              <select
                className={`${inputClass} w-40`}
                value={newField.templateDataTypeId}
                onChange={(e) => setNewField((f) => ({ ...f, templateDataTypeId: e.target.value }))}
              >
                {dataTypes.map((t) => (
                  <option key={t.templateDataTypeId} value={t.templateDataTypeId}>
                    {t.dataType}
                  </option>
                ))}
              </select>
              <Button type="submit" disabled={busy}>
                Add
              </Button>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}
