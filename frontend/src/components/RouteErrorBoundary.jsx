import { Component } from 'react';
import { Button } from './ui';

/**
 * Catches a page that fails to render, most often a lazy route chunk that is
 * still missing after preloadRecovery's one guarded reload. Without it the
 * rejected import unmounts the whole tree and leaves a blank page. Give it a
 * `key` that changes on navigation so another page gets a fresh attempt.
 */
export default class RouteErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error) {
    // The message only: no props or state that could carry data.
    console.error('Page failed to load:', error?.message ?? error);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div
        role="alert"
        className="rounded-md border-l-4 border-l-danger-ink bg-danger-bg px-4 py-3 text-danger-ink"
      >
        <p className="font-medium">This page could not be loaded.</p>
        <p className="mt-1 text-sm">
          The application may have been updated, or the connection dropped. Reload to get the latest
          version.
        </p>
        <div className="mt-3 flex gap-2">
          <Button size="sm" onClick={() => window.location.reload()}>
            Reload page
          </Button>
          <Button size="sm" variant="secondary" onClick={() => window.location.assign('/')}>
            Go to home
          </Button>
        </div>
      </div>
    );
  }
}
