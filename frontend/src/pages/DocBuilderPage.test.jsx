import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';

import {
  addBlock,
  assignAsset,
  generateInstance,
  getInstance,
  uploadAssets,
} from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  addBlock: vi.fn(),
  assetContentPath: (id) => `/api/doccreation/assets/${id}/content`,
  assignAsset: vi.fn(),
  deleteAsset: vi.fn(),
  deleteBlock: vi.fn(),
  downloadInstance: vi.fn(),
  generateInstance: vi.fn(),
  getInstance: vi.fn(),
  reorderBlocks: vi.fn(),
  updateBlock: vi.fn(),
  uploadAssets: vi.fn(),
}));

const baseDoc = {
  instanceId: 5,
  title: 'Survey 42',
  blueprintId: 1,
  blueprintName: 'PV Report',
  status: 'DRAFT',
  lastGeneratedDate: null,
  pdfAvailable: false,
  sections: [
    {
      sectionId: 10,
      orderIndex: 0,
      title: 'Roof',
      headingLevel: 1,
      keywords: ['roof'],
      blocks: [
        { blockId: 100, blockType: 'PARAGRAPH', orderIndex: 0, text: 'Roof note.' },
      ],
    },
    {
      sectionId: 20,
      orderIndex: 1,
      title: 'Inverter',
      headingLevel: 1,
      keywords: ['inverter', 'dc2'],
      blocks: [],
    },
  ],
  unsortedAssets: [{ assetId: 7, originalFileName: 'IMG_1234.png' }],
};

function renderPage() {
  // AuthedImage fetches thumbnails; give it a harmless blob response.
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response(new Blob([new Uint8Array([1])]), { status: 200 }))
  );
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:preview'),
    revokeObjectURL: vi.fn(),
  });
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

describe('DocBuilderPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(baseDoc);
  });

  it('renders the sections in document order with their blocks', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Roof' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Inverter' })).toBeInTheDocument();
    expect(screen.getByText('Roof note.')).toBeInTheDocument();
    expect(screen.getByText('Nothing added to this section yet.')).toBeInTheDocument();
  });

  it('shows the Unsorted tray with a section picker', async () => {
    assignAsset.mockResolvedValue(null);
    renderPage();

    expect(await screen.findByText('Unsorted images (1)')).toBeInTheDocument();
    const picker = screen.getByLabelText('Assign IMG_1234.png to a section');
    await userEvent.selectOptions(picker, '10');

    expect(assignAsset).toHaveBeenCalledWith(7, 10);
  });

  it('bulk-uploads images and reports the assigned/unsorted split', async () => {
    uploadAssets.mockResolvedValue({
      assigned: [{ assetId: 1 }, { assetId: 2 }],
      unsorted: [{ assetId: 3 }],
      rejected: [],
    });
    const { container } = renderPage();
    await screen.findByRole('heading', { name: 'Roof' });

    const input = container.querySelector('input[type="file"]');
    const file = new File([new Uint8Array([1])], 'roof_east.png', { type: 'image/png' });
    await userEvent.upload(input, file);

    expect(uploadAssets).toHaveBeenCalledWith('5', [file], true);
    expect(await screen.findByText('2 auto-assigned, 1 in Unsorted')).toBeInTheDocument();
  });

  it('adds a paragraph to a section', async () => {
    addBlock.mockResolvedValue({ blockId: 200 });
    renderPage();
    await screen.findByRole('heading', { name: 'Roof' });

    await userEvent.click(screen.getAllByRole('button', { name: '+ Add paragraph' })[1]);

    expect(addBlock).toHaveBeenCalledWith(5, 20, { blockType: 'PARAGRAPH', text: '' });
  });

  it('disables the PDF download while no converter is available', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Roof' });

    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Download .docx' })).toBeEnabled();
  });

  it('generates and reports whether the PDF came out', async () => {
    generateInstance.mockResolvedValue({ pdfReady: false, docxFileName: 'x.docx' });
    renderPage();
    await screen.findByRole('heading', { name: 'Roof' });

    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));

    expect(generateInstance).toHaveBeenCalledWith('5');
    expect(await screen.findByText('Document generated (.docx only)')).toBeInTheDocument();
  });
});
