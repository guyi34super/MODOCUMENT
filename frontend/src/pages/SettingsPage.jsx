import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { settingsApi } from '../api/settings';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import SettingField, { draftProblem, normalizeDraft, toApiValue } from '../components/SettingField';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';

const COPY = {
  bank: {
    crumbs: [{ label: 'Administration' }, { label: 'Settings' }],
    title: 'Settings',
    subtitle: "Your bank's settings, within the platform's limits",
    intro:
      'Operational settings apply as soon as you save. Security settings go to a second ' +
      'administrator for approval and keep their current value until then. Go-live limits ' +
      'cannot be crossed, and locked settings cannot be changed by anyone.',
  },
  platform: {
    crumbs: [{ label: 'Platform' }, { label: 'Settings' }],
    title: 'Platform settings',
    subtitle: 'Defaults for every bank and the range banks may choose from',
    intro:
      'Values here are what every bank inherits. "Banks may choose" narrows the range a bank ' +
      'administrator can pick, inside the go-live limits. Security values and every range ' +
      'change need a second platform administrator.',
  },
  platformBank: {
    crumbs: (bankId) => [
      { label: 'Platform', to: '/platform' },
      { label: 'Banks', to: '/platform/banks' },
      { label: 'Bank', to: `/platform/banks/${bankId}` },
      { label: 'Settings' },
    ],
    title: 'Settings set by the platform',
    subtitle: "This bank's own values of the settings only the platform may set",
    intro:
      'The bank sees these values but cannot change them. "Reset to inherited" returns the ' +
      'bank to the platform default. Security values need a second platform administrator.',
  },
};

/** One PUT change for a normalized draft (contracts §3). */
function toChange(setting, draft, mode) {
  const change = { key: setting.key, expectedVersion: setting.version };
  if (draft.reset) {
    change.value = null;
  } else if ('value' in draft) {
    change.value = toApiValue(setting.type, draft.value);
  }
  if (mode === 'platform') {
    for (const bound of ['bankMin', 'bankMax']) {
      if (bound in draft) {
        const raw = draft[bound];
        change[bound] = raw === '' || raw === null ? null : toApiValue(setting.type, raw);
      }
    }
  }
  return change;
}

/** Whether saving this draft sends anything to maker/checker. */
function needsApproval(setting, draft, mode) {
  if (setting.requiresApproval) return true;
  return mode === 'platform' && ('bankMin' in draft || 'bankMax' in draft);
}

/**
 * The settings screen (phase 2): one tab per settings group from the API,
 * one SettingField per setting, and a per-tab "Save <group>" that sends
 * only that tab's changed keys (each with the version it was read at). The
 * answer is summarized as "N applied, M sent for approval"; a refusal
 * (SETTING_OUT_OF_RANGE, SETTING_LOCKED, SETTING_VERSION_CONFLICT,
 * SETTING_CHANGE_PENDING, …) is shown under the field it names, and nothing
 * was saved — the server judges a save as a whole.
 *
 * `mode="bank"` (/admin/settings): the caller's bank layer.
 * `mode="platform"` (/platform/settings): the platform layer, with the
 * bank-bounds editors.
 * `mode="platformBank"` (/platform/banks/:id/settings): one customer bank's
 * platform-set settings (e.g. the allowed API-client auth methods), which
 * that bank sees read-only.
 */
export default function SettingsPage({ mode = 'bank' }) {
  const toast = useToast();
  const { id: bankId } = useParams();
  const api = useMemo(() => settingsApi(mode, bankId), [mode, bankId]);
  const copy = COPY[mode] || COPY.bank;
  const crumbs = typeof copy.crumbs === 'function' ? copy.crumbs(bankId) : copy.crumbs;
  const [searchParams, setSearchParams] = useSearchParams();

  const [page, setPage] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [banner, setBanner] = useState('');
  const [drafts, setDrafts] = useState({});
  const [errors, setErrors] = useState({});
  const [comments, setComments] = useState({});
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const fresh = await api.load();
      setPage(fresh);
      setLoadError('');
      // Drafts survive a reload only where they still differ from the server.
      const byKey = new Map();
      for (const group of fresh?.groups || []) {
        for (const setting of group.settings || []) byKey.set(setting.key, setting);
      }
      setDrafts((current) => {
        const kept = {};
        for (const [key, draft] of Object.entries(current)) {
          const setting = byKey.get(key);
          const normalized = setting && !setting.locked ? normalizeDraft(setting, draft) : undefined;
          if (normalized) kept[key] = normalized;
        }
        return kept;
      });
      return fresh;
    } catch (err) {
      setLoadError(err.message);
      return null;
    }
  }, [api]);

  useEffect(() => {
    setPage(null);
    setDrafts({});
    setErrors({});
    load();
  }, [load]);

  const groups = page?.groups || [];
  const requestedTab = searchParams.get('tab');
  const activeGroup = groups.find((group) => group.id === requestedTab) || groups[0];

  const selectTab = (groupId) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', groupId);
    setSearchParams(next, { replace: true });
  };

  const setDraft = (key, draft) => {
    setDrafts((current) => {
      const next = { ...current };
      if (draft) next[key] = draft;
      else delete next[key];
      return next;
    });
    setErrors((current) => {
      if (!(key in current)) return current;
      const next = { ...current };
      delete next[key];
      return next;
    });
  };

  const changedIn = (group) =>
    (group?.settings || []).filter((setting) => drafts[setting.key]);

  const save = async (group) => {
    const changed = changedIn(group);
    if (changed.length === 0) return;
    const changes = changed.map((setting) => toChange(setting, drafts[setting.key], mode));
    setSaving(true);
    setBanner('');
    try {
      const result = await api.save(changes, comments[group.id]);
      const applied = result?.applied?.length ?? 0;
      const sent = result?.pendingChange
        ? result.pendingChange.payload?.changes?.length ?? Math.max(changes.length - applied, 0)
        : 0;
      toast.success(`${applied} applied, ${sent} sent for approval`);
      setDrafts((current) => {
        const next = { ...current };
        changed.forEach((setting) => delete next[setting.key]);
        return next;
      });
      setComments((current) => ({ ...current, [group.id]: '' }));
      setErrors({});
      await load();
    } catch (err) {
      const key = err?.body?.key;
      const known = key && changed.some((setting) => setting.key === key);
      let message = err.message || 'The settings could not be saved.';
      if (err?.code === 'SETTING_VERSION_CONFLICT') {
        message += ' Someone changed it meanwhile — reload to see the current value.';
      }
      if (known) {
        setErrors({ [key]: message });
      } else {
        setBanner(message);
      }
      toast.error(`Nothing was saved: ${message}`);
    } finally {
      setSaving(false);
    }
  };

  const changed = changedIn(activeGroup);
  const blocked = changed.some((setting) => draftProblem(setting, drafts[setting.key]));
  const approvalNeeded = changed.some((setting) =>
    needsApproval(setting, drafts[setting.key], mode)
  );
  const unsavedElsewhere = groups.some(
    (group) => group.id !== activeGroup?.id && changedIn(group).length > 0
  );

  return (
    <div>
      <PageHeader
        crumbs={crumbs}
        title={copy.title}
        subtitle={copy.subtitle}
        actions={
          <Button variant="secondary" onClick={load} disabled={saving}>
            Reload
          </Button>
        }
      />
      <ErrorBanner message={loadError} onDismiss={() => setLoadError('')} />
      <ErrorBanner message={banner} onDismiss={() => setBanner('')} />
      {!page && !loadError && <Spinner label="Loading settings…" />}
      {page && (
        <>
          <p className="mb-4 text-sm text-muted">{copy.intro}</p>
          <div
            role="tablist"
            aria-label="Settings groups"
            className="mb-4 flex flex-wrap gap-1 border-b border-line"
          >
            {groups.map((group) => {
              const selected = group.id === activeGroup?.id;
              const dirty = changedIn(group).length > 0;
              return (
                <button
                  key={group.id}
                  type="button"
                  role="tab"
                  id={`settings-tab-${group.id}`}
                  aria-selected={selected}
                  // Only the selected tab's panel is rendered.
                  aria-controls={selected ? `settings-panel-${group.id}` : undefined}
                  tabIndex={selected ? 0 : -1}
                  onClick={() => selectTab(group.id)}
                  onKeyDown={(event) => {
                    const index = groups.indexOf(group);
                    let target = null;
                    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                      const step = event.key === 'ArrowRight' ? 1 : -1;
                      target = groups[(index + step + groups.length) % groups.length];
                    } else if (event.key === 'Home') {
                      target = groups[0];
                    } else if (event.key === 'End') {
                      target = groups[groups.length - 1];
                    }
                    if (target) {
                      event.preventDefault();
                      selectTab(target.id);
                      document.getElementById(`settings-tab-${target.id}`)?.focus();
                    }
                  }}
                  className={`-mb-px cursor-pointer rounded-t border-b-2 px-3 py-2 text-sm ${
                    selected
                      ? 'border-accent font-medium text-accent'
                      : 'border-transparent text-muted hover:text-ink'
                  }`}
                >
                  {group.label}
                  {dirty && (
                    <>
                      <span
                        aria-hidden="true"
                        className="ml-1.5 inline-block size-1.5 rounded-full bg-amber-500 align-middle"
                      />
                      <span className="sr-only"> (unsaved changes)</span>
                    </>
                  )}
                </button>
              );
            })}
          </div>

          {activeGroup && (
            <div
              role="tabpanel"
              id={`settings-panel-${activeGroup.id}`}
              aria-labelledby={`settings-tab-${activeGroup.id}`}
            >
              <Card padding="compact" className="px-5">
                {(activeGroup.settings || []).length === 0 && (
                  <p className="py-4 text-sm text-muted">No settings in this group.</p>
                )}
                {(activeGroup.settings || []).map((setting) => (
                  <SettingField
                    key={setting.key}
                    setting={setting}
                    mode={mode}
                    draft={drafts[setting.key]}
                    error={errors[setting.key]}
                    disabled={saving}
                    onDraft={(draft) => setDraft(setting.key, draft)}
                  />
                ))}
              </Card>

              <div className="mt-4 space-y-3">
                {approvalNeeded && (
                  <div className="max-w-xl">
                    <Field
                      label="Note for the approver"
                      hint="Optional. Shown with the approval request and kept in the audit trail."
                    >
                      <input
                        className={inputClass}
                        maxLength={500}
                        value={comments[activeGroup.id] || ''}
                        onChange={(e) =>
                          setComments((current) => ({
                            ...current,
                            [activeGroup.id]: e.target.value,
                          }))
                        }
                      />
                    </Field>
                  </div>
                )}
                <div className="flex flex-wrap items-center gap-3">
                  <Button
                    onClick={() => save(activeGroup)}
                    disabled={saving || changed.length === 0 || blocked}
                  >
                    {saving ? 'Saving…' : `Save ${activeGroup.label}`}
                  </Button>
                  <span className="text-xs text-muted">
                    {changed.length === 0
                      ? 'No unsaved changes on this tab.'
                      : `${changed.length} unsaved ${changed.length === 1 ? 'change' : 'changes'}${
                          approvalNeeded ? ' — some need approval' : ''
                        }.`}
                  </span>
                </div>
                {unsavedElsewhere && (
                  <Callout variant="info">
                    Other tabs have unsaved changes; each tab saves on its own.
                  </Callout>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
