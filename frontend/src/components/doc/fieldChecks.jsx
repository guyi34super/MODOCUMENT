// What is checked on each field of a document (field-validation contract §4.4,
// "Making the checks visible"): `doc.fieldChecks[regionKey]` =
// { rules: [{ id, ruleType, summary, severity, origin, sourceTemplateName?, sourceFieldName? }],
//   builtIn: [string], mappedFrom: [{ templateId, templateName, fieldName, role }] }.
// Template data only — never a document value.

import { Tag } from '../ui';

const EMPTY = Object.freeze({ rules: [], builtIn: [], mappedFrom: [] });

/** The checks of one field, always with the three lists. */
export function checksFor(fieldChecks, key) {
  const checks = fieldChecks?.[key];
  if (!checks) return EMPTY;
  return { rules: checks.rules || [], builtIn: checks.builtIn || [], mappedFrom: checks.mappedFrom || [] };
}

/** The number of rules on the template (every field's rules together). */
export function totalRules(fieldChecks) {
  return Object.values(fieldChecks || {}).reduce((sum, checks) => sum + (checks?.rules?.length || 0), 0);
}

export const hasChecks = (checks) => checks.rules.length + checks.builtIn.length + checks.mappedFrom.length > 0;

/** "Template.field" an IMPORTED rule came from, or null for the template's own rules. */
export function importedFrom(rule) {
  if (rule.origin !== 'IMPORTED' && !rule.sourceTemplateName) return null;
  if (!rule.sourceTemplateName) return 'another template';
  return rule.sourceFieldName ? `${rule.sourceTemplateName}.${rule.sourceFieldName}` : rule.sourceTemplateName;
}

/** "Template.field", plus " (linked)" for a LINKED data source. */
export const mappingText = (m) =>
  `${m.templateName || `Template ${m.templateId}`}.${m.fieldName}${m.role === 'LINKED' ? ' (linked)' : ''}`;

const isWarning = (rule) => rule.severity === 'WARNING';

/** The tooltip of a field's input on the page: its rules in one line, or undefined without rules. */
export function rulesTitle(checks) {
  if (!checks.rules.length) return undefined;
  return `Rules: ${checks.rules.map((r) => r.summary).join('; ')}`;
}

const COMPACT_RULES = 3;

/** The id of the text describing a field's checks in the side list. */
export const checksId = (fieldKey) => `field-checks-text-${fieldKey}`;

/** The plain text of the compact chips (what a screen reader hears as the field's description). */
export function compactText(checks) {
  const parts = [];
  const shown = checks.rules.slice(0, COMPACT_RULES);
  if (shown.length) {
    const more = checks.rules.length - shown.length;
    const items = shown.map((r) => {
      const from = importedFrom(r);
      return `${r.summary}${isWarning(r) ? ' (warning)' : ''}${from ? ` (imported from ${from})` : ''}`;
    });
    if (more) items.push(`${more} more`);
    parts.push(`Rules: ${items.join('; ')}.`);
  }
  if (checks.mappedFrom.length) {
    parts.push(`From ${checks.mappedFrom.map(mappingText).join(', ')}.`);
  }
  return parts.join(' ');
}

/**
 * Under a field in the side list: the first rule summaries ("+N more"), the
 * imported marker and the data-source mapping. `checksId(fieldKey)` holds the
 * text the input's aria-describedby reads; the chips repeat it visually.
 */
export function FieldCheckChips({ fieldKey, checks }) {
  const text = compactText(checks);
  if (!text) return null;
  const shown = checks.rules.slice(0, COMPACT_RULES);
  const more = checks.rules.length - shown.length;
  return (
    <div className="mt-0.5" data-testid={`field-checks-${fieldKey}`}>
      <span id={checksId(fieldKey)} className="sr-only">
        {text}
      </span>
      <span aria-hidden="true" className="flex flex-wrap items-center gap-1 text-[11px]">
        {shown.map((rule, i) => {
          const from = importedFrom(rule);
          return (
            <Tag
              key={rule.id ?? i}
              tone={isWarning(rule) ? 'warning' : 'info'}
              title={from ? `Imported from ${from}` : undefined}
            >
              {rule.summary}
              {from ? ' · imported' : ''}
            </Tag>
          );
        })}
        {more > 0 && <Tag tone="neutral">+{more} more</Tag>}
        {checks.mappedFrom.map((m) => (
          <span key={`${m.templateId}:${m.fieldName}:${m.role}`} className="text-muted">
            from {mappingText(m)}
          </span>
        ))}
      </span>
    </div>
  );
}

/** All the checks of one field, for the Checks panel's "Rules for each field". */
export function FieldChecksDetail({ checks }) {
  return (
    <>
      {(checks.rules.length > 0 || checks.builtIn.length > 0) && (
        <ul className="mt-1 flex flex-wrap gap-1" aria-label="Checks">
          {checks.rules.map((rule, i) => {
            const from = importedFrom(rule);
            return (
              <li key={rule.id ?? `r${i}`} data-severity={rule.severity || 'ERROR'}>
                <Tag tone={isWarning(rule) ? 'warning' : 'info'} className="!whitespace-normal">
                  {rule.summary}
                  {isWarning(rule) && <span className="sr-only"> (warning)</span>}
                  {from && <span className="font-normal"> · from {from}</span>}
                </Tag>
              </li>
            );
          })}
          {checks.builtIn.map((text) => (
            <li key={`b:${text}`} data-builtin="true">
              <Tag tone="neutral">{text}</Tag>
            </li>
          ))}
        </ul>
      )}
      {checks.mappedFrom.map((m) => (
        <p key={`${m.templateId}:${m.fieldName}:${m.role}`} className="mt-1 text-xs text-muted">
          Filled from {mappingText(m)}
        </p>
      ))}
    </>
  );
}
