import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintListPage, { suggestedFlow } from './BlueprintListPage';

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

const pdf = (name) => new File(['%PDF-1.7'], name, { type: 'application/pdf' });
const submit = () =>
  // jsdom never satisfies a required file input, so submit the form directly.
  fireEvent.submit(screen.getByRole('button', { name: 'Upload and review' }).closest('form'));

describe('BlueprintListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listBlueprints.mockResolvedValue([]);
  });

  it('auto-opens the upload panel for a first-time user and accepts PDF only', async () => {
    renderPage();

    expect(await screen.findByText('Upload a template', { selector: 'h2' })).toBeInTheDocument();
    expect(screen.getByLabelText('PDF file')).toHaveAttribute('accept', '.pdf,application/pdf');
    expect(screen.getByRole('radio', { name: /Detect automatically/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /Blank template/ })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: /Filled document — remove the data/ })).not.toBeChecked();
  });

  it('rejects a file that is not a PDF before uploading', async () => {
    renderPage();
    const input = await screen.findByLabelText('PDF file');
    // applyAccept: false lets the test hand over a file the picker would hide.
    await userEvent.setup({ applyAccept: false }).upload(
      input,
      new File(['docx'], 'report.docx', {
        type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      })
    );

    expect(screen.getByRole('alert')).toHaveTextContent('“report.docx” is not a PDF');
    expect(screen.getByRole('button', { name: 'Upload and review' })).toBeDisabled();
    expect(uploadBlueprint).not.toHaveBeenCalled();
  });

  it.each([
    ['loan_filled.pdf', 'WITH_DATA'],
    ['Specimen form.pdf', 'WITH_DATA'],
    ['report_sample.pdf', 'WITH_DATA'],
    ['report-sample.pdf', 'WITH_DATA'],
    ['kyc_mock.pdf', 'WITH_DATA'],
    ['application.pdf', 'AUTO'],
  ])('suggests the flow for %s', (name, flow) => {
    expect(suggestedFlow(name)).toBe(flow);
  });

  it('pre-selects "Filled document" for a filled-looking file name, and the user can change it', async () => {
    renderPage();
    await userEvent.upload(await screen.findByLabelText('PDF file'), pdf('loan_filled.pdf'));

    expect(screen.getByRole('radio', { name: /Filled document — remove the data/ })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: /Blank template/ }));
    expect(screen.getByRole('radio', { name: /Blank template/ })).toBeChecked();
  });

  it('uploads with the chosen flow and opens the editor', async () => {
    uploadBlueprint.mockResolvedValue({
      id: 9,
      status: 'DRAFT',
      flow: 'WITH_DATA',
      regions: [{ key: 'a' }, { key: 'b' }],
      edits: [{ kind: 'REMOVE' }],
    });
    renderPage();

    const file = pdf('loan_filled.pdf');
    await userEvent.upload(await screen.findByLabelText('PDF file'), file);
    await userEvent.type(screen.getByLabelText(/^Name/), 'Loan form');
    submit();

    expect(uploadBlueprint).toHaveBeenCalledWith(file, { name: 'Loan form', companyId: undefined, flow: 'WITH_DATA' });
    expect(
      await screen.findByText('Template draft ready — 2 fields found, 1 value to remove. Review it before saving.')
    ).toBeInTheDocument();
    expect(mockNavigate).toHaveBeenCalledWith('/creation/blueprints/9');
  });

  it('shows the backend error when the upload is rejected', async () => {
    uploadBlueprint.mockRejectedValue(new Error('The PDF is password-protected.'));
    renderPage();

    await userEvent.upload(await screen.findByLabelText('PDF file'), pdf('secret.pdf'));
    submit();

    expect(await screen.findByText('The PDF is password-protected.')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('lists templates with status, version, pages and documents, and deletes one', async () => {
    listBlueprints.mockResolvedValue([
      { id: 3, name: 'Loan form', status: 'READY', currentVersion: 3, pageCount: 4, documentCount: 2, rulesCount: 5, flow: 'BLANK' },
      { id: 4, name: 'KYC', status: 'DRAFT', currentVersion: 1, pageCount: 1, documentCount: 0, rulesCount: 0, flow: 'WITH_DATA' },
    ]);
    deleteBlueprint.mockResolvedValue(null);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    const table = await screen.findByRole('table', { name: 'Document templates' });
    expect(table).toHaveTextContent('Loan form');
    expect(screen.getByText('Ready')).toBeInTheDocument();
    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('v3')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'KYC' })).toHaveAttribute('href', '/creation/blueprints/4');

    await userEvent.click(screen.getByRole('button', { name: 'Delete KYC' }));
    expect(deleteBlueprint).toHaveBeenCalledWith(4);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('shows the number of field rules as a link to the Field rules tab, "None" without rules', async () => {
    listBlueprints.mockResolvedValue([
      { id: 3, name: 'Loan form', status: 'READY', currentVersion: 3, pageCount: 4, documentCount: 2, rulesCount: 5 },
      { id: 4, name: 'KYC', status: 'DRAFT', currentVersion: 1, pageCount: 1, documentCount: 0, rulesCount: 0 },
    ]);
    renderPage();

    const table = await screen.findByRole('table', { name: 'Document templates' });
    expect(within(table).getByRole('columnheader', { name: 'Rules' })).toBeInTheDocument();
    const five = within(table).getByRole('link', { name: 'Field rules of Loan form: 5' });
    expect(five).toHaveTextContent('5');
    expect(five).toHaveAttribute('href', '/creation/blueprints/3?tab=rules');
    const none = within(table).getByRole('link', { name: 'Field rules of KYC: none' });
    expect(none).toHaveTextContent('None');
    expect(none).toHaveAttribute('href', '/creation/blueprints/4?tab=rules');

    // The link is its own target: the row's click (open the template) does not also fire.
    await userEvent.click(five);
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
