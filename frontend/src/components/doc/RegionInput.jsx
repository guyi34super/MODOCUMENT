import { inputClass } from '../ui';
import { fieldLabel } from './fields';

/**
 * The input for one region — used on the page (positioned, sized to the
 * box) and in the Fields panel (labelled, full width). Both edit the same
 * value, so they always agree.
 */
export default function RegionInput({ region, value, onChange, locked, onPage, style, onFocus, describedBy }) {
  const label = fieldLabel(region);
  const common = {
    disabled: locked,
    onFocus,
    'aria-describedby': describedBy,
    'aria-required': region.required || undefined,
  };
  const pageClass = onPage
    ? 'h-full w-full border border-sky-500/50 bg-sky-50/50 px-0.5 py-0 leading-none text-black outline-none focus:border-accent focus:bg-white/90 focus:ring-2 focus:ring-accent'
    : inputClass;
  const ariaLabel = onPage ? label : undefined;
  if (region.kind === 'CHECKBOX') {
    return (
      <input
        type="checkbox"
        aria-label={ariaLabel}
        checked={value === 'true' || value === 'x' || value === true}
        onChange={(e) => onChange(e.target.checked ? 'true' : '')}
        className={onPage ? 'h-full w-full cursor-pointer accent-accent' : 'size-4'}
        {...common}
      />
    );
  }
  if (region.kind === 'MULTILINE') {
    return (
      <textarea
        aria-label={ariaLabel}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className={`${pageClass} resize-none`}
        rows={onPage ? undefined : Math.min(8, Math.max(2, region.maxLines || 3))}
        style={style}
        {...common}
      />
    );
  }
  const type = region.kind === 'DATE' ? 'date' : region.kind === 'NUMBER' ? 'number' : 'text';
  return (
    <input
      type={type}
      aria-label={ariaLabel}
      inputMode={region.kind === 'NUMBER' ? 'decimal' : undefined}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      className={pageClass}
      style={style}
      {...common}
    />
  );
}
