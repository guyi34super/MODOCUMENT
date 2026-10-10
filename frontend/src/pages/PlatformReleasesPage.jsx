import { useEffect, useState } from 'react';
import { getReleaseNotes } from '../api/system';
import AppVersionCard from '../components/AppVersionCard';
import PageHeader from '../components/PageHeader';
import { ReleaseNotesEntry } from '../components/ReleaseNotes';
import { Card, ErrorBanner, Spinner } from '../components/ui';

/**
 * Platform console: releases (ws24). What runs here (version, commit,
 * environment, schema, deployment time) and the release notes of the
 * newest releases, from the CHANGELOG.md bundled with the running build.
 * Releases ship through release.yml (docs/release/runbook.md); there is no
 * self-update button.
 */
export default function PlatformReleasesPage() {
  const [releases, setReleases] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    getReleaseNotes(20)
      .then((data) => setReleases(data?.releases || []))
      .catch((err) => setError(err.message));
  }, []);

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Platform' }, { label: 'Releases' }]}
        title="Releases"
        subtitle="What is running and what changed"
      />
      <AppVersionCard className="mb-5" />
      <Card>
        <h2 className="mb-3 text-lg font-normal">Release notes</h2>
        <ErrorBanner message={error} />
        {!releases && !error && <Spinner label="Loading release notes…" />}
        {releases && releases.length === 0 && (
          <p className="text-sm text-muted">No released versions are recorded in the changelog.</p>
        )}
        {releases?.map((release) => (
          <ReleaseNotesEntry key={release.version} release={release} />
        ))}
      </Card>
    </div>
  );
}
