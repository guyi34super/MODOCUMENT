import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocInstancesPage from './DocInstancesPage';
import { stubImageFetch } from '../test/pdfTestUtils';

import { createInstance, deleteInstance, downloadInstance, listBlueprints, listInstances } from '../api/doccreation';
import { expectAccessible } from '../test/a11y';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant, version, dpi } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}&version=${version}&dpi=${dpi}`,
  createInstance: vi.fn(),
  deleteInstance: vi.fn(),
  downloadInstance: vi.fn(),
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

    expect(createInstance).toHaveBeenCalledWith(3, 'Welcome letter — J. Doe', true);
    // ws34: a new document opens on the builder's first step, Choose data.
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/creation/documents/12?step=data'));
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

  it('filters the documents by status and shows the Validated badge', async () => {
    listInstances.mockResolvedValue([
      { instanceId: 7, title: 'Loan — Jane', blueprintName: 'Loan form', status: 'VALIDATED', pageCount: 4, lastGeneratedDate: null },
      { instanceId: 8, title: 'Loan — Joe', blueprintName: 'Loan form', status: 'RELEASED', pageCount: 4, lastGeneratedDate: null },
    ]);
    renderPage();

    const table = await screen.findByRole('table', { name: 'My documents' });
    expect(listInstances).toHaveBeenLastCalledWith({});
    const badge = within(table).getByText('VALIDATED');
    expect(badge.className).toContain('bg-teal-100');
    expect(badge.className).not.toBe(within(table).getByText('RELEASED').className);

    const filter = screen.getByLabelText('Status');
    expect(within(filter).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'All',
      'Draft',
      'Validated',
      'Generated',
      'Submitted',
      'Released',
    ]);
    listInstances.mockResolvedValue([]);
    await userEvent.selectOptions(filter, 'VALIDATED');
    await waitFor(() => expect(listInstances).toHaveBeenLastCalledWith({ status: 'VALIDATED' }));
    expect(await screen.findByText('No validated documents.')).toBeInTheDocument();
    // An empty filtered list does not open the "New document" form.
    expect(screen.queryByRole('heading', { name: 'New document' })).not.toBeInTheDocument();
  });

  it('shows the validation of each document and offers Download only for validated ones', async () => {
    listInstances.mockResolvedValue([
      { instanceId: 7, title: 'Loan — Jane', blueprintName: 'Loan form', status: 'VALIDATED', validationState: 'VALIDATED', errorCount: 0, pageCount: 4 },
      { instanceId: 8, title: 'Loan — Joe', blueprintName: 'Loan form', status: 'DRAFT', validationState: 'ERRORS', errorCount: 3, pageCount: 4 },
      { instanceId: 9, title: 'Loan — Ann', blueprintName: 'Loan form', status: 'DRAFT', validationState: 'NOT_CHECKED', errorCount: 0, pageCount: 4 },
      { instanceId: 10, title: 'Loan — Bob', blueprintName: 'Loan form', status: 'GENERATED', validationState: 'RULES_CHANGED', errorCount: 0, pageCount: 4 },
      { instanceId: 11, title: 'Loan — Eve', blueprintName: 'Loan form', status: 'DRAFT', validationState: 'ERRORS', errorCount: 1, pageCount: 4 },
    ]);
    downloadInstance.mockResolvedValue(undefined);
    renderPage();

    const table = await screen.findByRole('table', { name: 'My documents' });
    expect(within(table).getByRole('columnheader', { name: 'Validation' })).toBeInTheDocument();
    expect(within(table).getAllByTestId('validation-state').map((n) => n.textContent)).toEqual([
      'Validated',
      '3 errors',
      'Not checked',
      'Rules changed — save again',
      '1 error',
    ]);

    const jane = within(table).getByRole('button', { name: 'Download Loan — Jane' });
    expect(jane).toBeEnabled();
    expect(within(table).queryByRole('link', { name: 'Open to fix: Loan — Jane' })).not.toBeInTheDocument();
    for (const [title, reason] of [
      ['Loan — Joe', 'Fix its errors and save it first'],
      ['Loan — Ann', 'Save and validate it first'],
      ['Loan — Bob', 'The rules changed — open it and save again'],
    ]) {
      const button = within(table).getByRole('button', { name: `Download ${title}` });
      expect(button).toBeDisabled();
      expect(button).toHaveAccessibleDescription(reason);
      expect(button).toHaveAttribute('title', reason);
    }
    expect(within(table).getByRole('link', { name: 'Open to fix: Loan — Joe' })).toHaveAttribute(
      'href',
      '/creation/documents/8?step=fix'
    );
    expectAccessible();

    await userEvent.click(jane);
    expect(downloadInstance).toHaveBeenCalledWith(7, 'Loan — Jane.pdf');
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(await screen.findByText('Download started')).toBeInTheDocument();
  });
});
