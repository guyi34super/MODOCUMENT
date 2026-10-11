import { useEffect, useState } from 'react';
import { getVersion } from '../api/system';
import { APP_VERSION } from '../version';
import { Card } from './ui';

function formatDate(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/**
 * "About MoDocument" (ws24): the version of this SPA bundle
 * (data-testid="app-version") and what the server reports from
 * GET /api/system/version — version, commit, environment, schema version
 * and when this version was deployed. The server half is best effort.
 */
export default function AppVersionCard({ className = '' }) {
  const [server, setServer] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getVersion()
      .then((info) => {
        if (!cancelled) setServer(info);
      })
      .catch(() => {
        /* informational only */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const deployed = formatDate(server?.deployedAt);
  return (
    <Card className={className}>
      <h2 className="mb-1 text-lg font-normal">About MoDocument</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted">Version</dt>
        <dd data-testid="app-version">{APP_VERSION}</dd>
        {server?.version && server.version !== APP_VERSION && (
          <>
            <dt className="text-muted">Server version</dt>
            <dd>{server.version} (reload the page to update this one)</dd>
          </>
        )}
        {server?.commit && (
          <>
            <dt className="text-muted">Build</dt>
            <dd className="font-mono text-xs leading-5">{server.commit}</dd>
          </>
        )}
        {server?.environment && (
          <>
            <dt className="text-muted">Environment</dt>
            <dd>{server.environment}</dd>
          </>
        )}
        {deployed && (
          <>
            <dt className="text-muted">Deployed</dt>
            <dd>{deployed}</dd>
          </>
        )}
        {server?.schemaVersion && (
          <>
            <dt className="text-muted">Database schema</dt>
            <dd>V{server.schemaVersion}</dd>
          </>
        )}
      </dl>
    </Card>
  );
}
