import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { fakeDataTransfer, stubImageFetch } from '../test/pdfTestUtils';

import {
  deleteImage,
  downloadInstance,
  generateInstance,
  getInstance,
  placeImage,
  saveValues,
  submitInstance,
  uploadImages,
} from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant, version, dpi } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}&version=${version}${dpi ? `&dpi=${dpi}` : ''}`,
  imageContentPath: (id, imageId) => `/api/doccreation/instances/${id}/images/${imageId}/content`,
  instancePagePath: (id, page, dpi) => `/api/doccreation/instances/${id}/pages/${page}?dpi=${dpi}`,
  deleteImage: vi.fn(),
  downloadInstance: vi.fn(),
  generateInstance: vi.fn(),
  getInstance: vi.fn(),
  placeImage: vi.fn(),
  saveValues: vi.fn(),
  submitInstance: vi.fn(),
  uploadImages: vi.fn(),
}));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, kind, overrides = {}) => ({
  key,
  label,
  group: 'Applicant',
  kind,
  page: 0,
  fx: 0.1,
  fy: 0.1,
  fw: 0.3,
  fh: 0.02,
  fontName: 'Helvetica',
  fontSize: 10,
  color: '#000000',
  align: 'LEFT',
  maxLines: kind === 'MULTILINE' ? 2 : 1,
  acroFieldName: null,
  required: false,
  sortOrder: 0,
  source: 'DETECTED',
  confidence: 0.9,
  ...overrides,
});

const doc = (overrides = {}) => ({
  id: 5,
  title: 'Loan — Jane',
  status: 'DRAFT',
  blueprintId: 1,
  blueprintName: 'Loan form',
  blueprintVersion: 2,
  blueprintCurrentVersion: 2,
  pages: [
    { index: 0, ...PAGE },
    { index: 1, ...PAGE },
  ],
  regions: [
    region('full_name', 'Full name', 'TEXT', { fy: 0.2, required: true }),
    region('dob', 'Date of birth', 'DATE', { fy: 0.25, sortOrder: 1 }),
    region('amount', 'Amount', 'NUMBER', { group: 'Loan', fy: 0.3, sortOrder: 2, align: 'RIGHT' }),
    region('agree', 'I agree', 'CHECKBOX', { group: 'Loan', fy: 0.35, fw: 0.02, sortOrder: 3 }),
    region('photo_front', 'Front of house', 'IMAGE', { group: 'Photos', fx: 0.6, fy: 0.5, fw: 0.3, fh: 0.2, sortOrder: 4 }),
    region('photo_back', 'Back of house', 'IMAGE', { group: 'Photos', fx: 0.1, fy: 0.5, fw: 0.3, fh: 0.2, sortOrder: 5 }),
    region('notes', 'Notes', 'MULTILINE', { group: 'Remarks', page: 1, fw: 0.2, fh: 0.02, sortOrder: 6 }),
  ],
  values: { full_name: 'Jane', amount: '1000' },
  images: [
    { id: 31, regionKey: 'photo_front', originalName: 'front.jpg' },
    { id: 32, regionKey: null, originalName: 'roof.png' },
  ],
  overflow: [],
  lastGeneratedDate: null,
  pdfAvailable: false,
  reviewerComment: null,
  ...overrides,
});

let fetchMock;

function renderPage() {
  fetchMock = stubImageFetch();
  return render(
    <MemoryRouter initialEntries={['/creation/documents/5']}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/documents/:id" element={<DocBuilderPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const onPage = () => within(screen.getByTestId('page-view'));
/** Waits for the document to load; resolves to the on-page "Full name" input. */
const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');
const panel = () => within(screen.getByRole('region', { name: 'Fields' }));
const fetchedPaths = () => fetchMock.mock.calls.map(([path]) => path);

describe('DocBuilderPage (WYSIWYG builder)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(doc());
    saveValues.mockImplementation(async (id, values) => doc({ values: { ...doc().values, ...values } }));
  });

  it('shows the template page exactly with an input on each region', async () => {
    renderPage();

    const name = await ready();
    expect(name).toHaveValue('Jane');
    const frame = screen.getByTestId('field-full_name');
    expect(frame.style.left).toBe('10%');
    expect(frame.style.top).toBe('20%');
    expect(frame.style.width).toBe('30%');
    expect(frame.style.height).toBe('2%');
    expect(onPage().getByLabelText('Date of birth')).toHaveAttribute('type', 'date');
    expect(onPage().getByLabelText('Amount')).toHaveAttribute('type', 'number');
    expect(onPage().getByLabelText('Amount').style.textAlign).toBe('right');
    expect(onPage().getByLabelText('I agree')).toHaveAttribute('type', 'checkbox');
    expect(await onPage().findByAltText('Front of house: front.jpg')).toBeInTheDocument();
    expect(onPage().getByRole('group', { name: 'Back of house — photo box' })).toHaveTextContent('Drop a photo here');
    // The page-2 field is not on page 1.
    expect(onPage().queryByLabelText('Notes')).not.toBeInTheDocument();
    await waitFor(() =>
      expect(fetchedPaths()).toContain('/api/doccreation/blueprints/1/pages/0?variant=template&version=2')
    );

    // Page 2 shows the multi-line field as a textarea.
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPage().getByLabelText('Notes').tagName).toBe('TEXTAREA');
  });

  it('lists the same fields by group in the side panel, kept in sync with the page', async () => {
    renderPage();
    await ready();

    const legends = screen.getAllByRole('group').map((g) => g.querySelector('legend')?.textContent).filter(Boolean);
    expect(legends).toEqual(['Applicant', 'Loan', 'Photos', 'Remarks']);
    const panelName = panel().getByRole('textbox', { name: /^Full name/ });
    expect(panelName).toHaveValue('Jane');
    expect(panelName).toHaveAttribute('aria-required', 'true');

    await userEvent.type(onPage().getByLabelText('Full name'), 't');
    expect(panelName).toHaveValue('Janet');
    // Focusing a page-2 field in the panel shows page 2.
    await userEvent.click(panel().getByRole('textbox', { name: /^Notes/ }));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
  });

  it('autosaves typing after a pause as one merged update', async () => {
    renderPage();
    const name = await ready();

    await userEvent.clear(name);
    await userEvent.type(name, 'Janet');
    await userEvent.type(panel().getByRole('spinbutton', { name: /^Amount/ }), '5');
    await userEvent.click(onPage().getByLabelText('I agree'));
    expect(screen.getByTestId('save-state')).toHaveTextContent('Unsaved changes…');

    await waitFor(() => expect(saveValues).toHaveBeenCalled(), { timeout: 2000 });
    expect(saveValues).toHaveBeenCalledTimes(1);
    expect(saveValues).toHaveBeenCalledWith('5', { full_name: 'Janet', amount: '10005', agree: 'true' });
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(screen.getByTestId('save-state')).toHaveAttribute('aria-live', 'polite');
  });

  it('uploads photos with auto-fill, and plain uploads to the tray', async () => {
    uploadImages.mockResolvedValue({
      assigned: [{ imageId: 40, regionKey: 'photo_back' }],
      unassigned: [41],
      rejected: [{ name: 'huge.bmp', reason: 'too large' }],
    });
    renderPage();
    await ready();

    const files = [
      new File([new Uint8Array([1])], 'back.jpg', { type: 'image/jpeg' }),
      new File([new Uint8Array([1])], 'side.png', { type: 'image/png' }),
      new File([new Uint8Array([1])], 'huge.bmp', { type: 'image/bmp' }),
      new File(['x'], 'notes.txt', { type: 'text/plain' }),
    ];
    const user = userEvent.setup({ applyAccept: false });
    await user.upload(screen.getByLabelText('Choose photos'), files);
    await user.click(screen.getByRole('button', { name: 'Auto-fill photos' }));

    expect(uploadImages).toHaveBeenCalledWith('5', files.slice(0, 3), true);
    expect(await screen.findByText(/Not a photo \(png, jpg, gif or bmp\): notes.txt/)).toBeInTheDocument();
    expect(
      await screen.findByText('1 placed in photo boxes, 1 in the tray, 1 rejected (huge.bmp: too large)')
    ).toBeInTheDocument();
    await waitFor(() => expect(getInstance).toHaveBeenCalledTimes(2));

    await user.upload(screen.getByLabelText('Choose photos'), files[1]);
    await user.click(screen.getByRole('button', { name: 'Upload to tray' }));
    expect(uploadImages).toHaveBeenLastCalledWith('5', [files[1]], false);
  });

  it('places a tray photo by drag and drop, and swaps when the box is filled', async () => {
    placeImage.mockResolvedValue(null);
    renderPage();
    await ready();

    const dataTransfer = fakeDataTransfer();
    fireEvent.dragStart(screen.getByTestId('tray-photo-32'), { dataTransfer });
    const empty = screen.getByTestId('photo-box-photo_back');
    fireEvent.dragOver(empty, { dataTransfer });
    fireEvent.drop(empty, { dataTransfer });
    expect(placeImage).toHaveBeenCalledWith('5', 32, 'photo_back');
    expect(await screen.findByText('Photo placed in “Back of house”')).toBeInTheDocument();

    const filled = screen.getByTestId('photo-box-photo_front');
    fireEvent.dragOver(filled, { dataTransfer });
    fireEvent.drop(filled, { dataTransfer });
    expect(placeImage).toHaveBeenLastCalledWith('5', 32, 'photo_front');
    expect(await screen.findByText('Photo placed in “Front of house” (swapped)')).toBeInTheDocument();
  });

  it('drags a placed photo onto another box', async () => {
    placeImage.mockResolvedValue(null);
    renderPage();
    await ready();
    const placed = await onPage().findByAltText('Front of house: front.jpg');

    const dataTransfer = fakeDataTransfer();
    fireEvent.dragStart(placed.parentElement, { dataTransfer });
    fireEvent.drop(screen.getByTestId('photo-box-photo_back'), { dataTransfer });
    expect(placeImage).toHaveBeenCalledWith('5', 31, 'photo_back');
  });

  it('places, moves and removes photos with the keyboard', async () => {
    placeImage.mockResolvedValue(null);
    deleteImage.mockResolvedValue(null);
    renderPage();
    await ready();

    await userEvent.selectOptions(screen.getByLabelText('Place roof.png in…'), 'photo_back');
    expect(placeImage).toHaveBeenCalledWith('5', 32, 'photo_back');

    await userEvent.selectOptions(screen.getByLabelText('Move front.jpg to…'), '__tray');
    expect(placeImage).toHaveBeenLastCalledWith('5', 31, null);
    expect(await screen.findByText('Photo moved to the tray')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Remove roof.png' }));
    expect(deleteImage).toHaveBeenCalledWith('5', 32);
  });

  it('warns when text will continue on an added page (server list and client estimate)', async () => {
    getInstance.mockResolvedValue(doc({ overflow: ['notes'] }));
    renderPage();
    await ready();

    // Server: notes overflowed at the last save.
    const notes = panel().getByRole('textbox', { name: /^Notes/ });
    expect(notes).toHaveAccessibleDescription('Will continue on an added page');
    expect(screen.queryByTestId('overflow-full_name')).not.toBeInTheDocument();

    // Client: 0.3 × 600 pt holds about 60 characters at the 6 pt minimum.
    fireEvent.change(onPage().getByLabelText('Full name'), { target: { value: 'x'.repeat(61) } });
    expect(screen.getByTestId('overflow-full_name')).toHaveTextContent('Will continue on an added page');
    fireEvent.change(onPage().getByLabelText('Full name'), { target: { value: 'x'.repeat(40) } });
    expect(screen.queryByTestId('overflow-full_name')).not.toBeInTheDocument();
  });

  it('previews the server-rendered pages in a dialog', async () => {
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    const dialog = await screen.findByRole('dialog', { name: 'Preview' });
    expect(await within(dialog).findByAltText('Preview of page 1')).toBeInTheDocument();
    expect(fetchedPaths()).toContain('/api/doccreation/instances/5/pages/0?dpi=110');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Next page' }));
    expect(await within(dialog).findByAltText('Preview of page 2')).toBeInTheDocument();
    expect(fetchedPaths()).toContain('/api/doccreation/instances/5/pages/1?dpi=110');
  });

  it('saves pending values, then generates and reports added pages and exactness', async () => {
    generateInstance.mockResolvedValue({
      pdfReady: true,
      addedPages: 1,
      continuedKeys: ['notes'],
      exact: true,
      generatedDate: '2026-10-15T10:00:00',
    });
    renderPage();
    await ready();

    await userEvent.type(onPage().getByLabelText('Full name'), '!');
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));

    expect(saveValues).toHaveBeenCalledWith('5', { full_name: 'Jane!' });
    expect(saveValues.mock.invocationCallOrder[0]).toBeLessThan(generateInstance.mock.invocationCallOrder[0]);
    expect(generateInstance).toHaveBeenCalledWith('5');
    expect(
      await screen.findByText('PDF generated — 1 page added for text that did not fit (Notes) · layout verified exact')
    ).toBeInTheDocument();
  });

  it('downloads the PDF', async () => {
    downloadInstance.mockResolvedValue(undefined);
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Download PDF' }));
    expect(downloadInstance).toHaveBeenCalledWith('5', 'Loan — Jane.pdf');
  });

  // ------------------------------------------------- ws11 release workflow

  it('submits a GENERATED document for release', async () => {
    getInstance.mockResolvedValue(doc({ status: 'GENERATED' }));
    submitInstance.mockResolvedValue({ instanceId: 5, status: 'SUBMITTED' });
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Submit for release' }));
    expect(submitInstance).toHaveBeenCalledWith('5');
    expect(await screen.findByText('Submitted — an approver (not you) can now release it')).toBeInTheDocument();
  });

  it('offers no submit button while the document is a DRAFT', async () => {
    renderPage();
    await ready();

    expect(screen.queryByRole('button', { name: 'Submit for release' })).not.toBeInTheDocument();
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
  });

  it('shows the status, the reviewer comment and a newer template version', async () => {
    getInstance.mockResolvedValue(
      doc({ status: 'GENERATED', blueprintCurrentVersion: 3, reviewerComment: 'Page 2 needs the Q3 figures' })
    );
    renderPage();
    await ready();

    expect(screen.getByText('GENERATED')).toBeInTheDocument();
    expect(screen.getByText('Built on template version 2 (newer available: version 3)')).toBeInTheDocument();
    expect(screen.getByText('Returned for rework')).toBeInTheDocument();
    expect(screen.getByText('Page 2 needs the Q3 figures')).toBeInTheDocument();
  });

  it('locks the fields while the document awaits release', async () => {
    getInstance.mockResolvedValue(doc({ status: 'SUBMITTED' }));
    renderPage();

    expect(await ready()).toBeDisabled();
    expect(panel().getByRole('textbox', { name: /^Full name/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
    expect(screen.queryByLabelText('Choose photos')).not.toBeInTheDocument();
    expect(screen.getByText('· awaiting release by an approver')).toBeInTheDocument();
  });
});
