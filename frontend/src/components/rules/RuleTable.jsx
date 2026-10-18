import ScrollBox from '../ScrollBox';
import { Tag } from '../ui';
import { RULE_LABELS, originText, ruleSummary } from './ruleText';

/**
 * The rules of one field as a table: type, what it checks, severity, origin
 * and (unless `readOnly`) Edit / Delete. Shared by a PDF template's Field
 * rules tab and a data template's rules drawer. `name` is the field's
 * display name; `nameOf(key)` names another field (REQUIRED_IF / COMPARE);
 * `canEdit` false hides Edit but keeps Delete (an orphan rule).
 * The table scrolls inside its own box (ScrollBox, header pinned), so a
 * field with many rules or a narrow screen never stretches the page.
 */
export default function RuleTable({ name, rules, nameOf, readOnly = false, canEdit = true, onEdit, onDelete }) {
  if (rules.length === 0) return <p className="text-xs text-muted">No rules.</p>;
  return (
    <ScrollBox label={`Rules of ${name}`} maxHeight="max-h-80" stickyHeader testId="rule-table-scroll">
      <table className="w-full min-w-[32rem] text-left text-xs">
        <caption className="sr-only">Rules of {name}</caption>
        <thead className="text-muted">
          <tr>
            <th className="p-1 font-medium">Rule</th>
            <th className="p-1 font-medium">Checks</th>
            <th className="p-1 font-medium">Severity</th>
            <th className="p-1 font-medium">Origin</th>
            <th className="p-1 font-medium">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule, index) => {
            const typeName = RULE_LABELS[rule.ruleType] || rule.ruleType;
            return (
              <tr key={rule._id ?? rule.id ?? index} className="border-t border-line align-top">
                <td className="p-1 font-medium">{typeName}</td>
                <td className="p-1">
                  {ruleSummary(rule, nameOf)}
                  {rule.message && <span className="block italic text-muted">“{rule.message}”</span>}
                </td>
                <td className="p-1">
                  <Tag tone={rule.severity === 'WARNING' ? 'warning' : 'danger'}>
                    {rule.severity === 'WARNING' ? 'Warning' : 'Error'}
                  </Tag>
                </td>
                <td className="p-1">
                  <Tag tone={rule.origin === 'IMPORTED' || rule.origin === 'DATA_TEMPLATE' ? 'info' : 'neutral'}>
                    {originText(rule)}
                  </Tag>
                </td>
                <td className="whitespace-nowrap p-1 text-right">
                  {!readOnly && (
                    <>
                      {canEdit && (
                        <button
                          type="button"
                          className="mr-2 text-accent hover:underline"
                          aria-label={`Edit ${typeName} rule of ${name}`}
                          onClick={() => onEdit(rule)}
                        >
                          Edit
                        </button>
                      )}
                      <button
                        type="button"
                        className="text-danger-ink hover:underline"
                        aria-label={`Delete ${typeName} rule of ${name}`}
                        onClick={() => onDelete(rule)}
                      >
                        Delete
                      </button>
                    </>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </ScrollBox>
  );
}
