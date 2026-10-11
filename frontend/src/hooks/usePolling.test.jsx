import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import usePolling from './usePolling';

let visibility = 'visible';

function setVisibility(state) {
  visibility = state;
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    visibility = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does nothing while inactive', async () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, false));

    await vi.advanceTimersByTimeAsync(5000);
    expect(fn).not.toHaveBeenCalled();
  });

  it('calls the function on the interval while active', async () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(3000);
    expect(fn).toHaveBeenCalledTimes(3);
  });

  it('stops polling when active flips to false (terminal job state)', async () => {
    const fn = vi.fn();
    const { rerender } = renderHook(({ active }) => usePolling(fn, 1000, active), {
      initialProps: { active: true },
    });

    await vi.advanceTimersByTimeAsync(2000);
    expect(fn).toHaveBeenCalledTimes(2);

    rerender({ active: false });
    await vi.advanceTimersByTimeAsync(5000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('clears the timer on unmount', async () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => usePolling(fn, 1000, true));

    unmount();
    await vi.advanceTimersByTimeAsync(5000);
    expect(fn).not.toHaveBeenCalled();
  });

  it('always invokes the latest callback', async () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ fn }) => usePolling(fn, 1000, true), {
      initialProps: { fn: first },
    });

    rerender({ fn: second });
    await vi.advanceTimersByTimeAsync(1000);

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('never overlaps calls: the next one waits for a slow call to settle', async () => {
    let resolveCall;
    const fn = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveCall = resolve;
        })
    );
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(1000);
    expect(fn).toHaveBeenCalledTimes(1);
    // The call is still in flight: no second request however long it takes.
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fn).toHaveBeenCalledTimes(1);

    await act(async () => resolveCall());
    await vi.advanceTimersByTimeAsync(1000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('keeps polling after a failed call', async () => {
    const fn = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValue(undefined);
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(2000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('pauses while the tab is hidden and polls at once when it is shown again', async () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(1000);
    expect(fn).toHaveBeenCalledTimes(1);

    act(() => setVisibility('hidden'));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fn).toHaveBeenCalledTimes(1);

    act(() => setVisibility('visible'));
    await vi.advanceTimersByTimeAsync(0);
    expect(fn).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fn).toHaveBeenCalledTimes(3);
  });

  it('a quick tab switch only waits out the rest of the interval (no extra call)', async () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(400);
    act(() => setVisibility('hidden'));
    await vi.advanceTimersByTimeAsync(200);
    act(() => setVisibility('visible'));
    await vi.advanceTimersByTimeAsync(0);
    expect(fn).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(399);
    expect(fn).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('does not start a timer when mounted in a hidden tab', async () => {
    visibility = 'hidden';
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 1000, true));

    await vi.advanceTimersByTimeAsync(5000);
    expect(fn).not.toHaveBeenCalled();

    act(() => setVisibility('visible'));
    await vi.advanceTimersByTimeAsync(0);
    expect(fn).toHaveBeenCalledTimes(1);
  });
});
