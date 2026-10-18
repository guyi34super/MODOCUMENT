import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocInstancesPage from './DocInstancesPage';
import { stubImageFetch } from '../test/pdfTestUtils';
import { createInstance, downloadInstance, listBlueprints, listInstances } from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page) => `/api/doccreation/blueprints/${id}/pages/${page}`,
  createInstance: vi.fn(),
  deleteInstance: vi.fn(),
  downloadInstance: vi.fn(),
  listBlueprints: vi.fn(),
  listInstances: vi.fn(),
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()),
  useNavigate: () => mockNavigate,
}));

// Data-templates contract §8: BlueprintSummaryDto.validationEnabled is the default for new documents.
const BLUEPRINTS = [
  { id: 1, name: 'Loan form', status: 'READY', currentVersion: 2, pageCount: 1, validationEnabled: true },
  { id: 3, name: 'Letter', status: 'READY', currentVersion: 1, pageCount: 1, validationEnabled: false },
];

function renderPage() {
  stubImageFetch();
  return render(
    <MemoryRouter>
      <ToastProvider>
        <DocInstancesPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

const checkbox = () => screen.getByRole('checkbox', { name: 'Validate this document' });

describe('DocInstancesPage — validation on/off', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listBlueprints.mockResolvedValue(BLUEPRINTS);
    listInstances.mockResolvedValue([]);
    createInstance.mockResolvedValue({ id: 12 });
  });

  it('"Validate this document" starts from the chosen template’s default and is sent with the new document', async () => {
    renderPage();
    const select = await screen.findByLabelText('Template');
    expect(screen.queryByRole('checkbox', { name: 'Validate this document' })).not.toBeInTheDocument();

    await userEvent.selectOptions(select, '1');
    expect(checkbox()).toBeChecked();
    await userEvent.selectOptions(select, '3');
    expect(checkbox()).not.toBeChecked();
    expect(screen.getByText(/The template’s default is off/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/^Title/), 'Letter — Ann');
    await userEvent.click(screen.getByRole('button', { name: 'Create document' }));
    expect(createInstance).toHaveBeenCalledWith(3, 'Letter — Ann', false);
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/creation/documents/12?step=data'));
  });

  it('the user can override the default; choosing another template starts from its default again', async () => {
    renderPage();
    const select = await screen.findByLabelText('Template');
    await userEvent.selectOptions(select, '1');
    await userEvent.click(checkbox());
    expect(checkbox()).not.toBeChecked();
    await userEvent.selectOptions(select, '3');
    await userEvent.selectOptions(select, '1');
    expect(checkbox()).toBeChecked();

    await userEvent.selectOptions(select, '3');
    await userEvent.click(checkbox());
    expect(checkbox()).toBeChecked();
    await userEvent.type(screen.getByLabelText(/^Title/), 'Letter — Bo');
    await userEvent.click(screen.getByRole('button', { name: 'Create document' }));
    expect(createInstance).toHaveBeenCalledWith(3, 'Letter — Bo', true);
  });

  it('shows "Off" in the Validation column and enables Download for a document with validation off', async () => {
    listInstances.mockResolvedValue([
      { instanceId: 7, title: 'Letter — Jane', blueprintName: 'Letter', status: 'DRAFT', validationEnabled: false, validationState: 'OFF', errorCount: 0 },
      { instanceId: 8, title: 'Loan — Joe', blueprintName: 'Loan form', status: 'DRAFT', validationEnabled: true, validationState: 'NOT_CHECKED', errorCount: 0 },
    ]);
    downloadInstance.mockResolvedValue(undefined);
    renderPage();

    const table = await screen.findByRole('table', { name: 'My documents' });
    expect(within(table).getAllByTestId('validation-state').map((n) => n.textContent)).toEqual(['Off', 'Not checked']);
    const offDownload = within(table).getByRole('button', { name: 'Download Letter — Jane' });
    expect(offDownload).toBeEnabled();
    expect(within(table).queryByRole('link', { name: 'Open to fix: Letter — Jane' })).not.toBeInTheDocument();
    expect(within(table).getByRole('button', { name: 'Download Loan — Joe' })).toBeDisabled();

    await userEvent.click(offDownload);
    expect(downloadInstance).toHaveBeenCalledWith(7, 'Letter — Jane.pdf');
  });
});
