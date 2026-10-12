import { api } from './client';

// What is running (ws24, system:info — every signed-in human role).
//   getVersion() -> {version, commit, buildTime, schemaVersion, deployedAt, environment}
//   getReleaseNotes(limit) -> {releases:[{version, date, sections:{Added:[…], Changed:[…], …}}]}
// Every field of the version may be null (a build without metadata).

// v1.2.0: memoized for the session — the banner, the profile's "About" card
// and the admin dashboard all ask, and the answer only changes on a deploy.
// `fresh: true` (the banner's periodic check) asks the server again and
// replaces the memo; a failed call is never memoized.
let versionPromise = null;

export function getVersion({ fresh = false } = {}) {
  if (fresh || !versionPromise) {
    const promise = api.get('/api/system/version').catch((err) => {
      if (versionPromise === promise) versionPromise = null;
      throw err;
    });
    versionPromise = promise;
  }
  return versionPromise;
}

/** Test hook: forget the memoized version. */
export function resetVersionCache() {
  versionPromise = null;
}

export const getReleaseNotes = (limit = 5) =>
  api.get(`/api/system/release-notes?limit=${encodeURIComponent(limit)}`);
