import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';

import {
  downloadBlueprintFile,
  getBlueprint,
  listBlueprintVersions,
  saveOverlay,
  uploadEditImage,
} from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant = 'template', version, dpi } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}${version ? `&version=${version}` : ''}${
      dpi ? `&dpi=${dpi}` : ''
    }`,
  downloadBlueprintFile: vi.fn(),
  getBlueprint: vi.fn(),
  listBlueprintVersions: vi.fn(),
  saveOverlay: vi.fn(),
  uploadEditImage: vi.fn(),
}));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, overrides = {}) => ({
  key,
  label,
  group: 'Applicant',
  kind: 'TEXT',
  page: 0,
  fx: 0.1,
  fy: 0.2,
  fw: 0.3,
  fh: 0.02,
  fontName: 'Helvetica',
  fontSize: 10,
  color: '#000000',
  align: 'LEFT',
  maxLines: 1,
  acroFieldName: null,
  required: false,
  sortOrder: 0,
  source: 'DETECTED',
  confidence: 0.9,
  sampleValue: null,
  ...overrides,
});

const removal = (id, removedText, overrides = {}) => ({
  id,
  kind: 'REMOVE',
  page: 0,
  fx: 0.1,
  fy: 0.5,
  fw: 0.2,
  fh: 0.02,
  text: null,
  fontName: null,
  fontSize: null,
  color: null,
  imageKey: null,
  source: 'DATA_REMOVAL',
  confidence: 0.9,
  removedText,
  ...overrides,
});

const blueprint = (overrides = {}) => ({
  id: 4,
  name: 'Loan form',
  status: 'READY',
  flow: 'BLANK',
  version: 2,
  currentVersion: 2,
  pages: [
    { index: 0, ...PAGE },
    { index: 1, ...PAGE },
  ],
  regions: [
    region('full_name', 'Full name'),
    region('photo', 'Photo', { kind: 'IMAGE', fx: 0.7, fy: 0.05, fw: 0.2, fh: 0.15, maxLines: 0, sortOrder: 1 }),
    region('notes', 'Notes', { kind: 'MULTILINE', page: 1, maxLines: 4, fy: 0.6, fh: 0.1, sortOrder: 2 }),
  ],
  edits: [],
  warnings: [],
  detectedBy: 'CODE',
  documentCount: 0,
  ...overrides,
});

const draft = (overrides = {}) =>
  blueprint({
    status: 'DRAFT',
    flow: 'WITH_DATA',
    version: 1,
    currentVersion: 1,
    detectedBy: 'CODE+AI',
    regions: [
      region('full_name', 'Full name', { sampleValue: 'John Doe', source: 'DATA_REMOVED' }),
      region('dob', 'Date of birth', {
        kind: 'DATE',
        fy: 0.3,
        sortOrder: 1,
        sampleValue: '12/03/1980',
        confidence: 0.4,
      }),
    ],
    edits: [
      removal('e1', 'John Doe'),
      removal('e2', '12/03/1980', { fy: 0.3, confidence: 0.3 }),
    ],
    ...overrides,
  });

let fetchMock;

function renderPage(path = '/creation/blueprints/4') {
  fetchMock = stubImageFetch();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/blueprints/:id" element={<BlueprintDetailPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const pageView = () => screen.getByTestId('page-view');
const fetchedPaths = () => fetchMock.mock.calls.map(([path]) => path);
const savedBody = () => saveOverlay.mock.calls.at(-1)[1];

/** Drags from one point to another (page-view pixels; the page is 1000×1000 px). */
function drag(element, from, to) {
  fireEvent.pointerDown(element, { clientX: from[0], clientY: from[1], button: 0 });
  fireEvent.pointerMove(element, { clientX: to[0], clientY: to[1] });
  fireEvent.pointerUp(element, { clientX: to[0], clientY: to[1] });
}

describe('BlueprintDetailPage (template editor)', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    saveOverlay.mockImplementation(async (id, body) =>
      blueprint({ version: 3, currentVersion: 3, regions: body.regions, edits: body.edits })
    );
  });

  it('shows the template page with the regions at their positions, coloured by kind', async () => {
    renderPage();

    const name = await screen.findByTestId('region-full_name');
    expect(name).toHaveAccessibleName('Full name, Text');
    expect(name.style.left).toBe('10%');
    expect(name.style.top).toBe('20%');
    expect(name.style.width).toBe('30%');
    expect(name.style.height).toBe('2%');
    expect(screen.getByTestId('region-photo')).toHaveAttribute('data-kind', 'IMAGE');
    // Page 2's field is not drawn on page 1, but it is in the field list.
    expect(screen.queryByTestId('region-notes')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Notes/, pressed: false })).toBeInTheDocument();
    await waitFor(() =>
      expect(fetchedPaths()).toContain('/api/doccreation/blueprints/4/pages/0?variant=template&version=2')
    );
    expect(screen.getByAltText('Page 1 of 2 (template)')).toBeInTheDocument();
    expect(screen.getByText('2 pages · 3 fields · version 2')).toBeInTheDocument();
  });

  it('switches between the Original, Template and Overlay views', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Original' }));
    await waitFor(() => expect(fetchedPaths()).toContain('/api/doccreation/blueprints/4/pages/0?variant=original'));
    expect(screen.getByTestId('region-full_name')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Template' }));
    expect(screen.queryByTestId('region-full_name')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add field' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Overlay' }));
    expect(screen.getByTestId('region-full_name')).toBeInTheDocument();
  });

  it('draws a new field, names it in the properties panel and saves fractions with the base version', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Add field' }));
    drag(screen.getByTestId('draw-layer'), [200, 700], [500, 730]);

    const label = screen.getByLabelText('Label');
    expect(label).toHaveFocus();
    await userEvent.type(label, 'Employer');
    expect(screen.getByLabelText(/^Key/)).toHaveValue('employer');
    expect(screen.getByTestId('region-employer').style.top).toBe('70%');

    await userEvent.type(screen.getByLabelText(/Note for this version/), 'added employer');
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));

    expect(saveOverlay).toHaveBeenCalledTimes(1);
    expect(saveOverlay.mock.calls[0][0]).toBe('4');
    const body = savedBody();
    expect(body).toMatchObject({ baseVersion: 2, note: 'added employer', confirm: true, edits: [] });
    expect(body.regions).toHaveLength(4);
    expect(body.regions[3]).toEqual({
      key: 'employer',
      label: 'Employer',
      group: null,
      kind: 'TEXT',
      page: 0,
      fx: 0.2,
      fy: 0.7,
      fw: 0.3,
      fh: 0.03,
      fontName: 'Helvetica',
      fontSize: 0,
      color: '#000000',
      align: 'LEFT',
      maxLines: 1,
      acroFieldName: null,
      required: false,
      sortOrder: 3,
      source: 'MANUAL',
      confidence: 1,
    });
    expect(await screen.findByText('Saved as version 3')).toBeInTheDocument();
  });

  it('moves and resizes a field with the pointer and the keyboard', async () => {
    renderPage();
    const box = await screen.findByTestId('region-full_name');

    drag(box, [150, 205], [250, 255]); // +10 % right, +5 % down
    await waitFor(() => expect(screen.getByTestId('region-full_name').style.left).toBe('20%'));
    drag(screen.getByTestId('region-full_name').querySelector('[data-handle="se"]'), [500, 270], [550, 290]);
    fireEvent.keyDown(screen.getByTestId('region-full_name'), { key: 'ArrowLeft' });

    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(savedBody().regions[0]).toMatchObject({ key: 'full_name', fx: 0.195, fy: 0.25, fw: 0.35, fh: 0.04 });
  });

  it('edits a field from the list and the properties panel (the keyboard path)', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');
    const list = screen.getAllByTestId('field-list');

    // Selecting a page-2 field from the list shows page 2.
    await userEvent.click(within(list[1]).getByRole('button', { name: /Notes/ }));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByTestId('region-notes')).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(within(list[0]).getByRole('button', { name: /Full name/ }));
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'DATE');
    await userEvent.click(screen.getByLabelText('Required'));
    expect(screen.getByTestId('region-full_name')).toHaveAttribute('data-kind', 'DATE');

    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(savedBody().regions[0]).toMatchObject({ key: 'full_name', kind: 'DATE', required: true, maxLines: 1 });
  });

  it('draws a "remove content" box as a REMOVE edit', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Remove content' }));
    drag(screen.getByTestId('draw-layer'), [600, 900], [800, 950]);

    const list = screen.getByTestId('edit-list');
    expect(within(list).getByText('p.1 · Removed content')).toBeInTheDocument();
    expect(pageView().querySelector('[data-edit-kind="REMOVE"]')).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(savedBody().edits).toEqual([
      expect.objectContaining({
        kind: 'REMOVE',
        page: 0,
        fx: 0.6,
        fy: 0.9,
        fw: 0.2,
        fh: 0.05,
        source: 'USER',
        id: expect.any(String),
      }),
    ]);
  });

  it('replaces existing text through the Edit text tool', async () => {
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    drag(screen.getByTestId('draw-layer'), [100, 50], [400, 80]);
    const dialog = await screen.findByRole('dialog', { name: 'Replace the text in this box' });
    await userEvent.type(within(dialog).getByLabelText('Replacement text'), 'ACME Bank plc');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Replace text' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replaced text: “ACME Bank plc”' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(savedBody().edits[0]).toMatchObject({ kind: 'REPLACE_TEXT', text: 'ACME Bank plc', fontSize: 10, fx: 0.1 });
  });

  it('adds a logo: uploads the image, then places an ADD_IMAGE edit', async () => {
    uploadEditImage.mockResolvedValue({ imageKey: 'img-7' });
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Add image/logo' }));
    fireEvent.keyDown(screen.getByTestId('draw-layer'), { key: 'Enter' });
    const dialog = await screen.findByRole('dialog', { name: 'Add an image or logo' });
    const logo = new File([new Uint8Array([1])], 'logo.png', { type: 'image/png' });
    await userEvent.upload(within(dialog).getByLabelText(/Image file/), logo);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add image' }));

    expect(uploadEditImage).toHaveBeenCalledWith('4', logo);
    expect(await screen.findByRole('button', { name: 'Added image' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    expect(savedBody().edits[0]).toMatchObject({ kind: 'ADD_IMAGE', imageKey: 'img-7', fx: 0.4, fy: 0.47 });
  });

  it('reviews a DRAFT: removed values, low-confidence highlights and the checklist with jump-to', async () => {
    getBlueprint.mockResolvedValue(draft());
    renderPage();

    expect(await screen.findByText('Review the proposed template')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm template' })).toBeInTheDocument();
    expect(screen.getByTestId('region-dob')).toHaveAttribute('data-low-confidence', 'true');
    expect(screen.getByTestId('region-full_name')).not.toHaveAttribute('data-low-confidence');
    const lowRemoval = screen.getByRole('button', { name: 'Removed content: “12/03/1980” (low confidence)' });
    expect(lowRemoval).toHaveAttribute('data-low-confidence', 'true');
    // The removed value shows in the box while reviewing.
    expect(within(screen.getByTestId('region-full_name')).getByText('John Doe')).toBeInTheDocument();

    const checklist = screen.getByTestId('checklist');
    expect(within(checklist).getByText('Check the removal of “12/03/1980” on page 1 (low confidence)')).toBeInTheDocument();
    expect(within(checklist).getByText('Check field “Date of birth” on page 1 (low confidence)')).toBeInTheDocument();

    await userEvent.click(
      within(checklist).getByRole('button', {
        name: 'Show: Check the removal of “12/03/1980” on page 1 (low confidence)',
      })
    );
    expect(lowRemoval).toHaveFocus();
    expect(lowRemoval).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/Removed: “12\/03\/1980” — low confidence, please check/)).toBeInTheDocument();
  });

  it('lists unlabelled, overlapping and duplicate-key fields, and blocks saving duplicate keys', async () => {
    getBlueprint.mockResolvedValue(
      blueprint({
        regions: [
          region('amount', 'Amount'),
          region('amount', 'Amount (copy)', { fx: 0.2, sortOrder: 1 }),
          region('field_3', '', { fy: 0.8, sortOrder: 2 }),
        ],
      })
    );
    renderPage();

    const checklist = await screen.findByTestId('checklist');
    expect(within(checklist).getByText('Field “field_3” on page 1 has no label')).toBeInTheDocument();
    expect(within(checklist).getByText('Key “amount” is used by 2 fields')).toBeInTheDocument();
    expect(within(checklist).getByText('“Amount” and “Amount (copy)” overlap on page 1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save as new version' })).toBeDisabled();

    await userEvent.click(
      within(checklist).getByRole('button', { name: 'Show: Field “field_3” on page 1 has no label' })
    );
    expect(screen.getByTestId('region-field_3')).toHaveFocus();
  });

  it('restores a removal and confirms the draft without the review-only values', async () => {
    getBlueprint.mockResolvedValue(draft());
    saveOverlay.mockResolvedValue(blueprint({ version: 1, currentVersion: 1, regions: [], edits: [] }));
    renderPage();
    await screen.findByText('Review the proposed template');

    await userEvent.click(screen.getByRole('button', { name: 'Restore: Removed content: “John Doe” on page 1' }));
    expect(screen.queryByRole('button', { name: /Removed content: “John Doe”/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm template' }));

    const body = savedBody();
    expect(body.baseVersion).toBe(1);
    expect(body.confirm).toBe(true);
    expect(body.edits.map((e) => e.id)).toEqual(['e2']);
    expect(body.edits[0]).not.toHaveProperty('removedText');
    expect(body.regions[0]).not.toHaveProperty('sampleValue');
    expect(body.regions[0]).toMatchObject({ key: 'full_name', fx: 0.1, fy: 0.2, fw: 0.3, fh: 0.02 });
    expect(await screen.findByText('Template confirmed — it is ready for new documents')).toBeInTheDocument();
  });

  it('offers to reload when someone else saved first (409)', async () => {
    const conflict = Object.assign(new Error('Stale base version'), { status: 409 });
    saveOverlay.mockRejectedValue(conflict);
    renderPage();
    await screen.findByTestId('region-full_name');
    fireEvent.keyDown(screen.getByTestId('region-full_name'), { key: 'ArrowDown' });

    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    const dialog = await screen.findByRole('dialog', { name: 'This template was changed elsewhere' });
    getBlueprint.mockResolvedValue(blueprint({ version: 3, currentVersion: 3 }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reload the latest version' }));

    expect(getBlueprint).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('2 pages · 3 fields · version 3')).toBeInTheDocument();
    expect(screen.getByTestId('region-full_name').style.top).toBe('20%');
  });

  it('shows an older version read-only from the history', async () => {
    listBlueprintVersions.mockResolvedValue([
      { version: 2, current: true, documentCount: 0, note: 'moved the photo' },
      { version: 1, current: false, documentCount: 2 },
    ]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version history' }));
    expect(await screen.findByText('“moved the photo”')).toBeInTheDocument();
    getBlueprint.mockResolvedValue(blueprint({ version: 1, currentVersion: 2 }));
    await userEvent.click(screen.getByRole('button', { name: 'View v1' }));

    expect(getBlueprint).toHaveBeenLastCalledWith('4', 1);
    expect(await screen.findByText('Version 1 (read-only)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save as new version' })).not.toBeInTheDocument();
    expect(screen.queryByRole('toolbar', { name: 'Editing tools' })).not.toBeInTheDocument();
    await waitFor(() =>
      expect(fetchedPaths()).toContain('/api/doccreation/blueprints/4/pages/0?variant=template&version=1')
    );
    // Selectable to inspect, but nothing moves.
    const box = screen.getByTestId('region-full_name');
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(box.querySelector('[data-handle]')).toBeNull();
    fireEvent.keyDown(box, { key: 'ArrowRight' });
    expect(box.style.left).toBe('10%');
    expect(screen.getByLabelText('Label')).toHaveAttribute('readonly');
  });

  it('downloads the original and the template PDF', async () => {
    downloadBlueprintFile.mockResolvedValue(undefined);
    renderPage();
    await screen.findByTestId('region-full_name');

    await userEvent.click(screen.getByRole('button', { name: 'Download template PDF' }));
    expect(downloadBlueprintFile).toHaveBeenCalledWith('4', {
      variant: 'template',
      version: 2,
      fallbackName: 'Loan form.pdf',
    });
    await userEvent.click(screen.getByRole('button', { name: 'Download original PDF' }));
    expect(downloadBlueprintFile).toHaveBeenLastCalledWith('4', {
      variant: 'original',
      version: undefined,
      fallbackName: 'Loan form-original.pdf',
    });
  });
});
