import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
});
