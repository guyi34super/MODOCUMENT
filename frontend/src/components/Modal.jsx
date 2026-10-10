import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const SIZES = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-2xl' };

function focusableIn(node) {
  if (!node) return [];
  return Array.from(node.querySelectorAll(FOCUSABLE)).filter(
    (el) => !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true'
  );
}

/**
 * Accessible modal dialog (phase 2 shared component). Rendered in a portal on
 * document.body with role="dialog", aria-modal and the title as its label.
 * While open it traps Tab/Shift+Tab inside the panel, Escape (and, unless
 * `closeOnBackdrop` is false, a click on the backdrop) calls `onClose`, the
 * page behind does not scroll, and on close focus returns to the element
 * that had it when the dialog opened (the button that opened it).
 *
 * Props:
 * - `open` (bool), `onClose()` — the dialog is controlled by its parent
 * - `title` (node, required: it labels the dialog), `description` (node, optional)
 * - `children` (body), `footer` (node: action buttons, right-aligned)
 * - `size`: 'sm' | 'md' (default) | 'lg'
 * - `initialFocusRef`: element to focus on open (default: first focusable)
 * - `closeOnBackdrop` (default true)
 */
export default function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  initialFocusRef,
  closeOnBackdrop = true,
}) {
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef(null);
  // Latest onClose without re-running the open/close effect on every render.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return undefined;
    const opener = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    // Default focus: the first field or button of the content — not the
    // header's close button, which would make every dialog start on "×".
    const items = focusableIn(panelRef.current);
    const target =
      initialFocusRef?.current ||
      items.find((el) => !el.hasAttribute('data-modal-close')) ||
      items[0] ||
      panelRef.current;
    target?.focus();

    return () => {
      document.body.style.overflow = previousOverflow;
      // Back to the opener — only if it is still on the page and focusable.
      if (opener && typeof opener.focus === 'function' && document.contains(opener)) {
        opener.focus();
      }
    };
    // initialFocusRef is a ref object: stable for the dialog's lifetime.
  }, [open]);

  if (!open) return null;

  const onKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onCloseRef.current?.();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = focusableIn(panelRef.current);
    if (items.length === 0) {
      event.preventDefault();
      panelRef.current?.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !panelRef.current.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !panelRef.current.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  };

  const onBackdropMouseDown = (event) => {
    if (closeOnBackdrop && event.target === event.currentTarget) {
      onCloseRef.current?.();
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-[48] flex items-start justify-center overflow-y-auto bg-ink/40 p-4 sm:items-center"
      onMouseDown={onBackdropMouseDown}
      data-testid="modal-backdrop"
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={`w-full ${SIZES[size] || SIZES.md} rounded-lg border border-line bg-white shadow-xl outline-none`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-3">
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-normal text-ink">
              {title}
            </h2>
            {description && (
              <p id={descriptionId} className="mt-0.5 text-sm text-muted">
                {description}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={() => onCloseRef.current?.()}
            className="-mr-1 cursor-pointer rounded px-1.5 text-xl leading-none text-muted hover:bg-pill/50 hover:text-ink"
            aria-label="Close dialog"
            data-modal-close=""
          >
            ×
          </button>
        </div>
        <div className="px-5 py-4 text-sm text-ink">{children}</div>
        {footer && (
          <div className="flex flex-wrap justify-end gap-2 border-t border-line px-5 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}
