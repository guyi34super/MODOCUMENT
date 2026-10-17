import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Stepper from './Stepper';

const STEPS = [
  { id: 'upload', label: 'Upload', done: true },
  { id: 'validation', label: 'Validation' },
  { id: 'mapping', label: 'Map fields' },
  { id: 'documents', label: 'Documents', locked: true, reason: 'Map the fields first' },
];

describe('Stepper — plain pills (the ingestion wizard)', () => {
  it('marks done steps, disables locked ones with their reason and selects open ones', async () => {
    const onSelect = vi.fn();
    render(<Stepper steps={STEPS} current="validation" onSelect={onSelect} />);

    expect(screen.getByRole('button', { name: '✓ Upload' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Validation' })).toBeEnabled();
    const locked = screen.getByRole('button', { name: 'Documents' });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('title', 'Map the fields first');
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Map fields' }));
    expect(onSelect).toHaveBeenCalledWith('mapping');
    fireEvent.click(locked);
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it('shows no ✓ on the current step, even when it is done', () => {
    render(<Stepper steps={STEPS} current="upload" onSelect={() => {}} />);
    expect(screen.getByRole('button', { name: 'Upload' })).toBeInTheDocument();
  });
});

describe('Stepper — tabs', () => {
  const renderTabs = (props = {}) => {
    const onSelect = vi.fn();
    render(
      <>
        <Stepper
          tabs
          label="Template steps"
          idPrefix="t"
          steps={STEPS}
          current="validation"
          onSelect={onSelect}
          panelId={(id) => `panel-${id}`}
          {...props}
        />
        <div role="tabpanel" id="panel-validation" aria-labelledby="t-tab-validation">
          Validation step
        </div>
      </>
    );
    return onSelect;
  };

  it('is a named tablist whose tabs are named by their label alone', () => {
    renderTabs();
    expect(screen.getByRole('tablist', { name: 'Template steps' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.getAttribute('aria-label'))).toEqual([
      'Upload',
      'Validation',
      'Map fields',
      'Documents',
    ]);
    const current = screen.getByRole('tab', { name: 'Validation' });
    expect(current).toHaveAttribute('aria-selected', 'true');
    expect(current).toHaveAttribute('aria-controls', 'panel-validation');
    expect(current).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: 'Upload' })).toHaveAttribute('data-done', 'true');
    expect(screen.getByRole('tab', { name: 'Upload' })).toHaveTextContent('✓');
    expect(screen.getByRole('tab', { name: 'Map fields' })).toHaveTextContent('3');
  });

  it('says why a step is locked and ignores clicks on it', async () => {
    const onSelect = renderTabs();
    const locked = screen.getByRole('tab', { name: 'Documents' });
    expect(locked).toHaveAttribute('aria-disabled', 'true');
    expect(locked).toHaveAccessibleDescription('Documents: Map the fields first');
    expect(screen.getByText('Documents: Map the fields first')).toBeVisible();
    await userEvent.click(locked);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('points a locked step at a reason shown elsewhere', () => {
    render(
      <>
        <p id="elsewhere">Save and validate first</p>
        <Stepper
          tabs
          label="Steps"
          steps={[
            { id: 'a', label: 'Fix' },
            { id: 'b', label: 'Download', locked: true, reason: 'Save and validate first', describedBy: 'elsewhere' },
          ]}
          current="a"
          onSelect={() => {}}
        />
      </>
    );
    expect(screen.getByRole('tab', { name: 'Download' })).toHaveAccessibleDescription('Save and validate first');
    expect(screen.getAllByText(/Save and validate first/)).toHaveLength(1);
  });

  it('moves between open steps with the arrow keys, Home and End, skipping locked ones', () => {
    const onSelect = renderTabs();
    const current = screen.getByRole('tab', { name: 'Validation' });
    fireEvent.keyDown(current, { key: 'ArrowRight' });
    expect(onSelect).toHaveBeenLastCalledWith('mapping');
    fireEvent.keyDown(current, { key: 'ArrowLeft' });
    expect(onSelect).toHaveBeenLastCalledWith('upload');
    fireEvent.keyDown(current, { key: 'End' });
    expect(onSelect).toHaveBeenLastCalledWith('mapping');
    fireEvent.keyDown(current, { key: 'Home' });
    expect(onSelect).toHaveBeenLastCalledWith('upload');
  });
});
