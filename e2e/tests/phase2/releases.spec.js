// Phase 2 — version, release notes, announcements, banner (SE5).
// Acceptance criteria: docs/phase2/test-plan.md §5.5.
//
// UI contract (SE5): AnnouncementBanner in Layout, role=region
// name="Announcements"; each banner data-testid="announcement-<kind>"
// (info | maintenance | release); dismiss button role=button name="Dismiss"
// (absent on maintenance); "What's new in X.Y.Z" link for admins; reload
// prompt role=alert containing "A new version is available". Version shown
// on ProfilePage (or footer) as data-testid="app-version".
import { test } from '../../lib/fixtures.js';

test.describe('releases and announcements @phase2 @wave2', () => {
  test.fixme('AC-REL-02 /api/system/version reports version, commit, schema version and environment', async () => {
    // every human role -> 200; anonymous -> 401; schemaVersion equals the latest
    // flyway_schema_history version (lib/db.js schemaVersion()).
  });

  test.fixme('AC-REL-03 release notes are parsed from the changelog', async () => {});

  test.fixme('AC-REL-06 platform announcements reach only their audience and window; bank admins cannot create them', async () => {});

  test.fixme('AC-REL-07 maintenance banner is visible to everyone and cannot be dismissed', async () => {
    // platform admin publishes MAINTENANCE for the bank -> operator sees it on every page, no Dismiss;
    // INFO banner dismissible; admin sees "What's new in X.Y.Z".
  });

  test.fixme('AC-REL-07b the SPA prompts a reload when its version differs from the backend', async () => {});
});
