/**
 * Stale-chunk recovery (v1.2.0 code splitting). A tab opened before a deploy
 * still runs the old index.html, whose lazy route chunks no longer exist on
 * the server (hashed names; nginx answers 404 under /assets/). Vite then
 * fires `vite:preloadError`; reloading fetches the new index.html and its
 * new chunk names.
 *
 * The reload happens at most once per RELOAD_WINDOW_MS per tab
 * (sessionStorage): if the chunk is still missing right after a reload, the
 * error is left to surface instead of reloading in a loop.
 */
export const RELOAD_KEY = 'modoc.chunkReloadAt';
export const RELOAD_WINDOW_MS = 60_000;

export function handlePreloadError(
  event,
  { storage = window.sessionStorage, reload, now = Date.now } = {}
) {
  let last = 0;
  try {
    last = Number(storage.getItem(RELOAD_KEY)) || 0;
    if (now() - last < RELOAD_WINDOW_MS) return false;
    storage.setItem(RELOAD_KEY, String(now()));
  } catch {
    // Storage blocked: never risk a reload loop.
    return false;
  }
  event?.preventDefault?.();
  (reload || (() => window.location.reload()))();
  return true;
}

export function installPreloadRecovery(target = window) {
  const listener = (event) => handlePreloadError(event);
  target.addEventListener('vite:preloadError', listener);
  return () => target.removeEventListener('vite:preloadError', listener);
}
