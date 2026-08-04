import { useEffect } from 'react';

/**
 * Warns before work in progress is thrown away.
 *
 * The app uses a plain BrowserRouter, so react-router's useBlocker is not
 * available; this covers the two ways unsaved work actually gets lost — a
 * reload or tab close (beforeunload) and a click on any in-app link, caught in
 * the capture phase before the router acts on it. Browser back/forward cannot
 * be intercepted without a data router and remains a known gap.
 */
export default function useUnsavedGuard(dirty, message) {
  useEffect(() => {
    if (!dirty) return undefined;

    const onBeforeUnload = (event) => {
      event.preventDefault();
      event.returnValue = message;
      return message;
    };

    const onClickCapture = (event) => {
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      ) {
        return;
      }
      const anchor = event.target?.closest?.('a[href]');
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return;
      const href = anchor.getAttribute('href');
      // In-page anchors and external schemes are not navigation away from the work.
      if (!href || href.startsWith('#') || /^[a-z][a-z0-9+.-]*:/i.test(href)) return;
      if (href === `${window.location.pathname}${window.location.search}`) return;
      if (!window.confirm(message)) {
        event.preventDefault();
        event.stopPropagation();
      }
    };

    window.addEventListener('beforeunload', onBeforeUnload);
    document.addEventListener('click', onClickCapture, true);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
      document.removeEventListener('click', onClickCapture, true);
    };
  }, [dirty, message]);
}
