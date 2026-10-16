import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import {
  Badge,
  Button,
  Callout,
  Card,
  ErrorBanner,
  KpiTile,
  Spinner,
  StatCard,
  Switch,
  Tag,
  sharePercents,
} from './ui';

describe('sharePercents', () => {
  it('rounds to whole percentages that add up to 100', () => {
    expect(sharePercents([1, 1, 1])).toEqual([34, 33, 33]);
    expect(sharePercents([6, 3, 1])).toEqual([60, 30, 10]);
    expect(sharePercents([2, 0, 1]).reduce((a, b) => a + b, 0)).toBe(100);
  });

  it('is all zeros for an empty whole', () => {
    expect(sharePercents([0, 0, null])).toEqual([0, 0, 0]);
  });
});

describe('announcements', () => {
  it('a panel spinner is a status region, an inline one is not', () => {
    const { rerender } = render(<Spinner label="Loading runs…" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading runs…');
    rerender(<Spinner inline label="" />);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('an error banner is announced as an alert', () => {
    render(<ErrorBanner message="Request failed (500)" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Request failed (500)');
  });
});

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

  it('gives VALIDATED its own colour, distinct from RELEASED', () => {
    render(<Badge status="VALIDATED" />);
    render(<Badge status="RELEASED" />);
    expect(screen.getByText('VALIDATED').className).toContain('bg-teal-100');
    expect(screen.getByText('RELEASED').className).toContain('bg-success-bg');
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

  it('exposes the E2E hooks: kpi-<metric> on the tile, kpi-value on the number', () => {
    render(
      <MemoryRouter>
        <KpiTile testId="kpi-users-active" label="Active users" value={7} />
        <KpiTile testId="kpi-pending-approvals" label="Pending" value={2} to="/admin/changes" />
      </MemoryRouter>
    );
    expect(
      within(screen.getByTestId('kpi-users-active')).getByTestId('kpi-value')
    ).toHaveTextContent('7');
    expect(screen.getByTestId('kpi-pending-approvals').tagName).toBe('A');
    expect(
      within(screen.getByTestId('kpi-pending-approvals')).getByTestId('kpi-value')
    ).toHaveTextContent('2');
  });
});

describe('Tag', () => {
  it('renders its text with a tone and an explanation', () => {
    render(
      <Tag tone="warning" title="A second administrator must approve a change.">
        Requires approval
      </Tag>
    );
    const tag = screen.getByText('Requires approval');
    expect(tag.className).toContain('bg-amber-100');
    expect(tag).toHaveAttribute('title', 'A second administrator must approve a change.');
  });
});

describe('Switch', () => {
  it('is a named switch that reports the next state', async () => {
    const onChange = vi.fn();
    render(<Switch label="Share with bank" checked={false} onChange={onChange} />);

    const toggle = screen.getByRole('switch', { name: 'Share with bank' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('does nothing when disabled', async () => {
    const onChange = vi.fn();
    render(<Switch label="Locked" checked disabled onChange={onChange} />);
    await userEvent.click(screen.getByRole('switch', { name: 'Locked' }));
    expect(onChange).not.toHaveBeenCalled();
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
