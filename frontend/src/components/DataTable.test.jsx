import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DataTable from './DataTable';

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status', render: (row) => `[${row.status}]` },
];

describe('DataTable', () => {
  it('shows the empty message when there are no rows', () => {
    render(<DataTable columns={columns} rows={[]} emptyMessage="Nothing here." />);
    expect(screen.getByText('Nothing here.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders headers and plain cell values', () => {
    render(<DataTable columns={columns} rows={[{ name: 'Job 1', status: 'OK' }]} />);
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Job 1')).toBeInTheDocument();
  });

  it('uses custom render functions when provided', () => {
    render(<DataTable columns={columns} rows={[{ name: 'Job 1', status: 'OK' }]} />);
    expect(screen.getByText('[OK]')).toBeInTheDocument();
  });

  it('renders empty string for null/undefined values', () => {
    render(<DataTable columns={[{ key: 'name', label: 'Name' }]} rows={[{ name: null }]} />);
    const cell = screen.getAllByRole('cell')[0];
    expect(cell).toHaveTextContent('');
  });

  it('calls onRowClick with the row when a row is clicked', async () => {
    const onRowClick = vi.fn();
    render(
      <DataTable
        columns={columns}
        rows={[{ name: 'Job 1', status: 'OK' }]}
        onRowClick={onRowClick}
      />
    );
    await userEvent.click(screen.getByText('Job 1'));
    expect(onRowClick).toHaveBeenCalledWith({ name: 'Job 1', status: 'OK' }, 0);
  });

  it('does not call onRowClick when an interactive element inside a cell is clicked', async () => {
    const onRowClick = vi.fn();
    render(
      <DataTable
        columns={[
          { key: 'name', label: 'Name' },
          { key: 'open', label: 'Open', render: () => <button>Open</button> },
        ]}
        rows={[{ name: 'Job 1' }]}
        onRowClick={onRowClick}
      />
    );
    await userEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('rows without onRowClick stay clickable without crashing', async () => {
    render(<DataTable columns={columns} rows={[{ name: 'Job 1', status: 'OK' }]} />);
    await userEvent.click(screen.getByText('Job 1'));
    expect(screen.getByText('Job 1').closest('tr').className).not.toContain('cursor-pointer');
  });

  it('maxHeight caps the wrapper and enables internal vertical scroll', () => {
    const { container } = render(
      <DataTable columns={columns} rows={[{ name: 'Job 1', status: 'OK' }]} maxHeight="60vh" />
    );
    const wrapper = container.firstChild;
    expect(wrapper).toHaveStyle({ maxHeight: '60vh' });
    expect(wrapper.className).toContain('overflow-y-auto');
    screen.getAllByRole('columnheader').forEach((th) => {
      expect(th.className).toContain('sticky');
    });
  });

  it('without maxHeight the header is not sticky and no height cap applies', () => {
    const { container } = render(
      <DataTable columns={columns} rows={[{ name: 'Job 1', status: 'OK' }]} />
    );
    expect(container.firstChild.className).not.toContain('overflow-y-auto');
    screen.getAllByRole('columnheader').forEach((th) => {
      expect(th.className).not.toContain('sticky');
    });
  });

  it('onRowClick still fires with maxHeight set', async () => {
    const onRowClick = vi.fn();
    render(
      <DataTable
        columns={columns}
        rows={[{ name: 'Job 1', status: 'OK' }]}
        maxHeight="40vh"
        onRowClick={onRowClick}
      />
    );
    await userEvent.click(screen.getByText('Job 1'));
    expect(onRowClick).toHaveBeenCalled();
  });
});
