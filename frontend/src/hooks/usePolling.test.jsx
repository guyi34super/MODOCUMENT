import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import usePolling from './usePolling';

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does nothing while inactive', () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, false));

    vi.advanceTimersByTime(5000);
    expect(fn).not.toHaveBeenCalled();
  });

  it('calls the function on the interval while active', () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, true));

    vi.advanceTimersByTime(3000);
    expect(fn).toHaveBeenCalledTimes(3);
  });

  it('stops polling when active flips to false (terminal job state)', () => {
    const fn = vi.fn();
    const { rerender } = renderHook(({ active }) => usePolling(fn, 1000, active), {
      initialProps: { active: true },
    });

    vi.advanceTimersByTime(2000);
    expect(fn).toHaveBeenCalledTimes(2);

    rerender({ active: false });
    vi.advanceTimersByTime(5000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('clears the interval on unmount', () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => usePolling(fn, 1000, true));

    unmount();
    vi.advanceTimersByTime(5000);
    expect(fn).not.toHaveBeenCalled();
  });

  it('always invokes the latest callback', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ fn }) => usePolling(fn, 1000, true), {
      initialProps: { fn: first },
    });

    rerender({ fn: second });
    vi.advanceTimersByTime(1000);

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });
});
