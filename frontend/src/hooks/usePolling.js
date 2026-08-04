import { useEffect, useRef } from 'react';

/**
 * Calls fn every intervalMs, but only while active is true. The interval is
 * torn down as soon as active flips false or the component unmounts, so idle
 * pages generate zero network traffic.
 */
export default function usePolling(fn, intervalMs, active) {
  const saved = useRef(fn);

  useEffect(() => {
    saved.current = fn;
  }, [fn]);

  useEffect(() => {
    if (!active) return undefined;
    const id = setInterval(() => {
      saved.current();
    }, intervalMs);
    return () => clearInterval(id);
  }, [intervalMs, active]);
}
