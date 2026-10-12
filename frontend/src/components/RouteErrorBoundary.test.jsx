import { lazy, Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import RouteErrorBoundary from './RouteErrorBoundary';

describe('RouteErrorBoundary', () => {
  afterEach(() => vi.restoreAllMocks());

  it('renders its children when nothing fails', () => {
    render(
      <RouteErrorBoundary>
        <p>page body</p>
      </RouteErrorBoundary>
    );
    expect(screen.getByText('page body')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows an alert with a reload button when a lazy chunk fails to load (BUG-09)', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Missing = lazy(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module')));
    render(
      <RouteErrorBoundary>
        <Suspense fallback={<p>loading</p>}>
          <Missing />
        </Suspense>
      </RouteErrorBoundary>
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('This page could not be loaded.');
    expect(screen.getByRole('button', { name: 'Reload page' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go to home' })).toBeInTheDocument();
  });

  it('gives the next page a fresh attempt when its key changes', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Boom = () => {
      throw new Error('boom');
    };
    const { rerender } = render(
      <RouteErrorBoundary key="/a">
        <Boom />
      </RouteErrorBoundary>
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    rerender(
      <RouteErrorBoundary key="/b">
        <p>next page</p>
      </RouteErrorBoundary>
    );
    expect(screen.getByText('next page')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
