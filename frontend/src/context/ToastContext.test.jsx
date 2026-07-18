import { act, render, renderHook, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToast } from './ToastContext';

const wrapper = ({ children }) => <ToastProvider>{children}</ToastProvider>;

describe('ToastContext', () => {
  it('shows a success toast and auto-dismisses it', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useToast(), { wrapper });

      act(() => {
        result.current.success('Template created');
      });
      expect(screen.getByRole('status')).toHaveTextContent('Template created');

      act(() => {
        vi.advanceTimersByTime(4000);
      });
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows an error toast with role alert', () => {
    const { result } = renderHook(() => useToast(), { wrapper });

    act(() => {
      result.current.error('Request failed (500)');
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Request failed (500)');
  });

  it('can be dismissed with the close button', async () => {
    const { result } = renderHook(() => useToast(), { wrapper });

    act(() => {
      result.current.success('Saved');
    });
    act(() => {
      screen.getByLabelText('Dismiss notification').click();
    });
    expect(screen.queryByText('Saved')).not.toBeInTheDocument();
  });

  it('promise() morphs loading into success and returns the result', async () => {
    const { result } = renderHook(() => useToast(), { wrapper });

    let resolve;
    const pending = new Promise((r) => {
      resolve = r;
    });

    let outcome;
    act(() => {
      outcome = result.current.promise(pending, {
        loading: 'Saving rule…',
        success: 'Rule saved',
      });
    });
    expect(screen.getByRole('status')).toHaveTextContent('Saving rule…');

    await act(async () => {
      resolve(42);
      await pending;
    });
    expect(screen.getByRole('status')).toHaveTextContent('Rule saved');
    await expect(outcome).resolves.toBe(42);
  });

  it('promise() morphs loading into an error toast with err.message and rethrows', async () => {
    const { result } = renderHook(() => useToast(), { wrapper });

    let outcome;
    await act(async () => {
      outcome = result.current.promise(Promise.reject(new Error('Field name is required.')), {
        loading: 'Adding field…',
      });
      await outcome.catch(() => {});
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Field name is required.');
    await expect(outcome).rejects.toThrow('Field name is required.');
  });

  it('renders toasts for components inside the provider', () => {
    function Probe() {
      const toast = useToast();
      return <button onClick={() => toast.success('From a page')}>go</button>;
    }
    render(<Probe />, { wrapper });
    act(() => {
      screen.getByText('go').click();
    });
    expect(screen.getByRole('status')).toHaveTextContent('From a page');
  });
});
