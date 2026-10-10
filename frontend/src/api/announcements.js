import { api } from './client';

// Announcements (ws24). Title and body are PLAIN TEXT — render them as React
// text (escaped), never as HTML.
//
// Banner feed (profile:self, every signed-in user):
//   getActiveAnnouncements() -> {announcements:[{id, kind, severity, title, body,
//     startsAt, endsAt, maintenanceStartsAt, maintenanceEndsAt, releaseVersion,
//     dismissible}]}   kind INFO | MAINTENANCE | RELEASE, severity INFO | WARNING | CRITICAL
//
// Platform console (platform:read / platform:announce, PLATFORM_ADMIN only):
//   listPlatformAnnouncements() -> {announcements:[{…, audienceBankCodes|null,
//     archived, status: SCHEDULED|ACTIVE|ENDED|ARCHIVED, createdBy, createdAt, …}]}
//   createAnnouncement(body) / updateAnnouncement(id, body), body =
//     {kind, severity, title, body, audienceBankCodes|null, startsAt, endsAt,
//      maintenanceStartsAt?, maintenanceEndsAt?, releaseVersion?}  (ISO instants)
//   archiveAnnouncement(id)  (DELETE → 204; the row is kept, archived)
// A 400 carries {error, code: "ANNOUNCEMENT_INVALID", message}.

export const getActiveAnnouncements = () => api.get('/api/announcements/active');

export const listPlatformAnnouncements = () => api.get('/api/platform/announcements');

export const createAnnouncement = (body) => api.post('/api/platform/announcements', body);

export const updateAnnouncement = (id, body) =>
  api.put(`/api/platform/announcements/${encodeURIComponent(id)}`, body);

export const archiveAnnouncement = (id) =>
  api.del(`/api/platform/announcements/${encodeURIComponent(id)}`);

// The audience picker's choices: the customer banks' codes, from the ws21
// platform bank registry (platform:read; counts-only BankSummary rows).
export const listAudienceBanks = () =>
  api.get('/api/platform/banks').then((banks) =>
    (Array.isArray(banks) ? banks : [])
      .filter((bank) => bank.kind !== 'PLATFORM')
      .map((bank) => ({ code: bank.code, name: bank.name, status: bank.status }))
  );
