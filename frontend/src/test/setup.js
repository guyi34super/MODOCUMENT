import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, expect, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { a11yProblems } from './a11y.js';

/**
 * Phase-2 screens are held to the targeted accessibility rules of a11y.js
 * in EVERY test of these files: after each test, whatever the test left on
 * screen (a loaded page, an open dialog, a wizard step, an error) must pass,
 * so a new state that breaks a rule fails the test that reaches it.
 */
const A11Y_GATED = new Set([
  'AdminDashboardPage.test.jsx',
  'AdminUsersPage.test.jsx',
  'AnnouncementBanner.test.jsx',
  'AuditLogPage.test.jsx',
  'BlueprintDetailPage.test.jsx',
  'BlueprintDetailPage.sources.test.jsx',
  'BlueprintDetailPage.rules.test.jsx',
  'BlueprintDetailPage.steps.test.jsx',
  'BlueprintListPage.test.jsx',
  'ConfirmDialog.test.jsx',
  'DataMappingListPage.test.jsx',
  'BuildFromFlowDialog.test.jsx',
  'DataValidationPage.test.jsx',
  'DocBuilderPage.test.jsx',
  'DocBuilderPage.checks.test.jsx',
  'DocBuilderPage.fill.test.jsx',
  'DocBuilderPage.layout.test.jsx',
  'DocBuilderPage.steps.test.jsx',
  'DocBuilderPage.validation.test.jsx',
  'DocInstancesPage.test.jsx',
  'FieldList.test.jsx',
  'FieldMatcher.test.jsx',
  'IntegrationAgentsPage.test.jsx',
  'IntegrationApiClientsPage.test.jsx',
  'IntegrationConnectionsPage.test.jsx',
  'IntegrationEgressRulesPage.test.jsx',
  'IntegrationFlowBuilderPage.test.jsx',
  'IntegrationFlowsPage.test.jsx',
  'IntegrationRunDetailPage.test.jsx',
  'IntegrationRunsPage.test.jsx',
  'IntegrationsOverviewPage.test.jsx',
  'Layout.test.jsx',
  'Modal.test.jsx',
  'RegionBox.test.jsx',
  'RegionPropertiesPanel.test.jsx',
  'PasswordChangedNotice.test.jsx',
  'PendingChangesPage.test.jsx',
  'PlatformAnnouncementsPage.test.jsx',
  'PlatformBankDetailPage.test.jsx',
  'PlatformBanksPage.test.jsx',
  'PlatformOverviewPage.test.jsx',
  'SettingField.test.jsx',
  'SettingsPage.test.jsx',
  'Stepper.test.jsx',
  'ValidationPanel.test.jsx',
]);

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  const file = expect.getState().testPath?.split('/').pop();
  try {
    if (A11Y_GATED.has(file)) {
      expect(a11yProblems(document.body), 'accessibility problems (src/test/a11y.js)').toEqual([]);
    }
  } finally {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  }
});
