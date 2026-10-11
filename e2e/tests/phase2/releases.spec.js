// Phase 2 — version, release notes, announcements, banner (SE5).
// Acceptance criteria: docs/phase2/test-plan.md §5.5. Live since Wave 2 (ws24).
//
// UI contract (SE5, frontend/src/components/AnnouncementBanner.jsx):
// role=region name="Announcements"; each banner data-testid="announcement-<kind>"
// (info | maintenance | release); dismiss button role=button name="Dismiss"
// (absent on maintenance); "What's new in X.Y.Z" link for admins on RELEASE;
// reload prompt role=alert containing "A new version is available". Version
// on ProfilePage (AppVersionCard) as data-testid="app-version".
import fs from 'node:fs';
import path from 'node:path';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { E2E_ROOT } from '../../lib/env.js';
import { schemaVersion } from '../../lib/db.js';
import { publishAnnouncement, waitForAnnouncements } from '../../lib/phase2.js';
import { BOOTSTRAP, OPERATOR, uniqueName } from '../../lib/seed.js';
import { ApiClient } from '../../lib/api.js';

const SPA_VERSION = JSON.parse(
  fs.readFileSync(path.join(E2E_ROOT, '..', 'frontend', 'package.json'), 'utf8')
).version;

test.describe.configure({ mode: 'serial' });

test.describe('releases and announcements @phase2 @wave2', () => {
  test('AC-REL-02 /api/system/version reports version, commit, schema version and environment', async ({
    page,
    adminApi,
    operatorApi,
  }) => {
    expect((await new ApiClient().request('GET', '/api/system/version')).status).toBe(401);
    for (const api of [adminApi, operatorApi]) {
      const info = await api.get('/api/system/version');
      expect(info).toMatchObject({ version: expect.stringMatching(/^\d+\.\d+\.\d+/) });
      for (const field of ['commit', 'buildTime', 'schemaVersion', 'deployedAt', 'environment']) {
        expect(info, `version.${field}`).toHaveProperty(field);
      }
      expect(String(info.schemaVersion)).toBe(String(await schemaVersion()));
    }

    await openAs(page, OPERATOR(), '/profile');
    await expect(page.getByTestId('app-version')).toHaveText(SPA_VERSION);
    await expect(page.getByText('Database schema')).toBeVisible();
  });

  test('AC-REL-03 release notes are parsed from the changelog', async ({ operatorApi }) => {
    const notes = await operatorApi.get('/api/system/release-notes?limit=3');
    expect(Array.isArray(notes.releases)).toBe(true);
    expect(notes.releases.length).toBeGreaterThan(0);
    expect(notes.releases.length).toBeLessThanOrEqual(3);
    for (const release of notes.releases) {
      expect(release.version).toMatch(/^\d+\.\d+\.\d+|^Unreleased$/i);
      expect(typeof release.sections).toBe('object');
    }
  });

  test('AC-REL-06 platform announcements reach only their audience and window; bank admins cannot create them', async ({
    adminApi,
    operatorApi,
    platformApi,
  }) => {
    test.skip(!platformApi, 'the stack was started without platform admins');
    const denied = await adminApi.request('POST', '/api/platform/announcements', {
      json: { kind: 'INFO', severity: 'INFO', title: 'nope', endsAt: new Date(Date.now() + 3600_000).toISOString() },
    });
    expect(denied.status).toBe(403);

    const mine = uniqueName('E2E info for DEFAULT');
    const other = uniqueName('E2E info for another bank');
    const forUs = await publishAnnouncement(platformApi, {
      title: mine,
      body: '<script>window.__pwned = true</script> plain text only',
      audienceBankCodes: ['DEFAULT'],
    });
    const forThem = await publishAnnouncement(platformApi, {
      title: other,
      body: 'for the platform bank only',
      audienceBankCodes: ['MOI-PLATFORM'],
    });
    try {
      const list = await waitForAnnouncements(operatorApi, (l) => l.some((a) => a.title === mine));
      expect(list.map((a) => a.title)).not.toContain(other);
      expect(list.find((a) => a.title === mine).body).toContain('<script>');
    } finally {
      for (const a of [forUs, forThem]) {
        await platformApi.request('DELETE', `/api/platform/announcements/${a.id}`);
      }
    }
  });

  test('AC-REL-07 maintenance banner is visible to everyone and cannot be dismissed; INFO can be', async ({
    page,
    operatorApi,
    platformApi,
  }) => {
    test.skip(!platformApi, 'the stack was started without platform admins');
    const maintenanceTitle = uniqueName('E2E maintenance');
    const infoTitle = uniqueName('E2E info');
    const maintenance = await publishAnnouncement(platformApi, {
      kind: 'MAINTENANCE',
      severity: 'WARNING',
      title: maintenanceTitle,
      body: 'Planned upgrade <b>not bold</b>',
    });
    const info = await publishAnnouncement(platformApi, { title: infoTitle, body: 'FYI' });
    try {
      await waitForAnnouncements(
        operatorApi,
        (l) => l.some((a) => a.title === maintenanceTitle) && l.some((a) => a.title === infoTitle)
      );
      await openAs(page, OPERATOR(), '/templates');
      const region = page.getByRole('region', { name: 'Announcements' });
      const maintenanceBanner = region.getByTestId('announcement-maintenance').filter({ hasText: maintenanceTitle });
      await expect(maintenanceBanner).toBeVisible();
      await expect(maintenanceBanner.getByText('Maintenance window:')).toBeVisible();
      await expect(maintenanceBanner.getByText('Planned upgrade <b>not bold</b>')).toBeVisible();
      await expect(maintenanceBanner.getByRole('button', { name: 'Dismiss' })).toHaveCount(0);

      const infoBanner = region.getByTestId('announcement-info').filter({ hasText: infoTitle });
      await infoBanner.getByRole('button', { name: 'Dismiss' }).click();
      await expect(infoBanner).toHaveCount(0);

      // Still there on another page and after a reload; the INFO stays dismissed.
      await page.goto('/ingestion');
      await expect(maintenanceBanner).toBeVisible();
      await page.reload();
      await expect(maintenanceBanner).toBeVisible();
      await expect(region.getByTestId('announcement-info').filter({ hasText: infoTitle })).toHaveCount(0);
    } finally {
      for (const a of [maintenance, info]) {
        await platformApi.request('DELETE', `/api/platform/announcements/${a.id}`);
      }
    }
  });

  test('AC-REL-07a admins see "What\'s new in X.Y.Z" on the RELEASE banner', async ({ page, adminApi }) => {
    // The deployment recorder publishes a RELEASE announcement at startup
    // (needs the platform bank, i.e. platform admins in the stack).
    const list = (await adminApi.get('/api/announcements/active')).announcements ?? [];
    const release = list.find((a) => a.kind === 'RELEASE');
    test.skip(!release, 'no RELEASE announcement active (the recorder needs the platform bank)');
    await openAs(page, BOOTSTRAP(), '/');
    await expect(
      page.getByRole('region', { name: 'Announcements' }).getByText(`What's new in ${release.releaseVersion}`)
    ).toBeVisible();
  });

  test('AC-REL-07b the SPA prompts a reload when its version differs from the backend', async ({ page }) => {
    // Simulate a backend upgraded under an open tab: the version endpoint
    // reports a newer release than this bundle.
    await page.route('**/api/system/version', async (route) => {
      const response = await route.fetch();
      const body = await response.json();
      await route.fulfill({ response, json: { ...body, version: '99.0.0' } });
    });
    await openAs(page, OPERATOR(), '/templates');
    const prompt = page.getByRole('alert').filter({ hasText: 'A new version is available' });
    await expect(prompt).toContainText('99.0.0');
    await expect(prompt.getByRole('button', { name: 'Reload' })).toBeVisible();
  });
});
