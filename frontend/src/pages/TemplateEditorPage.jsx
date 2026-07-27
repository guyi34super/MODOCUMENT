import { useCallback, useEffect, useRef, useState } from 'react';
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
import DataTable from '../components/DataTable';
import { Button, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function TemplateEditorPage() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();
  const toast = useToast();

  const [dataTypes, setDataTypes] = useState([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // New-template mode keeps draft rows locally; edit mode works on saved fields.
  const [draftFields, setDraftFields] = useState([{ fieldName: '', dataType: 'Text' }]);
  const [savedFields, setSavedFields] = useState([]);
  const [newField, setNewField] = useState({ fieldName: '', templateDataTypeId: 1 });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(!isNew);
  const fieldNameRef = useRef(null);

  // No setLoading(true) here: refetches after adding a field must update the
  // table in place without unmounting (and de-focusing) the add-field form.
  const loadExisting = useCallback(async () => {
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
    try {
      const payload = {
        name,
        description,
        fields: draftFields
          .filter((f) => f.fieldName.trim())
          .map((f) => ({ fieldName: f.fieldName.trim(), dataType: f.dataType, rules: [] })),
      };
      const created = await toast.promise(createWithFields(payload), {
        loading: 'Creating template…',
        success: 'Template created',
      });
      navigate(`/templates/${created.templateId}`);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const saveDetails = async () => {
    setBusy(true);
    try {
      await toast.promise(updateTemplate({ templateId: Number(id), name, description }), {
        loading: 'Saving template…',
        success: 'Template details saved',
      });
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const addField = async (e) => {
    e.preventDefault();
    if (!newField.fieldName.trim()) return;
    setBusy(true);
    try {
      await toast.promise(
        createField({
          templateId: Number(id),
          fieldName: newField.fieldName.trim(),
          templateDataTypeId: Number(newField.templateDataTypeId),
        }),
        { loading: 'Adding field…', success: `Field "${newField.fieldName.trim()}" added` }
      );
      setNewField({ fieldName: '', templateDataTypeId: 1 });
      await loadExisting();
      fieldNameRef.current?.focus();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const removeField = async (field) => {
    if (!window.confirm(`Delete field "${field.fieldName}"?`)) return;
    try {
      await toast.promise(deleteField(field.templateFieldsId), {
        loading: 'Deleting field…',
        success: `Field "${field.fieldName}" deleted`,
      });
      setSavedFields((fields) => fields.filter((f) => f.templateFieldsId !== field.templateFieldsId));
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Design Templates', to: '/templates' }, { label: isNew ? 'New' : name || '…' }]}
        title={isNew ? 'New Template' : name ? `Edit: ${name}` : 'Edit template'}
        subtitle={isNew ? 'Create a template together with its fields' : 'Update details and manage fields'}
        actions={
          !isNew && (
            <Button variant="secondary" onClick={() => navigate(`/validation?templateId=${id}`)}>
              Next: set field rules →
            </Button>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {loading ? (
        <Spinner />
      ) : isNew ? (
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
            <div className="flex flex-wrap items-center gap-3">
              <Button onClick={saveDetails} disabled={busy}>
                {busy ? 'Saving…' : 'Save details'}
              </Button>
              <span className="text-xs text-muted">
                Applies to name and description only — field changes below save immediately.
              </span>
            </div>
          </Card>

          <Card>
            <h2 className="mb-3 text-lg font-normal">Fields ({savedFields.length})</h2>
            <form onSubmit={addField} className="mb-4 flex gap-2">
              <input
                ref={fieldNameRef}
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
            <DataTable
              maxHeight="50vh"
              columns={[
                { key: 'fieldName', label: 'Field name' },
                {
                  key: 'dataType',
                  label: 'Data type',
                  render: (field) => typeName(field.templateDataTypeId),
                },
                {
                  key: 'actions',
                  label: '',
                  render: (field) => (
                    <div className="flex justify-end">
                      <Button size="sm" variant="danger" onClick={() => removeField(field)}>
                        Delete
                      </Button>
                    </div>
                  ),
                },
              ]}
              rows={savedFields}
              emptyMessage="No fields yet — add the first one above."
            />
          </Card>

          <div className="flex justify-end">
            <Button onClick={() => navigate(`/validation?templateId=${id}`)}>
              Next: set field rules →
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
