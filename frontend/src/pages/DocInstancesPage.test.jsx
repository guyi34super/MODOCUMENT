import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocInstancesPage from './DocInstancesPage';
import { stubImageFetch } from '../test/pdfTestUtils';

import { createInstance, deleteInstance, listBlueprints, listInstances } from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant, version, dpi } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}&version=${version}&dpi=${dpi}`,
  createInstance: vi.fn(),
  deleteInstance: vi.fn(),
  listBlueprints: vi.fn(),
  listInstances: vi.fn(),
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

const BLUEPRINTS = [
  { id: 1, name: 'Loan form', status: 'READY', currentVersion: 2, pageCount: 3, documentCount: 1 },
  { id: 2, name: 'KYC draft', status: 'DRAFT', currentVersion: 1, pageCount: 1, documentCount: 0 },
  { id: 3, name: 'Letter', status: 'READY', currentVersion: 1, pageCount: 1, documentCount: 0 },
];

let fetchMock;

function renderPage() {
  fetchMock = stubImageFetch();
  return render(
    <MemoryRouter>
      <ToastProvider>
        <DocInstancesPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('DocInstancesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listBlueprints.mockResolvedValue(BLUEPRINTS);
    listInstances.mockResolvedValue([]);
  });

  it('offers only READY templates and shows page 1 of the chosen one', async () => {
    renderPage();

    const select = await screen.findByLabelText('Template');
    const options = within(select).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Select a template…', 'Loan form (3 pages)', 'Letter (1 page)']);
    expect(screen.queryByAltText(/Page 1 of/)).not.toBeInTheDocument();

    await userEvent.selectOptions(select, '1');
    expect(await screen.findByAltText('Page 1 of Loan form')).toHaveAttribute('src', 'blob:page');
    expect(fetchMock.mock.calls.map(([path]) => path)).toContain(
      '/api/doccreation/blueprints/1/pages/0?variant=template&version=2&dpi=50'
    );
    expect(screen.getByText('Loan form · page 1 of 3')).toBeInTheDocument();
  });

  it('creates a document on the chosen template and opens it', async () => {
    createInstance.mockResolvedValue({ id: 12 });
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText('Template'), '3');
    await userEvent.type(screen.getByLabelText(/^Title/), 'Welcome letter — J. Doe');
    await userEvent.click(screen.getByRole('button', { name: 'Create document' }));

    expect(createInstance).toHaveBeenCalledWith(3, 'Welcome letter — J. Doe');
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/creation/documents/12'));
  });

  it('lists documents with their template and page count', async () => {
    listInstances.mockResolvedValue([
      {
        instanceId: 7,
        title: 'Loan — Jane',
        blueprintName: 'Loan form',
        status: 'GENERATED',
        pageCount: 4,
        lastGeneratedDate: null,
      },
    ]);
    deleteInstance.mockResolvedValue(null);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    const table = await screen.findByRole('table', { name: 'My documents' });
    expect(within(table).getByRole('link', { name: 'Loan — Jane' })).toHaveAttribute('href', '/creation/documents/7');
    expect(within(table).getByText('4')).toBeInTheDocument();
    expect(within(table).getByText('GENERATED')).toBeInTheDocument();

    await userEvent.click(within(table).getByRole('button', { name: 'Delete Loan — Jane' }));
    expect(deleteInstance).toHaveBeenCalledWith(7);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('says when no template is ready yet', async () => {
    listBlueprints.mockResolvedValue([BLUEPRINTS[1]]);
    renderPage();

    expect(await screen.findByText(/No ready templates yet/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'upload and confirm one first' })).toHaveAttribute(
      'href',
      '/creation/blueprints'
    );
  });
});
