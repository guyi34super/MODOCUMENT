import RegionBox from './RegionBox';
import { EDIT_LABELS, LOW_CONFIDENCE } from './geometry';

const EDIT_COLORS = {
  REMOVE: '#c5221f',
  REPLACE_TEXT: '#6a1b9a',
  ADD_TEXT: '#37474f',
  ADD_IMAGE: '#00695c',
};

const HATCH =
  'repeating-linear-gradient(45deg, rgba(197, 34, 31, 0.32) 0 3px, rgba(255, 255, 255, 0.55) 3px 8px)';

export function editColor(kind) {
  return EDIT_COLORS[kind] || '#37474f';
}

/** Shown on a suggested removal (BUG-PDF-01): a low-confidence guess that is NOT removed unless applied. */
export const SUGGESTED_LABEL = 'Suggested — not removed';

/** A suggested removal: proposed by the analysis but not applied (`applied: false`). */
export function isSuggestion(edit) {
  return edit?.applied === false;
}

/** A short description of an edit for labels and lists. */
export function describeEdit(edit) {
  if (isSuggestion(edit)) {
    return edit.removedText ? `${SUGGESTED_LABEL}: “${edit.removedText}”` : SUGGESTED_LABEL;
  }
  const base = EDIT_LABELS[edit.kind] || edit.kind;
  if (edit.kind === 'REMOVE' && edit.removedText) return `${base}: “${edit.removedText}”`;
  if ((edit.kind === 'ADD_TEXT' || edit.kind === 'REPLACE_TEXT') && edit.text) {
    return `${base}: “${edit.text}”`;
  }
  return base;
}

/**
 * A template edit drawn on the page: REMOVE as a hatched box, REPLACE_TEXT
 * as its new text on white (it covers the old text), ADD_TEXT as plain
 * text and ADD_IMAGE as a labelled image placeholder. `scale` is CSS px per
 * PDF point, so the preview text matches the PDF's size.
 *
 * A SUGGESTED removal (`applied: false`) is drawn as a dashed outline with
 * no hatching — the content under it stays in the template — and tagged
 * "Suggested — not removed" until it is applied.
 */
export default function EditBox({ edit, scale = 1, showRemoved = false, ...props }) {
  const suggested = isSuggestion(edit);
  const color = editColor(edit.kind);
  const lowConfidence = edit.kind === 'REMOVE' && edit.confidence != null && edit.confidence < LOW_CONFIDENCE;
  const fontPx = Math.max(6, (edit.fontSize || 10) * scale);
  let background;
  let content = null;
  if (suggested) {
    return (
      <RegionBox
        box={edit}
        color="#b45309"
        highlight
        tabText={SUGGESTED_LABEL}
        label={`${describeEdit(edit)}${lowConfidence ? ' (low confidence)' : ''}`}
        data-edit-kind={edit.kind}
        data-suggested="true"
        data-low-confidence={lowConfidence ? 'true' : undefined}
        style={{ background: 'rgba(245, 158, 11, 0.08)' }}
        {...props}
        editable={false}
      />
    );
  }
  if (edit.kind === 'REMOVE') {
    background = HATCH;
    if (showRemoved && edit.removedText) {
      content = (
        <span className="pointer-events-none absolute inset-0 overflow-hidden px-0.5 text-[10px] leading-tight text-danger-ink line-through">
          {edit.removedText}
        </span>
      );
    }
  } else if (edit.kind === 'REPLACE_TEXT' || edit.kind === 'ADD_TEXT') {
    background = edit.kind === 'REPLACE_TEXT' ? '#ffffff' : 'transparent';
    content = (
      <span
        className="pointer-events-none absolute inset-0 overflow-hidden whitespace-pre-wrap leading-tight"
        style={{ fontSize: `${fontPx}px`, color: edit.color || '#000000' }}
      >
        {edit.text}
      </span>
    );
  } else if (edit.kind === 'ADD_IMAGE') {
    background = 'rgba(0, 105, 92, 0.12)';
    content = (
      <span className="pointer-events-none absolute inset-0 flex items-center justify-center text-[10px] text-teal-900">
        Image
      </span>
    );
  }
  return (
    <RegionBox
      box={edit}
      color={color}
      highlight={lowConfidence}
      label={`${describeEdit(edit)}${lowConfidence ? ' (low confidence)' : ''}`}
      data-edit-kind={edit.kind}
      data-low-confidence={lowConfidence ? 'true' : undefined}
      style={{ background }}
      {...props}
    >
      {content}
    </RegionBox>
  );
}
