import { useCallback, useEffect, useMemo, useState } from 'react';
import { getRules, saveRules, toRuleInput } from '../../api/docRules';

/**
 * The saved field rules of a blueprint (GET /rules), shared by the template
 * editor's panels and the Field rules tab. `enabled` false skips the load
 * (a draft template has no rules yet).
 *
 * - `countFor(key)`, `hasRequired(key)`, `knows(key)` (the key is a field of
 *   the saved current version, so rules can be written for it);
 * - `save(ruleInputs)` replaces the set with the loaded revision as base and
 *   flags `conflict` on a 409;
 * - `setRequired(key, on)` adds or removes the field's REQUIRED rule.
 */
export default function useFieldRules(blueprintId, { enabled = true } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);

  const load = useCallback(async () => {
    try {
      const dto = await getRules(blueprintId);
      setData(dto && Array.isArray(dto.rules) ? dto : null);
      setError('');
      return dto;
    } catch (err) {
      setError(err.message);
      return null;
    }
  }, [blueprintId]);

  useEffect(() => {
    if (enabled) load();
    else setData(null);
  }, [enabled, load]);

  const byKey = useMemo(() => {
    const map = new Map();
    for (const rule of data?.rules || []) {
      if (!map.has(rule.regionKey)) map.set(rule.regionKey, []);
      map.get(rule.regionKey).push(rule);
    }
    return map;
  }, [data]);

  const countFor = useCallback((key) => byKey.get(key)?.length || 0, [byKey]);
  const hasRequired = useCallback((key) => (byKey.get(key) || []).some((r) => r.ruleType === 'REQUIRED'), [byKey]);
  const knows = useCallback((key) => Boolean(data?.fields?.some((f) => f.key === key)), [data]);

  const save = useCallback(
    async (rules, baseRevision = data?.revision ?? 0) => {
      setBusy(true);
      try {
        const next = await saveRules(blueprintId, { baseRevision, rules });
        setData(next);
        return next;
      } catch (err) {
        if (err.status === 409) setConflict(true);
        throw err;
      } finally {
        setBusy(false);
      }
    },
    [blueprintId, data]
  );

  /**
   * Adds or removes the REQUIRED rule of several fields at once (`changes`:
   * Map key → on) on top of `base` (default: the loaded rules). Keys the
   * base does not know are skipped. Resolves to the saved RulesDto, or the
   * base when nothing changes.
   */
  const setRequiredMany = useCallback(
    async (changes, base = data) => {
      const known = new Set((base?.fields || []).map((f) => f.key));
      let next = (base?.rules || []).map(toRuleInput);
      let changed = false;
      for (const [key, on] of changes) {
        if (!known.has(key)) continue;
        const has = next.some((r) => r.regionKey === key && r.ruleType === 'REQUIRED');
        if (on && !has) {
          const index = next.findIndex((r) => r.regionKey === key);
          const required = { regionKey: key, ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'MANUAL' };
          next.splice(index < 0 ? next.length : index, 0, required);
          changed = true;
        } else if (!on && has) {
          next = next.filter((r) => !(r.regionKey === key && r.ruleType === 'REQUIRED'));
          changed = true;
        }
      }
      if (!changed) return base;
      return save(next, base?.revision ?? 0);
    },
    [data, save]
  );

  const setRequired = useCallback((key, on) => setRequiredMany(new Map([[key, on]])), [setRequiredMany]);

  return {
    data,
    setData,
    error,
    load,
    busy,
    conflict,
    setConflict,
    countFor,
    hasRequired,
    knows,
    save,
    setRequired,
    setRequiredMany,
  };
}
