import { describe, expect, it, vi } from 'vitest';
import {
  handlePreloadError,
  installPreloadRecovery,
  RELOAD_KEY,
  RELOAD_WINDOW_MS,
} from './preloadRecovery';

function memoryStorage(initial = {}) {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = String(v);
    },
    data,
  };
}

describe('preload error recovery', () => {
  it('reloads once on a missing chunk and swallows the error', () => {
    const storage = memoryStorage();
    const reload = vi.fn();
    const event = { preventDefault: vi.fn() };

    expect(handlePreloadError(event, { storage, reload, now: () => 1_000_000 })).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.preventDefault).toHaveBeenCalled();
    expect(storage.data[RELOAD_KEY]).toBe('1000000');
  });

  it('does not reload again right after a reload (no loop)', () => {
    const storage = memoryStorage({ [RELOAD_KEY]: '1000000' });
    const reload = vi.fn();
    const event = { preventDefault: vi.fn() };

    expect(handlePreloadError(event, { storage, reload, now: () => 1_005_000 })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  it('recovers again from a later deploy in the same tab', () => {
    const storage = memoryStorage({ [RELOAD_KEY]: '1000000' });
    const reload = vi.fn();
    handlePreloadError({}, { storage, reload, now: () => 1_000_000 + RELOAD_WINDOW_MS + 1 });
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('never reloads when sessionStorage is blocked', () => {
    const storage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {},
    };
    const reload = vi.fn();
    expect(handlePreloadError({}, { storage, reload })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('listens for vite:preloadError on the window', () => {
    const target = new EventTarget();
    const add = vi.spyOn(target, 'addEventListener');
    const remove = vi.spyOn(target, 'removeEventListener');
    const uninstall = installPreloadRecovery(target);
    expect(add).toHaveBeenCalledWith('vite:preloadError', expect.any(Function));
    uninstall();
    expect(remove).toHaveBeenCalledWith('vite:preloadError', expect.any(Function));
  });
});
