import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import SettingField, {
  draftProblem,
  limitsHint,
  normalizeDraft,
  toApiValue,
  validateSettingInput,
} from './SettingField';

/** A SettingView as GET /api/admin/settings returns it (contracts §3). */
function settingView(overrides = {}) {
  return {
    key: 'security.password.minLength',
    type: 'INT',
    tier: 'SECURITY',
    scope: 'BANK',
    group: 'signIn',
    label: 'Password minimum length',
    description: 'Shortest password a user may choose.',
    unit: 'characters',
    value: 12,
    source: 'DEFAULT',
    default: 12,
    min: 12,
    max: 128,
    hardMin: 12,
    hardMax: 128,
    allowed: null,
    requiresApproval: true,
    requiresRestart: false,
    locked: false,
    clamped: false,
    pendingChangeId: null,
    version: 0,
    ...overrides,
  };
}

/** Holds the draft like SettingsPage does, so typing accumulates. */
function Harness({ onDraft, ...props }) {
  const [draft, setDraft] = useState(props.draft);
  return (
    <SettingField
      {...props}
      draft={draft}
      onDraft={(next) => {
        setDraft(next);
        onDraft(next);
      }}
    />
  );
}

function renderStateful(props) {
  const onDraft = vi.fn();
  render(
    <MemoryRouter>
      <Harness onDraft={onDraft} {...props} />
    </MemoryRouter>
  );
  return { onDraft };
}

function renderField(props) {
  const onDraft = props.onDraft || vi.fn();
  const view = render(
    <MemoryRouter>
      <SettingField onDraft={onDraft} {...props} />
    </MemoryRouter>
  );
  return { ...view, onDraft };
}

describe('SettingField', () => {
  it('shows the input with its unit, the effective value, its source and the limits', () => {
    renderField({ setting: settingView({ value: 14, source: 'BANK' }) });

    const row = screen.getByTestId('setting-security.password.minLength');
    const input = within(row).getByLabelText('Password minimum length');
    expect(input).toHaveValue(14);
    expect(input).toHaveAttribute('type', 'number');
    expect(within(row).getAllByText('characters').length).toBeGreaterThan(0);
    expect(within(row).getByTestId('setting-source')).toHaveTextContent('Bank');
    expect(row).toHaveTextContent('12–128 characters · go-live floor 12');
    expect(within(row).getByText('Requires approval')).toBeInTheDocument();
  });

  it('emits a draft when the value changes and drops it when it is changed back', async () => {
    const { onDraft } = renderStateful({ setting: settingView() });
    const input = screen.getByLabelText('Password minimum length');

    await userEvent.clear(input);
    await userEvent.type(input, '14');
    expect(onDraft).toHaveBeenLastCalledWith({ value: '14' });

    expect(normalizeDraft(settingView(), { value: '12' })).toBeUndefined();
  });

  it('renders a locked setting read-only with the Locked badge and no reset', () => {
    renderField({
      setting: settingView({
        key: 'security.mfa.requireForAdmins',
        type: 'BOOLEAN',
        label: 'MFA for administrators',
        unit: null,
        value: true,
        source: 'BANK',
        locked: true,
        allowed: ['true'],
        min: null,
        max: null,
        hardMin: null,
        hardMax: null,
      }),
    });

    const row = screen.getByTestId('setting-security.mfa.requireForAdmins');
    expect(within(row).getByText('Locked')).toBeInTheDocument();
    expect(within(row).getByRole('switch', { name: 'MFA for administrators' })).toBeDisabled();
    expect(within(row).queryByText('Requires approval')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^Reset to inherited/ })).toBeNull();
  });

  it('marks a clamped value and a pending change, and blocks editing while pending', () => {
    renderField({ setting: settingView({ clamped: true, pendingChangeId: 7 }) });

    expect(screen.getByText('Clamped by platform')).toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'change #7' })).toHaveAttribute(
      'href',
      '/admin/changes'
    );
    expect(screen.getByLabelText('Password minimum length')).toBeDisabled();
  });

  it('offers "Reset to inherited" only for a value set at its own layer', async () => {
    const { onDraft, rerender } = renderField({ setting: settingView({ source: 'BANK' }) });
    await userEvent.click(screen.getByRole('button', { name: /^Reset to inherited/ }));
    expect(onDraft).toHaveBeenCalledWith({ reset: true });

    rerender(
      <MemoryRouter>
        <SettingField setting={settingView({ source: 'PLATFORM' })} onDraft={onDraft} />
      </MemoryRouter>
    );
    expect(screen.queryByRole('button', { name: /^Reset to inherited/ })).toBeNull();
  });

  it('renders a platform-set setting read-only for the bank, editable for the platform', async () => {
    const flag = settingView({
      key: 'integration.auth.allowApiKeys',
      type: 'BOOLEAN',
      label: 'Allow API keys',
      unit: null,
      value: false,
      source: 'BANK',
      allowed: ['true', 'false'],
      min: null,
      max: null,
      hardMin: null,
      hardMax: null,
      platformControlled: true,
    });
    const { onDraft, rerender } = renderField({ setting: flag });

    let row = screen.getByTestId('setting-integration.auth.allowApiKeys');
    expect(within(row).getByText('Set by platform')).toBeInTheDocument();
    expect(within(row).getByRole('switch', { name: 'Allow API keys' })).toBeDisabled();
    expect(within(row).queryByText('Requires approval')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Reset to inherited' })).toBeNull();

    // The platform's per-bank screen edits the same view.
    rerender(
      <MemoryRouter>
        <SettingField setting={flag} mode="platformBank" onDraft={onDraft} />
      </MemoryRouter>
    );
    row = screen.getByTestId('setting-integration.auth.allowApiKeys');
    expect(within(row).queryByText('Set by platform')).toBeNull();
    expect(within(row).getByText('Requires approval')).toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: 'Reset to inherited' }));
    expect(onDraft).toHaveBeenCalledWith({ reset: true });
  });

  it('shows a pending reset and lets it be undone', async () => {
    const { onDraft } = renderField({
      setting: settingView({ source: 'BANK' }),
      draft: { reset: true },
    });
    expect(screen.getByText('Resets to the inherited value when saved.')).toBeInTheDocument();
    expect(screen.getByLabelText('Password minimum length')).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: /^Undo/ }));
    expect(onDraft).toHaveBeenCalledWith(undefined);
  });

  it('toggles a boolean through a labelled switch', async () => {
    const { onDraft } = renderField({
      setting: settingView({
        key: 'privacy.masking.screenWorking',
        type: 'BOOLEAN',
        label: 'Mask personal data on screen',
        unit: null,
        value: false,
        allowed: ['true', 'false'],
        min: null,
        max: null,
      }),
    });

    await userEvent.click(screen.getByRole('switch', { name: 'Mask personal data on screen' }));
    expect(onDraft).toHaveBeenCalledWith({ value: true });
  });

  it('offers the allowed values of an enum', () => {
    renderField({
      setting: settingView({
        key: 'extraction.defaultMode',
        type: 'ENUM',
        tier: 'OPERATIONAL',
        requiresApproval: false,
        label: 'Default extraction mode',
        unit: null,
        value: 'AI_WITH_CODE_FALLBACK',
        allowed: ['AI_WITH_CODE_FALLBACK', 'CODE_ONLY', 'AI_ONLY'],
        min: null,
        max: null,
      }),
    });

    const select = screen.getByLabelText('Default extraction mode');
    expect(within(select).getAllByRole('option').map((o) => o.value)).toEqual([
      'AI_WITH_CODE_FALLBACK',
      'CODE_ONLY',
      'AI_ONLY',
    ]);
    expect(screen.queryByText('Requires approval')).not.toBeInTheDocument();
  });

  it('flags a malformed duration inline', () => {
    renderField({
      setting: settingView({
        key: 'security.session.idleTimeout',
        type: 'DURATION',
        label: 'Idle timeout',
        unit: null,
        value: '12h',
        min: '15m',
        max: '12h',
        hardMin: '15m',
        hardMax: '12h',
      }),
      draft: { value: 'soon' },
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Use a duration such as');
    expect(screen.getByLabelText('Idle timeout')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows the server refusal for the key inline', () => {
    renderField({
      setting: settingView(),
      error: 'Password minimum length must be between 12 and 128.',
    });
    expect(screen.getByRole('alert')).toHaveTextContent('must be between 12 and 128');
  });

  it('adds the bank-bounds editors in platform mode', async () => {
    const { onDraft } = renderStateful({
      mode: 'platform',
      setting: settingView({ source: 'PLATFORM', bankMin: null, bankMax: null, hasBankBounds: true }),
    });

    const min = screen.getByLabelText('Password minimum length: bank minimum');
    await userEvent.type(min, '14');
    expect(onDraft).toHaveBeenLastCalledWith({ bankMin: '14' });
    expect(screen.getByLabelText('Password minimum length: bank maximum')).toBeInTheDocument();
  });
});

describe('setting value helpers', () => {
  it('converts inputs to the JSON the API expects', () => {
    expect(toApiValue('INT', ' 14 ')).toBe(14);
    expect(toApiValue('DECIMAL', '0.8')).toBe(0.8);
    expect(toApiValue('BOOLEAN', 'true')).toBe(true);
    expect(toApiValue('DURATION', '15 m')).toBe('15m');
    expect(toApiValue('ENUM', 'CODE_ONLY')).toBe('CODE_ONLY');
  });

  it('validates shapes, not limits', () => {
    expect(validateSettingInput('INT', '11')).toBeNull(); // the server judges the range
    expect(validateSettingInput('INT', '1.5')).toMatch(/whole number/);
    expect(validateSettingInput('DECIMAL', 'abc')).toMatch(/number/);
    expect(validateSettingInput('DURATION', '90s')).toBeNull();
    expect(validateSettingInput('DURATION', 'PT15M')).toBeNull();
    expect(validateSettingInput('DURATION', 'later')).toMatch(/duration/);
    expect(draftProblem(settingView(), { reset: true })).toBeNull();
  });

  it('describes the limits with the go-live floor and a differing ceiling', () => {
    expect(limitsHint(settingView({ max: 64 }))).toBe(
      '12–64 characters · go-live floor 12 · ceiling 128'
    );
    expect(limitsHint(settingView({ type: 'BOOLEAN', min: null, max: null }))).toBeNull();
  });
});
