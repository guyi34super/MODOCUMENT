import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { getInstance, saveOverlay, saveValues, upgradeInstance } from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant, version } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}&version=${version}`,
  imageContentPath: (id, imageId) => `/api/doccreation/instances/${id}/images/${imageId}/content`,
  instancePagePath: (id, page) => `/api/doccreation/instances/${id}/pages/${page}`,
  deleteImage: vi.fn(),
  downloadInstance: vi.fn(),
  generateInstance: vi.fn(),
  getInstance: vi.fn(),
  placeImage: vi.fn(),
  saveOverlay: vi.fn(),
  saveValues: vi.fn(),
  submitInstance: vi.fn(),
  upgradeInstance: vi.fn(),
  uploadImages: vi.fn(),
  listBlueprintSources: vi.fn(),
  getFillPreview: vi.fn(),
  fillFromRecord: vi.fn(),
}));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, kind, overrides = {}) => ({
  key,
  label,
  group: 'Applicant',
  kind,
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
  ...overrides,
});

/** The blueprint's current template edits: the builder must resend them untouched. */
const EDITS = [
  {
    id: 'e1',
    kind: 'ADD_TEXT',
    page: 0,
    fx: 0.5,
    fy: 0.9,
    fw: 0.2,
    fh: 0.02,
    text: 'Branch copy',
    fontName: 'Helvetica',
    fontSize: 9,
    color: '#000000',
    imageKey: null,
    source: 'USER',
    confidence: 1,
    regionKey: null,
    applied: true,
  },
];

const doc = (overrides = {}) => ({
  id: 5,
  title: 'Loan — Jane',
  status: 'DRAFT',
  blueprintId: 1,
  blueprintName: 'Loan form',
  blueprintVersion: 2,
  blueprintCurrentVersion: 2,
  pages: [{ index: 0, ...PAGE }],
  regions: [
    region('full_name', 'Full name', 'TEXT'),
    region('agree', 'I agree', 'CHECKBOX', { fy: 0.35, fw: 0.02, sortOrder: 1 }),
  ],
  values: { full_name: 'Jane', agree: 'true' },
  images: [],
  overflow: [],
  sourceRecords: [],
  canEditLayout: true,
  blueprintEdits: EDITS,
  ...overrides,
});

function renderPage() {
  stubImageFetch();
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

const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');

/** Drags from one point to another (page-view pixels; the page is 1000×1000 px). */
function drag(element, from, to) {
  fireEvent.pointerDown(element, { clientX: from[0], clientY: from[1], button: 0 });
  fireEvent.pointerMove(element, { clientX: to[0], clientY: to[1] });
  fireEvent.pointerUp(element, { clientX: to[0], clientY: to[1] });
}

describe('DocBuilderPage — Edit layout', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getInstance.mockResolvedValue(doc());
    saveValues.mockResolvedValue(doc());
  });

  it('offers the layout tools only when the caller may edit the blueprint and the document is not locked', async () => {
    getInstance.mockResolvedValue(doc({ canEditLayout: false }));
    const { unmount } = renderPage();
    await ready();
    expect(screen.queryByRole('button', { name: 'Edit layout' })).not.toBeInTheDocument();
    unmount();

    getInstance.mockResolvedValue(doc({ status: 'SUBMITTED' }));
    renderPage();
    await ready();
    expect(screen.queryByRole('button', { name: 'Edit layout' })).not.toBeInTheDocument();
  });

  it('moves a field and adds one, saves the layout to the template, then moves the document to it', async () => {
    saveOverlay.mockResolvedValue({ id: 1, version: 3, currentVersion: 3 });
    upgradeInstance.mockResolvedValue({
      ...doc({ blueprintVersion: 3, blueprintCurrentVersion: 3 }),
      droppedKeys: ['agree'],
    });
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Edit layout' }));
    const box = screen.getByTestId('layout-region-full_name');
    expect(box).toHaveAccessibleName('Full name, Text');
    expect(screen.getByRole('button', { name: 'Save layout to template' })).toBeDisabled();

    drag(box, [150, 205], [250, 255]); // +10 % right, +5 % down
    await waitFor(() => expect(screen.getByTestId('layout-region-full_name').style.left).toBe('20%'));
    fireEvent.keyDown(screen.getByTestId('layout-region-full_name'), { key: 'ArrowLeft' });
    expect(screen.getByTestId('layout-region-full_name').style.left).toBe('19.5%');

    await userEvent.click(within(screen.getByRole('toolbar', { name: 'Layout tools' })).getByRole('button', { name: 'Add field' }));
    drag(screen.getByTestId('draw-layer'), [200, 700], [500, 730]);
    const label = screen.getByLabelText('Label');
    expect(label).toHaveFocus();
    await userEvent.type(label, 'Employer');
    expect(screen.getByTestId('layout-region-employer').style.top).toBe('70%');
    expectAccessible();

    await userEvent.click(screen.getByRole('button', { name: 'Save layout to template' }));

    expect(saveOverlay).toHaveBeenCalledTimes(1);
    const [blueprintId, body] = saveOverlay.mock.calls[0];
    expect(blueprintId).toBe(1);
    expect(body).toMatchObject({
      baseVersion: 2,
      note: 'Layout adjusted from document "Loan — Jane"',
      confirm: true,
    });
    expect(body.edits).toEqual(EDITS);
    expect(body.regions).toHaveLength(3);
    expect(body.regions[0]).toEqual({ ...region('full_name', 'Full name', 'TEXT'), fx: 0.195, fy: 0.25 });
    expect(body.regions[1]).toEqual(doc().regions[1]);
    expect(body.regions[2]).toEqual({
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
      sortOrder: 2,
      source: 'MANUAL',
      confidence: 1,
    });

    await waitFor(() => expect(upgradeInstance).toHaveBeenCalledWith('5'));
    expect(saveOverlay.mock.invocationCallOrder[0]).toBeLessThan(upgradeInstance.mock.invocationCallOrder[0]);
    expect(
      await screen.findByText(
        'Layout saved to the template as version 3 · values removed for fields no longer on the template: agree'
      )
    ).toBeInTheDocument();
    // Back to filling, on the new version.
    expect(screen.getByRole('button', { name: 'Edit layout' })).toBeInTheDocument();
    expect(screen.getByText('Built on template version 3')).toBeInTheDocument();
  });

  it('cancel restores the layout without saving', async () => {
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Edit layout' }));
    fireEvent.keyDown(screen.getByTestId('layout-region-full_name'), { key: 'ArrowRight' });
    expect(screen.getByTestId('layout-region-full_name').style.left).toBe('10.5%');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(saveOverlay).not.toHaveBeenCalled();
    expect(screen.getByTestId('field-full_name').style.left).toBe('10%');
    await userEvent.click(screen.getByRole('button', { name: 'Edit layout' }));
    expect(screen.getByTestId('layout-region-full_name').style.left).toBe('10%');
  });

  it('offers to reload when the template was saved elsewhere (409), without moving the document', async () => {
    saveOverlay.mockRejectedValue(Object.assign(new Error('Stale base version'), { status: 409 }));
    renderPage();
    await ready();

    await userEvent.click(screen.getByRole('button', { name: 'Edit layout' }));
    fireEvent.keyDown(screen.getByTestId('layout-region-full_name'), { key: 'ArrowDown' });
    await userEvent.click(screen.getByRole('button', { name: 'Save layout to template' }));

    const dialog = await screen.findByRole('dialog', { name: 'The template was changed elsewhere' });
    expect(upgradeInstance).not.toHaveBeenCalled();
    getInstance.mockResolvedValue(doc({ blueprintCurrentVersion: 3 }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reload the latest version' }));

    await waitFor(() => expect(getInstance).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('A newer template version 3 exists')).toBeInTheDocument();
    expect(screen.queryByTestId('layout-region-full_name')).not.toBeInTheDocument();
  });

  it('shows a newer template version and requires moving to it before editing the layout', async () => {
    getInstance.mockResolvedValue(doc({ blueprintCurrentVersion: 3 }));
    upgradeInstance.mockResolvedValue({ ...doc({ blueprintVersion: 3, blueprintCurrentVersion: 3 }), droppedKeys: [] });
    renderPage();
    await ready();

    expect(screen.getByText('A newer template version 3 exists')).toBeInTheDocument();
    const edit = screen.getByRole('button', { name: 'Edit layout' });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAccessibleDescription('Move to version 3 first to edit the layout.');

    await userEvent.click(screen.getByRole('button', { name: 'Move to version 3' }));
    expect(upgradeInstance).toHaveBeenCalledWith('5');
    expect(await screen.findByText('Moved to template version 3')).toBeInTheDocument();
    expect(screen.queryByText('A newer template version 3 exists')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit layout' })).toBeEnabled();
  });
});
