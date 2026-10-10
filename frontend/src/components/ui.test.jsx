import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Badge, Button, Callout, Card, ErrorBanner, KpiTile, StatCard } from './ui';

describe('Callout', () => {
  it('renders title and children', () => {
    render(
      <Callout variant="success" title="All clear">
        Everything is healthy.
      </Callout>
    );
    expect(screen.getByText('All clear')).toBeInTheDocument();
    expect(screen.getByText('Everything is healthy.')).toBeInTheDocument();
  });

  it('applies the error variant styling', () => {
    const { container } = render(<Callout variant="error">Broken</Callout>);
    expect(container.firstChild.className).toContain('bg-danger-bg');
  });
});

describe('Badge', () => {
  it('shows the status text', () => {
    render(<Badge status="RUNNING" />);
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
  });

  it('renders a dash for a missing status', () => {
    render(<Badge status={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('uses success colors for COMPLETED and danger colors for FAILED', () => {
    render(<Badge status="COMPLETED" />);
    render(<Badge status="FAILED" />);
    expect(screen.getByText('COMPLETED').className).toContain('bg-success-bg');
    expect(screen.getByText('FAILED').className).toContain('bg-danger-bg');
  });
});

describe('ErrorBanner', () => {
  it('renders nothing without a message', () => {
    const { container } = render(<ErrorBanner message="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the message and calls onDismiss', async () => {
    const onDismiss = vi.fn();
    render(<ErrorBanner message="Something failed" onDismiss={onDismiss} />);

    expect(screen.getByText('Something failed')).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('Dismiss'));
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});

describe('Button', () => {
  it('fires onClick', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire when disabled', async () => {
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} disabled>
        Save
      </Button>
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('supports a small size variant', () => {
    render(<Button size="sm">Tiny</Button>);
    expect(screen.getByRole('button', { name: 'Tiny' }).className).toContain('text-xs');
  });
});

describe('Card', () => {
  it('uses normal padding by default and compact when requested', () => {
    const { container: normal } = render(<Card>content</Card>);
    const { container: compact } = render(<Card padding="compact">content</Card>);
    expect(normal.firstChild.className).toContain('p-5');
    expect(compact.firstChild.className).toContain('p-3');
  });
});

describe('KpiTile', () => {
  it('renders label, value and hint like a StatCard, neutral by default', () => {
    render(<KpiTile label="Users" value={12} hint="2 disabled" />);
    expect(screen.getByText('Users')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('2 disabled')).toBeInTheDocument();
    expect(screen.getByText('12').closest('[data-tone]')).toHaveAttribute(
      'data-tone',
      'neutral'
    );
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('colours the value and edge by tone', () => {
    render(<KpiTile label="Admins without MFA" value={1} tone="danger" />);
    render(<KpiTile label="Locked" value={3} tone="warning" />);
    render(<KpiTile label="Healthy" value={0} tone="success" />);
    expect(screen.getByText('1').className).toContain('text-danger-ink');
    expect(screen.getByText('3').className).toContain('text-amber-800');
    expect(screen.getByText('0').className).toContain('text-success-ink');
    expect(screen.getByText('1').closest('[data-tone]').className).toContain(
      'border-l-danger-ink'
    );
  });

  it('becomes a link to the page behind the number when given `to`', () => {
    render(
      <MemoryRouter>
        <KpiTile label="Pending approvals" value={4} to="/admin/changes" tone="warning" />
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: /Pending approvals\s*4/ });
    expect(link).toHaveAttribute('href', '/admin/changes');
  });

  it('shows a dash when the value is not known', () => {
    render(<KpiTile label="Parsed by code" value={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});

describe('StatCard', () => {
  it('renders label, value and hint', () => {
    render(<StatCard label="Templates" value={4} hint="Defined templates" />);
    expect(screen.getByText('Templates')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('Defined templates')).toBeInTheDocument();
  });
});
