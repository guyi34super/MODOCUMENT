import { inputClass } from '../ui';
import { fieldLabel } from './fields';

/**
 * The input for one region — used on the page (positioned, sized to the
 * box) and in the Fields panel (labelled, full width). Both edit the same
 * value, so they always agree.
 *
 * The field's properties shape it: `props.placeholder`, `props.maxLength`
 * and, for a dropdown (CHOICE), a <select> of `props.options` with an
 * empty choice. `invalid` marks a validation error (aria-invalid);
 * `describedBy` names the help text and messages under the input.
 */
export default function RegionInput({ id, region, value, onChange, locked, onPage, style, onFocus, describedBy, invalid = false }) {
  const label = fieldLabel(region);
  const props = region.props || {};
  const common = {
    id,
    disabled: locked,
    onFocus,
    'aria-describedby': describedBy || undefined,
    'aria-required': region.required || undefined,
    'aria-invalid': invalid || undefined,
  };
  const pageClass = onPage
    ? `h-full w-full border ${
        invalid ? 'border-danger-ink bg-danger-bg/40' : 'border-sky-500/50 bg-sky-50/50'
      } px-0.5 py-0 leading-none text-black outline-none focus:border-accent focus:bg-white/90 focus:ring-2 focus:ring-accent`
    : `${inputClass} ${invalid ? '!border-danger-ink' : ''}`;
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
  if (region.kind === 'CHOICE') {
    const options = Array.isArray(props.options) ? props.options : [];
    const current = value ?? '';
    return (
      <select
        aria-label={ariaLabel}
        value={current}
        onChange={(e) => onChange(e.target.value)}
        className={`${pageClass} ${onPage ? 'p-0' : ''}`}
        style={style}
        {...common}
      >
        <option value="">{props.placeholder || ''}</option>
        {/* A value from before the options changed stays visible until it is replaced. */}
        {current && !options.includes(current) && <option value={current}>{current}</option>}
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    );
  }
  const maxLength = props.maxLength || undefined;
  if (region.kind === 'MULTILINE') {
    return (
      <textarea
        aria-label={ariaLabel}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className={`${pageClass} resize-none`}
        rows={onPage ? undefined : Math.min(8, Math.max(2, region.maxLines || 3))}
        placeholder={props.placeholder || undefined}
        maxLength={maxLength}
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
      placeholder={props.placeholder || undefined}
      maxLength={type === 'text' ? maxLength : undefined}
      style={style}
      {...common}
    />
  );
}
