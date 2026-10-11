import { useRef } from 'react';
import Modal from './Modal';
import { Button } from './ui';

/**
 * Yes/no confirmation built on Modal — the accessible replacement for
 * window.confirm. Controlled by the parent:
 *
 *   <ConfirmDialog
 *     open={!!pending}
 *     title="Disable user"
 *     confirmLabel="Request disable"
 *     tone="danger"
 *     busy={saving}
 *     onConfirm={submit}
 *     onCancel={() => setPending(null)}
 *   >
 *     A second administrator must approve it.
 *   </ConfirmDialog>
 *
 * Props: `open`, `title`, `children` (or `message`), `confirmLabel`
 * ('Confirm'), `cancelLabel` ('Cancel'), `tone` ('default' | 'danger'),
 * `busy` (disables both buttons while the action runs), `onConfirm()`,
 * `onCancel()` (also Escape, the × and a backdrop click).
 *
 * Focus starts on the confirm button — except for `tone="danger"`, where it
 * starts on Cancel so a reflexive Enter never runs the destructive action.
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'default',
  busy = false,
  onConfirm,
  onCancel,
}) {
  const cancelRef = useRef(null);
  const confirmRef = useRef(null);
  const danger = tone === 'danger';

  return (
    <Modal
      open={open}
      onClose={() => {
        if (!busy) onCancel?.();
      }}
      title={title}
      size="sm"
      initialFocusRef={danger ? cancelRef : confirmRef}
      footer={
        <>
          <Button
            ref={cancelRef}
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={() => onCancel?.()}
          >
            {cancelLabel}
          </Button>
          <Button
            ref={confirmRef}
            type="button"
            variant={danger ? 'danger' : 'primary'}
            disabled={busy}
            onClick={() => onConfirm?.()}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children ?? <p>{message}</p>}
    </Modal>
  );
}
