import { useEffect, useRef } from 'react';

/** True while the page is in a background tab (or minimised). */
export function pageHidden() {
  return typeof document !== 'undefined' && document.visibilityState === 'hidden';
}

/**
 * Calls fn every intervalMs, but only while active is true.
 *
 * v1.2.0: a setTimeout chain, not setInterval —
 *  - never overlapping: the next call is scheduled only after fn's promise
 *    settles, so a slow endpoint is never hit by a second poll while the
 *    first is still running;
 *  - paused while the tab is hidden: no timer runs in a background tab; when
 *    it becomes visible again the chain resumes — at once if a full interval
 *    has passed (the data is stale), otherwise after the rest of it (quick
 *    tab switches cost no extra requests);
 *  - torn down as soon as active flips false or the component unmounts, so
 *    idle pages generate zero network traffic.
 *
 * fn's errors are swallowed here (the caller shows them); polling goes on.
 */
export default function usePolling(fn, intervalMs, active) {
  const saved = useRef(fn);

  useEffect(() => {
    saved.current = fn;
  }, [fn]);

  useEffect(() => {
    if (!active) return undefined;
    let stopped = false;
    let timer = null;
    let running = false;
    // When the current wait started: a tab shown again before a full
    // interval has passed only waits out the rest of it.
    let waitStartedAt = Date.now();

    const schedule = (delay = intervalMs) => {
      if (stopped || pageHidden() || timer != null) return;
      timer = setTimeout(run, delay);
    };

    async function run() {
      timer = null;
      if (stopped || running) return;
      if (pageHidden()) return; // resumed by the visibilitychange listener
      running = true;
      try {
        await saved.current();
      } catch {
        // The callback reports its own failures.
      } finally {
        running = false;
      }
      waitStartedAt = Date.now();
      schedule();
    }

    const onVisibility = () => {
      if (stopped) return;
      if (pageHidden()) {
        if (timer != null) clearTimeout(timer);
        timer = null;
      } else if (timer == null && !running) {
        schedule(Math.max(0, intervalMs - (Date.now() - waitStartedAt)));
      }
    };

    document.addEventListener('visibilitychange', onVisibility);
    schedule();
    return () => {
      stopped = true;
      if (timer != null) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [intervalMs, active]);
}
