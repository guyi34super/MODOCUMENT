import { api } from './client';

// What is running (ws24, system:info — every signed-in human role).
//   getVersion() -> {version, commit, buildTime, schemaVersion, deployedAt, environment}
//   getReleaseNotes(limit) -> {releases:[{version, date, sections:{Added:[…], Changed:[…], …}}]}
// Every field of the version may be null (a build without metadata).

export const getVersion = () => api.get('/api/system/version');

export const getReleaseNotes = (limit = 5) =>
  api.get(`/api/system/release-notes?limit=${encodeURIComponent(limit)}`);
