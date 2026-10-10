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
import { isPendingChange } from '../api/changes';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

// PII classification options (ws12): '' means "classify automatically on
// first use" — the server then persists its inference, which shows up here.
const PII_KINDS = [
  { value: '', label: 'PII: auto' },
  { value: 'NIC', label: 'NIC' },
  { value: 'ACCOUNT_NUMBER', label: 'Account number' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone' },
  { value: 'NAME', label: 'Name' },
  { value: 'NONE', label: 'Not PII' },
];

const piiKindLabel = (value) =>
  PII_KINDS.find((k) => k.value === (value || ''))?.label ?? value;

export default function TemplateEditorPage() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();
  const toast = useToast();

  const [dataTypes, setDataTypes] = useState([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // New-template mode keeps draft rows locally; edit mode works on saved fields.
  const [draftFields, setDraftFields] = useState([{ fieldName: '', dataType: 'Text', piiKind: '' }]);
  const [savedFields, setSavedFields] = useState([]);
  const [newField, setNewField] = useState({ fieldName: '', templateDataTypeId: 1, piiKind: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(!isNew);
  const fieldNameRef = useRef(null);
  // What the server currently holds, so "Next" knows whether the name or
  // description on screen still needs saving.
  const savedDetails = useRef({ name: '', description: '' });

  // No setLoading(true) here: refetches after adding a field must update the
  // table in place without unmounting (and de-focusing) the add-field form.
  const loadExisting = useCallback(async () => {
    try {
      const [template, fields] = await Promise.all([getTemplate(id), getAllFields(id)]);
      setName(template.name || '');
      setDescription(template.description || '');
      savedDetails.current = {
        name: template.name || '',
        description: template.description || '',
      };
      setSavedFields(fields);
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [id]);

  const loadDataTypes = useCallback(() => {
    getTemplateDataTypes()
      .then(setDataTypes)
      .catch((err) => setError(`Field data types could not be loaded: ${err.message}`));
  }, []);

  useEffect(() => {
    loadDataTypes();
    // setLoading(true) here (not in loadExisting): opening a different template
    // reuses this component, and without it the previous template's fields —
    // or an empty "No fields yet" — flash before the new ones arrive. Refetches
    // after adding a field still update in place.
    if (!isNew) {
      setLoading(true);
      loadExisting();
    }
  }, [isNew, loadExisting, loadDataTypes]);

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
          .map((f) => ({
            fieldName: f.fieldName.trim(),
            dataType: f.dataType,
            rules: [],
            piiKind: f.piiKind || null,
          })),
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

  /**
   * Leaving for the rules page used to drop an edited name or description on
   * the floor, so the edit is committed first.
   */
  const goToRules = async () => {
    const dirty =
      name !== savedDetails.current.name || description !== savedDetails.current.description;
    if (dirty) {
      const saved = await saveDetails();
      if (!saved) return;
    }
    navigate(`/validation?templateId=${id}`);
  };

  /** Returns true when the details were persisted. */
  const saveDetails = async () => {
    setBusy(true);
    try {
      await toast.promise(updateTemplate({ templateId: Number(id), name, description }), {
        loading: 'Saving template…',
        // ws10: in production the edit waits for a second user's approval.
        success: (result) =>
          isPendingChange(result)
            ? `Template change submitted for approval (change #${result.pendingChangeId})`
            : 'Template details saved',
      });
      savedDetails.current = { name, description };
      return true;
    } catch {
      // The error toast is already shown.
      return false;
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
          piiKind: newField.piiKind || null,
        }),
        { loading: 'Adding field…', success: `Field "${newField.fieldName.trim()}" added` }
      );
      setNewField({ fieldName: '', templateDataTypeId: 1, piiKind: '' });
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
            <Button variant="secondary" onClick={goToRules}>
              Next: set field rules →
            </Button>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {dataTypes.length === 0 && !loading && (
        <div className="mb-4 flex items-start justify-between gap-3 rounded-md bg-danger-bg px-4 py-3 text-sm text-danger-ink">
          <span>
            Field data types are unavailable, so fields cannot be created right now.
          </span>
          <Button variant="secondary" size="sm" onClick={loadDataTypes}>
            Retry
          </Button>
        </div>
      )}

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
                  <select
                    aria-label="PII kind"
                    className={`${inputClass} w-44`}
                    value={field.piiKind || ''}
                    onChange={(e) =>
                      setDraftFields((fields) =>
                        fields.map((f, j) => (j === i ? { ...f, piiKind: e.target.value } : f))
                      )
                    }
                  >
                    {PII_KINDS.map((k) => (
                      <option key={k.value} value={k.value}>
                        {k.label}
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
              onClick={() =>
                setDraftFields((fields) => [...fields, { fieldName: '', dataType: 'Text', piiKind: '' }])
              }
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
              <select
                aria-label="PII kind"
                className={`${inputClass} w-44`}
                value={newField.piiKind}
                onChange={(e) => setNewField((f) => ({ ...f, piiKind: e.target.value }))}
              >
                {PII_KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
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
                  key: 'piiKind',
                  label: 'PII',
                  // Server-persisted classification (explicit or auto); the
                  // masking matrix masks these kinds in exports/reports.
                  render: (field) => piiKindLabel(field.piiKind),
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
            <Button onClick={goToRules}>
              Next: set field rules →
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
