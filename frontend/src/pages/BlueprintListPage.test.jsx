import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintListPage from './BlueprintListPage';

import { deleteBlueprint, listBlueprints, uploadBlueprint } from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  listBlueprints: vi.fn(),
  uploadBlueprint: vi.fn(),
  deleteBlueprint: vi.fn(),
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderPage() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <BlueprintListPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('BlueprintListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listBlueprints.mockResolvedValue([]);
  });

  it('auto-opens the upload panel for a first-time user and accepts docx and pdf', async () => {
    renderPage();

    expect(await screen.findByText('Upload a blueprint', { selector: 'h2' })).toBeInTheDocument();
    expect(screen.getByLabelText('File')).toHaveAttribute('accept', '.docx,.pdf');
  });

  it('lists blueprints with their section and document counts', async () => {
    listBlueprints.mockResolvedValue([
      {
        blueprintId: 3,
        name: 'PV Survey Report',
        sectionCount: 6,
        instanceCount: 2,
        companyId: 'mo-intelligence',
        flowKey: 'DEFAULT',
        currentVersion: 3,
      },
    ]);

    renderPage();

    expect(await screen.findByText('PV Survey Report')).toBeInTheDocument();
    expect(screen.getByText('mo-intelligence')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
    expect(screen.getByText('v3')).toBeInTheDocument();
  });

  it('uploads the file and navigates to the new blueprint', async () => {
    uploadBlueprint.mockResolvedValue({ blueprintId: 9, sections: [{}, {}] });
    renderPage();

    const file = new File(['docx-bytes'], 'report.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
    await userEvent.upload(await screen.findByLabelText('File'), file);
    // jsdom never satisfies a required file input, so submit the form directly.
    fireEvent.submit(screen.getByRole('button', { name: 'Upload & break into sections' }).closest('form'));

    expect(uploadBlueprint).toHaveBeenCalledWith(file, undefined, undefined);
    expect(await screen.findByText('Blueprint ready — 2 sections found')).toBeInTheDocument();
    expect(mockNavigate).toHaveBeenCalledWith('/creation/blueprints/9');
  });

  it('shows the backend error when the upload is rejected', async () => {
    uploadBlueprint.mockRejectedValue(new Error('Only .docx and .pdf blueprints are supported.'));
    renderPage();

    const file = new File(['x'], 'report.docx');
    await userEvent.upload(await screen.findByLabelText('File'), file);
    fireEvent.submit(screen.getByRole('button', { name: 'Upload & break into sections' }).closest('form'));

    expect(await screen.findByText('Only .docx and .pdf blueprints are supported.')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
