import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConfirmDialog from './ConfirmDialog';

function renderDialog(props = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog
      open
      title="Disable user"
      message="A second administrator must approve it."
      confirmLabel="Request disable"
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...props}
    />
  );
  return { onConfirm, onCancel };
}

describe('ConfirmDialog', () => {
  it('renders an accessible dialog with the title and the message', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog', { name: 'Disable user' });
    expect(dialog).toHaveTextContent('A second administrator must approve it.');
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request disable' })).toBeInTheDocument();
  });

  it('renders children instead of the message when given', () => {
    render(
      <ConfirmDialog open title="Unlock" onConfirm={() => {}} onCancel={() => {}}>
        <p>
          Unlock <strong>worker@bank.example</strong>?
        </p>
      </ConfirmDialog>
    );
    expect(screen.getByRole('dialog')).toHaveTextContent('Unlock worker@bank.example?');
    // Default labels.
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
  });

  it('confirms and cancels through the callbacks', async () => {
    const { onConfirm, onCancel } = renderDialog();

    await userEvent.click(screen.getByRole('button', { name: 'Request disable' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('treats Escape as cancel', async () => {
    const { onConfirm, onCancel } = renderDialog();

    await userEvent.keyboard('{Escape}');

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('starts on Cancel for a danger action, so a reflexive Enter does nothing harmful', async () => {
    const { onConfirm, onCancel } = renderDialog({ tone: 'danger' });

    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Request disable' }).className).toContain(
      'text-danger-ink'
    );
    await userEvent.keyboard('{Enter}');
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('starts on the confirm button for an ordinary action', () => {
    renderDialog({ tone: 'default', confirmLabel: 'Unlock' });
    expect(screen.getByRole('button', { name: 'Unlock' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Unlock' }).className).toContain('bg-accent');
  });

  it('disables both buttons and ignores Escape while busy', async () => {
    const { onCancel } = renderDialog({ busy: true });

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Request disable' })).toBeDisabled();
    await userEvent.keyboard('{Escape}');
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('renders nothing while closed', () => {
    renderDialog({ open: false });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
