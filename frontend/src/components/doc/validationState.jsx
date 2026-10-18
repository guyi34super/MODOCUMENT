import { Tag } from '../ui';

/**
 * The validation state of a document in a list (data-templates contract §5:
 * InstanceSummaryDto.validationState / errorCount). Older servers without
 * the field fall back on the status. 'OFF': validation is switched off for
 * the document (contract §8) — nothing gates its download.
 */
export function validationStateOf(row) {
  if (row?.validationState) return row.validationState;
  if (row?.validationEnabled === false) return 'OFF';
  if (['VALIDATED', 'GENERATED', 'SUBMITTED', 'RELEASED'].includes(row?.status)) return 'VALIDATED';
  return 'NOT_CHECKED';
}

export function validationStateText(row) {
  const state = validationStateOf(row);
  if (state === 'ERRORS') {
    const n = row.errorCount ?? 0;
    return n ? `${n} error${n === 1 ? '' : 's'}` : 'Errors';
  }
  if (state === 'VALIDATED') return 'Validated';
  if (state === 'OFF') return 'Off';
  if (state === 'RULES_CHANGED') return 'Rules changed — save again';
  return 'Not checked';
}

const TONES = { ERRORS: 'danger', VALIDATED: 'success', RULES_CHANGED: 'warning', NOT_CHECKED: 'neutral', OFF: 'neutral' };

/** Why a document cannot be downloaded yet ('' when it can). */
export function downloadBlockReason(row) {
  const state = validationStateOf(row);
  if (state === 'VALIDATED' || state === 'OFF') return '';
  if (state === 'RULES_CHANGED') return 'The rules changed — open it and save again';
  if (state === 'ERRORS') return 'Fix its errors and save it first';
  return 'Save and validate it first';
}

export function ValidationStateTag({ row }) {
  const state = validationStateOf(row);
  return (
    <Tag tone={TONES[state] || 'neutral'} title={state === 'OFF' ? 'Validation is off for this document' : undefined}>
      <span data-testid="validation-state">{validationStateText(row)}</span>
    </Tag>
  );
}
