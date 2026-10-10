// Playwright configuration for the MoDocument E2E suite.
// The stack (backend + SPA + fake LLM + PostgreSQL) is started OUTSIDE
// Playwright — by e2e/run-local.sh or docker-compose.e2e.yml — and described
// to the suite through E2E_* variables (e2e/README.md).
import { defineConfig, devices } from '@playwright/test';

const isCI = Boolean(process.env.CI);

// The sandbox and the CI image carry a preinstalled Chromium under
// PLAYWRIGHT_BROWSERS_PATH that matches this pinned @playwright/test. If a
// runner ever has a different build, point E2E_CHROMIUM_PATH at its binary
// (e.g. /opt/pw-browsers/chromium) instead of downloading browsers.
const executablePath = process.env.E2E_CHROMIUM_PATH || undefined;

export default defineConfig({
  testDir: './tests',
  // One worker, file order: specs share one stack and one set of accounts
  // (TOTP steps are single-use per account), so they must not interleave.
  workers: 1,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  globalSetup: './global-setup.js',
  outputDir: 'test-results',
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'e2e-report' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    // Desktop width: the sidebar is a permanent column at >= 1024 px.
    viewport: { width: 1440, height: 900 },
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        launchOptions: executablePath ? { executablePath } : {},
      },
    },
  ],
});
