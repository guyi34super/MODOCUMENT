import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Spinner } from '../components/ui';

const ToastContext = createContext(null);

const AUTO_DISMISS_MS = { success: 4000, error: 6000 };

function ToastViewport({ toasts, onDismiss }) {
  if (toasts.length === 0) return null;
  return (
    <div className="fixed right-4 bottom-4 z-50 flex w-80 flex-col gap-2">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role={toast.type === 'error' ? 'alert' : 'status'}
          className={`animate-toast-in flex items-start justify-between gap-3 rounded-md px-4 py-3 text-sm shadow-lg ${
            toast.type === 'success'
              ? 'bg-success-bg text-success-ink'
              : toast.type === 'error'
                ? 'bg-danger-bg text-danger-ink'
                : 'bg-pill text-ink'
          }`}
        >
          <span className="flex items-start gap-2">
            {toast.type === 'loading' && <Spinner inline label="" />}
            <span>{toast.message}</span>
          </span>
          <button
            onClick={() => onDismiss(toast.id)}
            className="cursor-pointer font-bold"
            aria-label="Dismiss notification"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const nextId = useRef(1);
  const timers = useRef(new Map());

  // Clear pending auto-dismiss timers when the provider unmounts, so none
  // fires into an unmounted tree (or a torn-down test environment).
  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const dismiss = useCallback((id) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const scheduleDismiss = useCallback(
    (id, type) => {
      const delay = AUTO_DISMISS_MS[type];
      if (!delay) return;
      const timer = timers.current.get(id);
      if (timer) clearTimeout(timer);
      timers.current.set(id, setTimeout(() => dismiss(id), delay));
    },
    [dismiss]
  );

  const push = useCallback(
    (type, message) => {
      const id = nextId.current++;
      setToasts((current) => [...current, { id, type, message }]);
      scheduleDismiss(id, type);
      return id;
    },
    [scheduleDismiss]
  );

  const update = useCallback(
    (id, { type, message }) => {
      setToasts((current) => current.map((t) => (t.id === id ? { ...t, type, message } : t)));
      scheduleDismiss(id, type);
    },
    [scheduleDismiss]
  );

  const success = useCallback((message) => push('success', message), [push]);
  const error = useCallback((message) => push('error', message), [push]);
  const loading = useCallback((message) => push('loading', message), [push]);

  /**
   * Shows a loading toast while the promise runs, then morphs it into a
   * success or error toast. Rethrows on failure so callers can stop their
   * follow-up work (the error toast is already shown).
   */
  const promise = useCallback(
    async (promiseOrFn, messages) => {
      const id = push('loading', messages.loading || 'Working…');
      try {
        const result = await (typeof promiseOrFn === 'function' ? promiseOrFn() : promiseOrFn);
        const text =
          typeof messages.success === 'function' ? messages.success(result) : messages.success;
        if (text) update(id, { type: 'success', message: text });
        else dismiss(id);
        return result;
      } catch (err) {
        const text =
          (typeof messages.error === 'function' ? messages.error(err) : messages.error) ||
          err.message;
        update(id, { type: 'error', message: text });
        throw err;
      }
    },
    [push, update, dismiss]
  );

  const value = useMemo(
    () => ({ success, error, loading, update, dismiss, promise }),
    [success, error, loading, update, dismiss, promise]
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}
