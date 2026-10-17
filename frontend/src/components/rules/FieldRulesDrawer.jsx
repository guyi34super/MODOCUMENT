import { useEffect, useState } from 'react';
import Modal from '../Modal';
import { Button } from '../ui';
import RuleDialog from './RuleDialog';
import RuleTable from './RuleTable';
import { DEFAULT_RULE_TYPES } from './ruleText';

let counter = 0;
const withDraftId = (rule) => {
  counter += 1;
  return rule._id ? rule : { ...rule, _id: rule.id != null ? `r${rule.id}` : `d${counter}` };
};

/**
 * Edit the rules of one field without a blueprint behind it (a data
 * template's field): `field` { key, label, kind }, `fields` (for
 * REQUIRED_IF / COMPARE), `rules` (this field's rules, any shape the
 * RuleTable reads), `ruleTypes` (the server's, else the defaults) and
 * `onSave(rules)` with the field's new list when Done is pressed.
 * `keyName` is the key the rule inputs carry ('fieldName' for a data template).
 */
export default function FieldRulesDrawer({ open, field, fields, rules, ruleTypes, onSave, onClose, keyName = 'fieldName' }) {
  const [draft, setDraft] = useState([]);
  const [editing, setEditing] = useState(null);

  useEffect(() => {
    if (open) setDraft((rules || []).map(withDraftId));
    // Start from the given rules each time the drawer opens.
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!field) return null;
  const name = field.label || field.key;
  const nameOf = (key) => (fields || []).find((f) => f.key === key)?.label || key;
  const types = ruleTypes?.length ? ruleTypes : DEFAULT_RULE_TYPES;

  const saveRule = (input) => {
    const rule = editing?.rule;
    setDraft((list) =>
      rule ? list.map((r) => (r._id === rule._id ? { ...r, ...input } : r)) : [...list, withDraftId({ ...input, origin: 'MANUAL' })]
    );
    setEditing(null);
  };

  return (
    <>
      <Modal
        open={open && !editing}
        onClose={onClose}
        size="lg"
        title={`Rules — ${name}`}
        description="Errors stop a document from being saved as final; warnings are shown but allow it."
        footer={
          <>
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={() => onSave(draft.map(({ _id, ...rest }) => rest))}>Done</Button>
          </>
        }
      >
        <div className="space-y-3" data-testid="rules-drawer">
          <RuleTable
            name={name}
            rules={draft}
            nameOf={nameOf}
            onEdit={(rule) => setEditing({ rule })}
            onDelete={(rule) => setDraft((list) => list.filter((r) => r._id !== rule._id))}
          />
          <Button size="sm" variant="secondary" onClick={() => setEditing({ rule: null })}>
            Add rule<span className="sr-only"> to {name}</span>
          </Button>
        </div>
      </Modal>
      <RuleDialog
        open={Boolean(open && editing)}
        field={field}
        rule={editing?.rule}
        ruleTypes={types}
        fields={fields}
        keyName={keyName}
        onSave={saveRule}
        onCancel={() => setEditing(null)}
      />
    </>
  );
}
