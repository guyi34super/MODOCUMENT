import { useCallback, useEffect, useRef, useState } from 'react';
import { saveValues } from '../../api/doccreation';

/** How long typing pauses before the values are saved. */
export const AUTOSAVE_MS = 600;

/**
 * The builder's autosave queue: changed keys wait until typing pauses, then
 * go out as one merge (PUT values merges server-side), one request at a time.
 * A failed batch goes back into the queue and is retried with the next change
 * or flush.
 *
 * - `queue(key, value)` adds a change and restarts the pause timer;
 * - `flush()` sends whatever waits now and resolves to `true` when nothing is
 *   left unsaved, `false` when the save failed (so Save / Generate can stop);
 * - `pendingValues()` is what has not reached the server yet (kept on top of
 *   a server copy);
 * - `saveState`: idle | pending | saving | saved | error.
 *
 * `onSaved(dto)` receives the server's copy after each save and `onError(err)`
 * a failed save. Leaving the page sends whatever is still waiting.
 */
export default function useAutosave(id, { onSaved, onError, delay = AUTOSAVE_MS } = {}) {
  const [saveState, setSaveState] = useState('idle');
  const pending = useRef({});
  const timer = useRef(null);
  const inflight = useRef(null);
  const callbacks = useRef({ onSaved, onError });
  callbacks.current = { onSaved, onError };

  const flush = useCallback(async () => {
    clearTimeout(timer.current);
    timer.current = null;
    if (inflight.current) await inflight.current;
    const batch = pending.current;
    if (Object.keys(batch).length === 0) return true;
    pending.current = {};
    setSaveState('saving');
    const request = saveValues(id, batch)
      .then((dto) => {
        callbacks.current.onSaved?.(dto);
        setSaveState(Object.keys(pending.current).length ? 'pending' : 'saved');
        return true;
      })
      .catch((err) => {
        pending.current = { ...batch, ...pending.current };
        setSaveState('error');
        callbacks.current.onError?.(err);
        return false;
      })
      .finally(() => {
        inflight.current = null;
      });
    inflight.current = request;
    return request;
  }, [id]);

  const flushRef = useRef(flush);
  flushRef.current = flush;

  const queue = useCallback(
    (key, value) => {
      pending.current = { ...pending.current, [key]: value };
      setSaveState('pending');
      clearTimeout(timer.current);
      timer.current = setTimeout(() => flushRef.current(), delay);
    },
    [delay]
  );

  const pendingValues = useCallback(() => pending.current, []);

  // Leaving the page: send whatever is still waiting.
  useEffect(
    () => () => {
      clearTimeout(timer.current);
      const batch = pending.current;
      if (Object.keys(batch).length) saveValues(id, batch).catch(() => {});
      pending.current = {};
    },
    [id]
  );

  return { queue, flush, saveState, pendingValues };
}
