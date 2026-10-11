import { useCallback, useEffect, useRef, useState } from 'react';
import { getActiveAnnouncements } from '../api/announcements';
import { getToken } from '../api/client';
import { getVersion } from '../api/system';
import { useAuth } from '../context/AuthContext';
import usePolling from '../hooks/usePolling';
import { APP_VERSION } from '../version';
import { ReleaseNotesModal } from './ReleaseNotes';

/** localStorage key: ids of the announcements this browser dismissed. */
export const DISMISSED_KEY = 'modoc.dismissedAnnouncements';

/** How often an open tab re-checks announcements and the backend version. */
export const CHECK_INTERVAL_MS = 5 * 60 * 1000;

const MAX_REMEMBERED = 100;
const ADMIN_ROLES = ['ADMIN', 'PLATFORM_ADMIN'];

/**
 * A background check only runs while the access token stays valid for this
 * long: it must never trigger a token refresh itself, or an idle tab would
 * keep its session alive forever and defeat the idle timeout.
 */
const QUIET_CHECK_MIN_TOKEN_MS = 90_000;

const SEVERITY_STYLES = {
  INFO: 'border-l-accent bg-pill text-ink',
  WARNING: 'border-l-amber-500 bg-amber-50 text-amber-900',
  CRITICAL: 'border-l-danger-ink bg-danger-bg text-danger-ink',
};

const SEVERITY_WORDS = { INFO: 'Information', WARNING: 'Warning', CRITICAL: 'Critical' };

function readDismissed() {
  try {
    const parsed = JSON.parse(localStorage.getItem(DISMISSED_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter(Number.isInteger) : [];
  } catch {
    return [];
  }
}

function rememberDismissed(ids) {
  try {
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids.slice(-MAX_REMEMBERED)));
  } catch {
    // Storage blocked or full: the dismissal lasts until the next reload.
  }
}

/** Whether the stored access token is valid for at least `ms` more (no refresh needed). */
function tokenValidFor(ms) {
  try {
    const payload = JSON.parse(
      atob(getToken().split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))
    );
    return typeof payload?.exp === 'number' && payload.exp * 1000 - Date.now() > ms;
  } catch {
    return false;
  }
}

/** A maintenance time in the reader's own time zone, zone name included. */
export function formatLocal(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso || '';
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
  });
}

/**
 * The announcement strip above every page (ws24), inside Layout:
 *
 * - platform announcements from GET /api/announcements/active, one item per
 *   announcement (data-testid `announcement-<kind>`); title and body are
 *   plain text, rendered escaped;
 * - INFO and RELEASE can be dismissed (remembered per id in this browser);
 *   MAINTENANCE never can, and shows its window in local time;
 * - RELEASE gives admins a "What's new in X.Y.Z" link to the release notes;
 * - "A new version is available" when GET /api/system/version reports a
 *   different version than this bundle (APP_VERSION).
 *
 * Checked on mount and every 5 minutes — the periodic checks only while the
 * access token is still fresh, so they never extend an idle session, and
 * (v1.2.0, usePolling) never in a hidden tab: a tab shown again catches up
 * once the 5 minutes have passed. The mount check shares the session's
 * memoized version (api/system.js); the periodic one asks the server again.
 * Failures are silent: banners are informational.
 */
export default function AnnouncementBanner() {
  const auth = useAuth();
  const isAdmin = ADMIN_ROLES.includes(auth?.user?.role);
  const [announcements, setAnnouncements] = useState([]);
  const [dismissed, setDismissed] = useState(readDismissed);
  const [serverVersion, setServerVersion] = useState(null);
  const [notesFor, setNotesFor] = useState(null);
  const mounted = useRef(true);

  const refresh = useCallback(async (fresh = false) => {
    const [active, version] = await Promise.allSettled([
      getActiveAnnouncements(),
      getVersion({ fresh }),
    ]);
    if (!mounted.current) return;
    if (active.status === 'fulfilled' && Array.isArray(active.value?.announcements)) {
      setAnnouncements(active.value.announcements);
    }
    if (version.status === 'fulfilled') {
      const running = version.value?.version;
      setServerVersion(running && APP_VERSION !== 'dev' && running !== APP_VERSION ? running : null);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    refresh();
    return () => {
      mounted.current = false;
    };
  }, [refresh]);

  const periodicCheck = useCallback(
    () => (tokenValidFor(QUIET_CHECK_MIN_TOKEN_MS) ? refresh(true) : undefined),
    [refresh]
  );
  usePolling(periodicCheck, CHECK_INTERVAL_MS, true);

  const dismiss = (id) => {
    setDismissed((current) => {
      const next = [...current.filter((known) => known !== id), id];
      rememberDismissed(next);
      return next;
    });
  };

  const visible = announcements.filter(
    (a) => a.kind === 'MAINTENANCE' || !a.dismissible || !dismissed.includes(a.id)
  );

  if (visible.length === 0 && !serverVersion) return null;

  return (
    <section role="region" aria-label="Announcements" className="mb-4 space-y-2">
      {serverVersion && (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border-l-4 border-l-accent bg-pill px-4 py-2 text-sm text-ink"
        >
          <span>
            A new version is available ({serverVersion}). Reload the page to use it.
          </span>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="cursor-pointer rounded border border-line bg-white px-3 py-1 text-accent hover:bg-pill/50"
          >
            Reload
          </button>
        </div>
      )}
      {visible.map((a) => {
        const kind = String(a.kind || 'INFO').toLowerCase();
        const canDismiss = a.kind !== 'MAINTENANCE' && a.dismissible;
        return (
          <div
            key={a.id}
            data-testid={`announcement-${kind}`}
            className={`flex items-start justify-between gap-3 rounded-md border-l-4 px-4 py-2 text-sm ${
              SEVERITY_STYLES[a.severity] || SEVERITY_STYLES.INFO
            }`}
          >
            <div className="min-w-0 flex-1">
              <p id={`announcement-title-${a.id}`} className="font-semibold">
                {/* The severity is a colour; say it in words too. */}
                <span className="sr-only">{SEVERITY_WORDS[a.severity] || SEVERITY_WORDS.INFO}: </span>
                {a.title}
              </p>
              {a.body && <p className="mt-0.5 whitespace-pre-line break-words">{a.body}</p>}
              {a.kind === 'MAINTENANCE' && a.maintenanceStartsAt && a.maintenanceEndsAt && (
                <p className="mt-0.5">
                  Maintenance window:{' '}
                  <time dateTime={a.maintenanceStartsAt}>{formatLocal(a.maintenanceStartsAt)}</time>
                  {' – '}
                  <time dateTime={a.maintenanceEndsAt}>{formatLocal(a.maintenanceEndsAt)}</time>
                </p>
              )}
              {a.kind === 'RELEASE' && isAdmin && a.releaseVersion && (
                <a
                  href={`#whats-new-${a.releaseVersion}`}
                  onClick={(event) => {
                    event.preventDefault();
                    setNotesFor(a.releaseVersion);
                  }}
                  className="mt-0.5 inline-block font-medium text-accent underline"
                >
                  What&apos;s new in {a.releaseVersion}
                </a>
              )}
            </div>
            {canDismiss && (
              <button
                type="button"
                aria-label="Dismiss"
                aria-describedby={`announcement-title-${a.id}`}
                title="Dismiss"
                onClick={() => dismiss(a.id)}
                className="-mr-1 cursor-pointer rounded px-1.5 text-lg leading-none opacity-70 hover:opacity-100"
              >
                ×
              </button>
            )}
          </div>
        );
      })}
      <ReleaseNotesModal open={Boolean(notesFor)} version={notesFor || ''} onClose={() => setNotesFor(null)} />
    </section>
  );
}
