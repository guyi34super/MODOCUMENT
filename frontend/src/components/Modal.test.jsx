import { describe, expect, it, vi } from 'vitest';
import { useRef, useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Modal from './Modal';

/** A page-like harness: a button opens the dialog, closing goes through state. */
function Harness({ closeOnBackdrop, withInitialFocus = false, onClose = () => {} }) {
  const [open, setOpen] = useState(false);
  const secondRef = useRef(null);
  const close = () => {
    onClose();
    setOpen(false);
  };
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Open dialog
      </button>
      <Modal
        open={open}
        onClose={close}
        title="Edit user"
        description="worker@bank.example"
        closeOnBackdrop={closeOnBackdrop}
        initialFocusRef={withInitialFocus ? secondRef : undefined}
        footer={
          <>
            <button type="button" onClick={close}>
              Cancel
            </button>
            <button type="button">Save</button>
          </>
        }
      >
        <label>
          Display name
          <input />
        </label>
        <label>
          Second field
          <input ref={secondRef} />
        </label>
      </Modal>
    </div>
  );
}

describe('Modal', () => {
  it('renders nothing while closed', () => {
    render(
      <Modal open={false} onClose={() => {}} title="Hidden">
        body
      </Modal>
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('is a modal dialog labelled by its title and described by its description', async () => {
    const { container } = render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    const dialog = screen.getByRole('dialog', { name: 'Edit user' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription('worker@bank.example');
    // Rendered in a portal on document.body, outside the page's own tree.
    expect(container).not.toContainElement(dialog);
    expect(document.body).toContainElement(dialog);
  });

  it('focuses the first field — not the close button — when it opens', async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    expect(screen.getByRole('textbox', { name: 'Display name' })).toHaveFocus();
  });

  it('honours initialFocusRef', async () => {
    render(<Harness withInitialFocus />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    expect(screen.getByRole('textbox', { name: 'Second field' })).toHaveFocus();
  });

  it('traps Tab and Shift+Tab inside the dialog', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open dialog' }));

    // Last focusable → Tab wraps to the first (the close button)...
    screen.getByRole('button', { name: 'Save' }).focus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Close dialog' })).toHaveFocus();

    // ...and Shift+Tab on the first wraps back to the last.
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();

    // Never onto the page behind the dialog.
    for (let i = 0; i < 6; i += 1) {
      await user.tab();
      expect(screen.getByRole('dialog')).toContainElement(document.activeElement);
    }
  });

  it('closes on Escape and returns focus to the button that opened it', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Harness onClose={onClose} />);
    const opener = screen.getByRole('button', { name: 'Open dialog' });
    await user.click(opener);
    expect(opener).not.toHaveFocus();

    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('closes from the × button as well', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    await userEvent.click(screen.getByRole('button', { name: 'Close dialog' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Open dialog' })).toHaveFocus();
  });

  it('closes on a backdrop click, but not on a click inside the panel', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    fireEvent.mouseDown(screen.getByRole('dialog'));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByTestId('modal-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('ignores backdrop clicks when closeOnBackdrop is false', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} closeOnBackdrop={false} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dialog' }));

    fireEvent.mouseDown(screen.getByTestId('modal-backdrop'));

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('locks page scrolling while open and restores it on close', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open dialog' }));
    expect(document.body.style.overflow).toBe('hidden');

    await user.keyboard('{Escape}');
    expect(document.body.style.overflow).toBe('');
  });
});
