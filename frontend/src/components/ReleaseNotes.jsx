import { useEffect, useState } from 'react';
import { getReleaseNotes } from '../api/system';
import Modal from './Modal';
import { Button, ErrorBanner, Spinner } from './ui';

// Keep a Changelog section order (ReleaseNotesParser.SECTIONS); anything else
// the server sends is listed after these.
const SECTION_ORDER = ['Added', 'Changed', 'Deprecated', 'Removed', 'Fixed', 'Security'];

function orderedSections(sections = {}) {
  const names = Object.keys(sections);
  return [
    ...SECTION_ORDER.filter((name) => names.includes(name)),
    ...names.filter((name) => !SECTION_ORDER.includes(name)),
  ];
}

/** One release's notes. Items are plain text from CHANGELOG.md (rendered escaped). */
export function ReleaseNotesEntry({ release, headingLevel = 3 }) {
  const Heading = `h${headingLevel}`;
  return (
    <section className="mb-4 last:mb-0" aria-label={`Release ${release.version}`}>
      <Heading className="text-base font-semibold text-ink">
        {release.version}
        {release.date && <span className="ml-2 text-sm font-normal text-muted">{release.date}</span>}
      </Heading>
      {orderedSections(release.sections).map((name) => (
        <div key={name} className="mt-2">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted">{name}</p>
          <ul className="mt-1 list-disc space-y-1 pl-5 text-sm text-ink">
            {(release.sections[name] || []).map((item, i) => (
              <li key={i} className="whitespace-pre-line">
                {item}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

/**
 * "What's new in X.Y.Z": the release notes of `version` (the newest ones
 * when that version has no released section yet), from
 * GET /api/system/release-notes.
 */
export function ReleaseNotesModal({ open, version, onClose }) {
  const [releases, setReleases] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    setError('');
    getReleaseNotes(5)
      .then((data) => {
        if (!cancelled) setReleases(data?.releases || []);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message || 'The release notes could not be loaded.');
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  const matching = releases?.filter((release) => release.version === version) || [];
  const shown = matching.length > 0 ? matching : releases || [];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`What's new in ${version}`}
      size="lg"
      footer={
        <Button type="button" variant="secondary" onClick={onClose}>
          Close
        </Button>
      }
    >
      <ErrorBanner message={error} />
      {!releases && !error && <Spinner label="Loading release notes…" />}
      {releases && shown.length === 0 && (
        <p className="text-muted">No release notes are available for this version.</p>
      )}
      {shown.map((release) => (
        <ReleaseNotesEntry key={release.version} release={release} />
      ))}
    </Modal>
  );
}
