import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import useAutosave from './useAutosave';
import { saveValues } from '../../api/doccreation';

vi.mock('../../api/doccreation', () => ({ saveValues: vi.fn() }));

describe('useAutosave', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('sends the queued changes as one merge and reports success', async () => {
    saveValues.mockResolvedValue({ id: 5, values: { a: '1', b: '2' } });
    const onSaved = vi.fn();
    const { result } = renderHook(() => useAutosave('5', { onSaved, delay: 10_000 }));
    act(() => {
      result.current.queue('a', '1');
      result.current.queue('b', '2');
    });
    expect(result.current.saveState).toBe('pending');
    expect(result.current.pendingValues()).toEqual({ a: '1', b: '2' });

    let ok;
    await act(async () => {
      ok = await result.current.flush();
    });
    expect(ok).toBe(true);
    expect(saveValues).toHaveBeenCalledWith('5', { a: '1', b: '2' });
    expect(onSaved).toHaveBeenCalledWith({ id: 5, values: { a: '1', b: '2' } });
    expect(result.current.saveState).toBe('saved');
    // Nothing waiting: a flush succeeds without a request.
    await act(async () => {
      ok = await result.current.flush();
    });
    expect(ok).toBe(true);
    expect(saveValues).toHaveBeenCalledTimes(1);
  });

  it('reports a failed save (so Save can stop) and keeps the batch for the next try', async () => {
    saveValues.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ id: 5 });
    const onError = vi.fn();
    const { result } = renderHook(() => useAutosave('5', { onError, delay: 10_000 }));
    act(() => result.current.queue('a', '1'));

    let ok;
    await act(async () => {
      ok = await result.current.flush();
    });
    expect(ok).toBe(false);
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'offline' }));
    expect(result.current.saveState).toBe('error');
    expect(result.current.pendingValues()).toEqual({ a: '1' });

    await act(async () => {
      ok = await result.current.flush();
    });
    expect(ok).toBe(true);
    expect(saveValues).toHaveBeenLastCalledWith('5', { a: '1' });
  });

  it('saves on its own after the pause, and sends what waits when the page closes', async () => {
    vi.useFakeTimers();
    try {
      saveValues.mockResolvedValue({ id: 5 });
      const { result, unmount } = renderHook(() => useAutosave('5', { delay: 600 }));
      act(() => result.current.queue('a', '1'));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(600);
      });
      expect(saveValues).toHaveBeenCalledWith('5', { a: '1' });

      act(() => result.current.queue('b', '2'));
      unmount();
      expect(saveValues).toHaveBeenLastCalledWith('5', { b: '2' });
    } finally {
      vi.useRealTimers();
    }
  });
});
