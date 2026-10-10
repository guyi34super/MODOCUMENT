import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import SettingsPage from './SettingsPage';

import {
  getBankSettings,
  getBankSettingsForPlatform,
  getPlatformSettings,
  updateBankSettings,
  updateBankSettingsForPlatform,
  updatePlatformSettings,
} from '../api/settings';

vi.mock('../api/settings', async () => {
  const getBankSettings = vi.fn();
  const updateBankSettings = vi.fn();
  const getPlatformSettings = vi.fn();
  const updatePlatformSettings = vi.fn();
  const getBankSettingsForPlatform = vi.fn();
  const updateBankSettingsForPlatform = vi.fn();
  return {
    getBankSettings,
    updateBankSettings,
    getPlatformSettings,
    updatePlatformSettings,
    getBankSettingsForPlatform,
    updateBankSettingsForPlatform,
    settingsApi: (mode, bankId) => {
      if (mode === 'platform') return { load: getPlatformSettings, save: updatePlatformSettings };
      if (mode === 'platformBank') {
        return {
          load: () => getBankSettingsForPlatform(bankId),
          save: (changes, comment) => updateBankSettingsForPlatform(bankId, changes, comment),
        };
      }
      return { load: getBankSettings, save: updateBankSettings };
    },
  };
});

function view(overrides = {}) {
  return {
    type: 'INT',
    tier: 'SECURITY',
    scope: 'BANK',
    description: null,
    unit: null,
    source: 'DEFAULT',
    min: null,
    max: null,
    hardMin: null,
    hardMax: null,
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

const MIN_LENGTH = view({
  key: 'security.password.minLength',
  group: 'signIn',
  label: 'Password minimum length',
  unit: 'characters',
  value: 12,
  default: 12,
  min: 12,
  max: 128,
  hardMin: 12,
  hardMax: 128,
});
const LOCKOUT = view({
  key: 'security.lockout.allowedAttempts',
  group: 'signIn',
  label: 'Allowed sign-in attempts',
  unit: 'attempts',
  value: 6,
  source: 'BANK',
  version: 3,
  min: 3,
  max: 10,
  hardMin: 3,
  hardMax: 10,
});
const MFA_ADMINS = view({
  key: 'security.mfa.requireForAdmins',
  group: 'signIn',
  type: 'BOOLEAN',
  label: 'MFA for administrators',
  value: true,
  allowed: ['true'],
  locked: true,
});
const RETENTION = view({
  key: 'retention.customerDataDays',
  group: 'dataProtection',
  label: 'Customer data retention',
  unit: 'days',
  value: 20,
  source: 'BANK',
  clamped: true,
  version: 2,
  min: 1,
  max: 20,
  hardMin: 1,
  hardMax: 30,
});
const MIN_CONFIDENCE = view({
  key: 'extraction.code.minConfidence',
  group: 'aiExtraction',
  type: 'DECIMAL',
  tier: 'OPERATIONAL',
  requiresApproval: false,
  label: 'Code extraction minimum confidence',
  value: 0.75,
  min: 0.5,
  max: 0.95,
  hardMin: 0.5,
  hardMax: 0.95,
});

const PAGE = {
  layer: 'BANK',
  revision: 4,
  groups: [
    { id: 'signIn', label: 'Sign-in & passwords', settings: [MIN_LENGTH, LOCKOUT, MFA_ADMINS] },
    { id: 'dataProtection', label: 'Data protection', settings: [RETENTION] },
    { id: 'aiExtraction', label: 'AI & extraction', settings: [MIN_CONFIDENCE] },
  ],
};

function renderPage(mode = 'bank') {
  return render(
    <MemoryRouter initialEntries={[mode === 'platform' ? '/platform/settings' : '/admin/settings']}>
      <ToastProvider>
        <SettingsPage mode={mode} />
      </ToastProvider>
    </MemoryRouter>
  );
}

async function typeInto(label, text) {
  const input = await screen.findByLabelText(label);
  await userEvent.clear(input);
  await userEvent.type(input, text);
}

describe('SettingsPage (bank)', () => {
  beforeEach(() => {
    getBankSettings.mockReset().mockResolvedValue(PAGE);
    updateBankSettings.mockReset();
  });

  it('shows one tab per group and the first group\'s settings', async () => {
    renderPage();

    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      'Sign-in & passwords',
      'Data protection',
      'AI & extraction',
    ]);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('setting-security.password.minLength')).toBeInTheDocument();
    expect(screen.queryByTestId('setting-retention.customerDataDays')).toBeNull();
    // Locked: badge and a disabled control, nothing to save yet.
    const mfa = screen.getByTestId('setting-security.mfa.requireForAdmins');
    expect(within(mfa).getByText('Locked')).toBeInTheDocument();
    expect(within(mfa).getByRole('switch')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save Sign-in & passwords' })).toBeDisabled();
  });

  it('moves between tabs with Arrow, Home and End and names unsaved tabs in words', async () => {
    const user = userEvent.setup();
    renderPage();
    const tabs = await screen.findAllByRole('tab');
    // Only the selected tab points at a panel, and that panel exists.
    expect(tabs[0]).toHaveAttribute('aria-controls');
    expect(tabs[1]).not.toHaveAttribute('aria-controls');

    tabs[0].focus();
    await user.keyboard('{End}');
    expect(screen.getByRole('tab', { name: 'AI & extraction' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: 'AI & extraction' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await user.keyboard('{Home}');
    expect(screen.getByRole('tab', { name: /^Sign-in & passwords/ })).toHaveFocus();

    await user.click(within(screen.getByRole('tabpanel')).getAllByRole('spinbutton')[0]);
    await user.keyboard('{Backspace}{Backspace}13');
    expect(
      screen.getByRole('tab', { name: 'Sign-in & passwords (unsaved changes)' })
    ).toBeInTheDocument();
  });

  it('shows the clamped badge on the data-protection tab', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: 'Data protection' }));

    const retention = screen.getByTestId('setting-retention.customerDataDays');
    expect(within(retention).getByText('Clamped by platform')).toBeInTheDocument();
    expect(retention).toHaveTextContent('1–20 days · go-live floor 1 · ceiling 30');
  });

  it('saves only the changed keys of the tab and reports applied vs sent for approval', async () => {
    updateBankSettings.mockResolvedValue({
      applied: [],
      pendingChange: {
        pendingChangeId: 41,
        status: 'REQUESTED',
        payload: { changes: [{ key: 'security.password.minLength' }, { key: 'x' }] },
      },
    });
    renderPage();

    await typeInto('Password minimum length', '14');
    await typeInto('Allowed sign-in attempts', '5');
    await userEvent.type(screen.getByLabelText(/Note for the approver/), 'Audit finding 7');
    await userEvent.click(screen.getByRole('button', { name: 'Save Sign-in & passwords' }));

    await waitFor(() =>
      expect(updateBankSettings).toHaveBeenCalledWith(
        [
          { key: 'security.password.minLength', value: 14, expectedVersion: 0 },
          { key: 'security.lockout.allowedAttempts', value: 5, expectedVersion: 3 },
        ],
        'Audit finding 7'
      )
    );
    expect(await screen.findByText('0 applied, 2 sent for approval')).toBeInTheDocument();
    // Re-read so versions and Pending badges are current.
    expect(getBankSettings).toHaveBeenCalledTimes(2);
  });

  it('reports an operational change as applied', async () => {
    updateBankSettings.mockResolvedValue({
      applied: [{ ...MIN_CONFIDENCE, value: 0.8, source: 'BANK', version: 1 }],
      pendingChange: null,
    });
    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: 'AI & extraction' }));

    await typeInto('Code extraction minimum confidence', '0.8');
    // Operational: no approval note is asked for.
    expect(screen.queryByLabelText(/Note for the approver/)).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Save AI & extraction' }));

    await waitFor(() =>
      expect(updateBankSettings).toHaveBeenCalledWith(
        [{ key: 'extraction.code.minConfidence', value: 0.8, expectedVersion: 0 }],
        undefined
      )
    );
    expect(await screen.findByText('1 applied, 0 sent for approval')).toBeInTheDocument();
  });

  it('shows a guardrail refusal under the field it names and keeps the draft', async () => {
    const refusal = Object.assign(
      new Error('Password minimum length must be between 12 and 128 (got 11).'),
      {
        status: 400,
        code: 'SETTING_OUT_OF_RANGE',
        body: { code: 'SETTING_OUT_OF_RANGE', key: 'security.password.minLength' },
      }
    );
    updateBankSettings.mockRejectedValue(refusal);
    renderPage();

    await typeInto('Password minimum length', '11');
    await userEvent.click(screen.getByRole('button', { name: 'Save Sign-in & passwords' }));

    const row = screen.getByTestId('setting-security.password.minLength');
    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'must be between 12 and 128'
    );
    expect(within(row).getByLabelText('Password minimum length')).toHaveValue(11);
    // Nothing was saved, so nothing was re-read.
    expect(getBankSettings).toHaveBeenCalledTimes(1);
  });

  it('explains a version conflict', async () => {
    updateBankSettings.mockRejectedValue(
      Object.assign(new Error('Allowed sign-in attempts changed meanwhile.'), {
        status: 409,
        code: 'SETTING_VERSION_CONFLICT',
        body: { key: 'security.lockout.allowedAttempts' },
      })
    );
    renderPage();

    await typeInto('Allowed sign-in attempts', '4');
    await userEvent.click(screen.getByRole('button', { name: 'Save Sign-in & passwords' }));

    const row = screen.getByTestId('setting-security.lockout.allowedAttempts');
    expect(await within(row).findByRole('alert')).toHaveTextContent('reload to see');
  });

  it('resets a bank value to the inherited one (value null)', async () => {
    updateBankSettings.mockResolvedValue({ applied: [], pendingChange: { pendingChangeId: 9, status: 'REQUESTED', payload: { changes: [{}] } } });
    renderPage();

    const row = await screen.findByTestId('setting-security.lockout.allowedAttempts');
    await userEvent.click(within(row).getByRole('button', { name: /^Reset to inherited/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Save Sign-in & passwords' }));

    await waitFor(() =>
      expect(updateBankSettings).toHaveBeenCalledWith(
        [{ key: 'security.lockout.allowedAttempts', value: null, expectedVersion: 3 }],
        undefined
      )
    );
  });

  it('blocks saving a malformed value', async () => {
    renderPage();
    await typeInto('Password minimum length', '1.5');

    expect(screen.getByRole('button', { name: 'Save Sign-in & passwords' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('whole number');
  });

  it('says so when the settings cannot be loaded', async () => {
    getBankSettings.mockRejectedValue(new Error('The platform bank uses /api/platform/settings.'));
    renderPage();
    expect(await screen.findByText(/platform bank uses/)).toBeInTheDocument();
  });
});

describe('SettingsPage (platform)', () => {
  beforeEach(() => {
    getBankSettings.mockReset();
    getPlatformSettings.mockReset().mockResolvedValue({
      layer: 'PLATFORM',
      revision: 1,
      groups: [
        {
          id: 'signIn',
          label: 'Sign-in & passwords',
          settings: [{ ...MIN_LENGTH, bankMin: null, bankMax: null, hasBankBounds: true }],
        },
      ],
    });
    updatePlatformSettings.mockReset().mockResolvedValue({
      applied: [],
      pendingChange: { pendingChangeId: 3, status: 'REQUESTED', payload: { changes: [{}] } },
    });
  });

  it('edits the bank bounds and sends a bounds-only change without a value', async () => {
    renderPage('platform');

    expect(await screen.findByRole('heading', { name: 'Platform settings' })).toBeInTheDocument();
    await userEvent.type(
      screen.getByLabelText('Password minimum length: bank minimum'),
      '14'
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save Sign-in & passwords' }));

    await waitFor(() =>
      expect(updatePlatformSettings).toHaveBeenCalledWith(
        [{ key: 'security.password.minLength', expectedVersion: 0, bankMin: 14 }],
        undefined
      )
    );
    expect(await screen.findByText('0 applied, 1 sent for approval')).toBeInTheDocument();
    expect(getBankSettings).not.toHaveBeenCalled();
  });
});

describe('SettingsPage (platform, one bank)', () => {
  const FLAG = view({
    key: 'integration.auth.allowApiKeys',
    group: 'integrations',
    type: 'BOOLEAN',
    label: 'Allow API keys',
    value: true,
    allowed: ['true', 'false'],
    platformControlled: true,
  });

  beforeEach(() => {
    getBankSettings.mockReset();
    getBankSettingsForPlatform.mockReset().mockResolvedValue({
      layer: 'BANK_BY_PLATFORM',
      revision: 2,
      groups: [{ id: 'integrations', label: 'Integrations', settings: [FLAG] }],
    });
    updateBankSettingsForPlatform.mockReset().mockResolvedValue({
      applied: [],
      pendingChange: { pendingChangeId: 9, status: 'REQUESTED', payload: { changes: [{}] } },
    });
  });

  it("loads and saves the bank's platform-set values under the bank's id", async () => {
    render(
      <MemoryRouter initialEntries={['/platform/banks/42/settings']}>
        <ToastProvider>
          <Routes>
            <Route
              path="/platform/banks/:id/settings"
              element={<SettingsPage mode="platformBank" />}
            />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    );

    expect(
      await screen.findByRole('heading', { name: 'Settings set by the platform' })
    ).toBeInTheDocument();
    expect(getBankSettingsForPlatform).toHaveBeenCalledWith('42');
    const row = screen.getByTestId('setting-integration.auth.allowApiKeys');
    expect(within(row).queryByText('Set by platform')).toBeNull();
    await userEvent.click(within(row).getByRole('switch', { name: 'Allow API keys' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save Integrations' }));

    await waitFor(() =>
      expect(updateBankSettingsForPlatform).toHaveBeenCalledWith(
        '42',
        [{ key: 'integration.auth.allowApiKeys', expectedVersion: 0, value: false }],
        undefined
      )
    );
    expect(await screen.findByText('0 applied, 1 sent for approval')).toBeInTheDocument();
    expect(getBankSettings).not.toHaveBeenCalled();
  });
});
