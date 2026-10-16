import { useCallback, useRef } from 'react';
import { getRules } from '../../api/docRules';

/**
 * The properties panel's quick Required toggle on top of the field rules
 * (rules are the single source of truth, contract §3 "Region required flag"):
 *
 * - a field the saved rules know: the toggle writes or deletes its REQUIRED
 *   rule right away (PUT /rules with the base revision) and mirrors it in
 *   `region.required` without an undo step;
 * - a new or renamed field: the toggle sets `region.required` and waits;
 *   `takeChanges()` hands the waiting toggles to `sync()` after the layout
 *   is saved, when the field exists.
 *
 * `enabled` false (a draft template, no rules yet) leaves the toggle on
 * `region.required` alone.
 */
export default function useRequiredToggle({ editor, fieldRules, blueprintId, enabled, readOnly, onEditRules, onError, onToggled }) {
  const pending = useRef(new Map());

  const known = useCallback(
    (region) =>
      Boolean(enabled && fieldRules.data && fieldRules.knows(region.key) && !pending.current.has(region._uid)),
    [enabled, fieldRules]
  );

  const isRequired = useCallback(
    (region) => (known(region) ? fieldRules.hasRequired(region.key) : Boolean(region.required)),
    [known, fieldRules]
  );

  /** The `rules` prop of RegionPropertiesPanel for `region` (null without rules). */
  const rulesFor = (region) => {
    if (!enabled || !fieldRules.data || !region) return null;
    const isKnown = known(region);
    return {
      count: isKnown ? fieldRules.countFor(region.key) : 0,
      required: isRequired(region),
      busy: fieldRules.busy,
      note: isKnown ? null : 'A new or renamed field: Required becomes a rule when you save the layout.',
      onEditRules: isKnown && onEditRules ? () => onEditRules(region) : null,
      onToggleRequired: async (on) => {
        if (readOnly) return;
        if (!isKnown) {
          pending.current.set(region._uid, on);
          editor.update(region._uid, { required: on });
          return;
        }
        try {
          await fieldRules.setRequired(region.key, on);
          // The column mirrors the rule (kept for rollback): not an unsaved change.
          editor.quietly((list) => list.map((r) => (r._uid === region._uid ? { ...r, required: on } : r)));
          onToggled?.(region, on);
        } catch (err) {
          if (err.status !== 409) onError?.(err);
        }
      },
    };
  };

  /**
   * The waiting toggles as { key → on } for `regions` (their keys at save
   * time), plus every required field when `allRequired` (a confirmed
   * draft); clears the waiting list.
   */
  const takeChanges = useCallback((regions, { allRequired = false } = {}) => {
    const changes = new Map();
    for (const r of regions) {
      if (pending.current.has(r._uid)) changes.set(r.key, pending.current.get(r._uid));
      else if (allRequired && r.required) changes.set(r.key, true);
    }
    pending.current = new Map();
    return changes;
  }, []);

  const reset = useCallback(() => {
    pending.current = new Map();
  }, []);

  /** Writes `changes` as REQUIRED rules on top of freshly read rules; resolves to the saved RulesDto. */
  const sync = useCallback(
    async (changes) => {
      const base = await getRules(blueprintId);
      const result = await fieldRules.setRequiredMany(changes, base);
      fieldRules.setData(result);
      return result;
    },
    [blueprintId, fieldRules]
  );

  return { rulesFor, isRequired, takeChanges, reset, sync };
}
