import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';

import {
  getBlueprint,
  getBlueprintParagraphs,
  listBlueprintVersions,
  resectionBlueprint,
  saveBlueprintSections,
} from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  downloadBlueprintFile: vi.fn(),
  getBlueprint: vi.fn(),
  getBlueprintParagraphs: vi.fn(),
  listBlueprintVersions: vi.fn(),
  resectionBlueprint: vi.fn(),
  saveBlueprintSections: vi.fn(),
  updateSectionKeywords: vi.fn(),
}));

const section = (sectionId, title, anchorParagraphIndex, extra = {}) => ({
  sectionId,
  orderIndex: sectionId - 1,
  title,
  headingLevel: 1,
  keywords: [],
  anchorParagraphIndex,
  anchorText: title,
  ...extra,
});

const blueprint = (overrides = {}) => ({
  blueprintId: 4,
  name: 'Loan form',
  flowKey: 'DEFAULT',
  sectionSource: 'AI',
  version: 2,
  currentVersion: 2,
  coveragePercent: 100,
  gaps: [],
  healthIssues: [],
  sections: [
    section(1, 'Applicant', 0, { sectionKind: 'FORM' }),
    section(2, 'Income', 5),
    section(3, 'Declaration', 9),
  ],
  ...overrides,
});

function renderPage(path = '/creation/blueprints/4') {
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

describe('BlueprintDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getBlueprint.mockResolvedValue(blueprint());
  });

  it('says how the sections were found and which version is shown', async () => {
    renderPage();

    expect(await screen.findByText('Sections found by AI')).toBeInTheDocument();
    expect(screen.getByText(/3 sections · version 2/)).toBeInTheDocument();
  });

  it('labels the rule-based fallback', async () => {
    getBlueprint.mockResolvedValue(blueprint({ sectionSource: 'HEURISTIC' }));
    renderPage();

    expect(
      await screen.findByText('Sections found by the rule-based parser (AI unavailable or found nothing)')
    ).toBeInTheDocument();
  });

  it('saves renamed, merged and added sections as the next version', async () => {
    getBlueprintParagraphs.mockResolvedValue([
      { index: 0, text: 'Applicant', style: null, headingCandidate: true },
      { index: 3, text: 'Joint applicant', style: null, headingCandidate: true },
      { index: 5, text: 'Income', style: null, headingCandidate: true },
    ]);
    saveBlueprintSections.mockResolvedValue(
      blueprint({ version: 3, currentVersion: 3, sectionSource: 'MANUAL', sections: [section(7, 'Main applicant', 0)] })
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Edit sections' }));
    const title = screen.getByLabelText('Title of section 1');
    await userEvent.clear(title);
    await userEvent.type(title, 'Main applicant');
    // Income folds into the section above it.
    await userEvent.click(screen.getAllByRole('button', { name: 'Merge with next' })[1]);
    await userEvent.click(screen.getByRole('button', { name: '+ Add section at a paragraph' }));
    const dialog = await screen.findByRole('dialog');
    // Paragraphs that already start a section cannot be picked again.
    expect(within(dialog).getByRole('button', { name: /Applicant/ })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('button', { name: /Joint applicant/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Save as version 3' }));

    expect(saveBlueprintSections).toHaveBeenCalledWith(
      4,
      2,
      [
        { title: 'Main applicant', headingLevel: 1, anchorParagraphIndex: 0, keywords: [] },
        { title: 'Joint applicant', headingLevel: 1, anchorParagraphIndex: 3, keywords: [] },
        { title: 'Income', headingLevel: 1, anchorParagraphIndex: 5, keywords: [] },
      ],
      undefined
    );
    expect(await screen.findByText('Sections edited by hand')).toBeInTheDocument();
  });

  it('re-runs AI sectioning only after confirming', async () => {
    resectionBlueprint.mockResolvedValue(blueprint({ version: 3, currentVersion: 3 }));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Re-run AI sectioning' }));
    expect(resectionBlueprint).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Re-run and save' }));

    expect(resectionBlueprint).toHaveBeenCalledWith('4', 2);
    expect(await screen.findByText(/3 sections · version 3/)).toBeInTheDocument();
  });

  it('shows older versions read-only from the history', async () => {
    listBlueprintVersions.mockResolvedValue([
      { version: 2, sectionSource: 'MANUAL', sectionCount: 3, documentCount: 0, current: true },
      { version: 1, sectionSource: 'HEURISTIC', sectionCount: 1, documentCount: 2, current: false },
    ]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version history' }));
    expect(await screen.findByText(/1 section · 2 documents/)).toBeInTheDocument();
    getBlueprint.mockResolvedValue(
      blueprint({ version: 1, sectionSource: 'HEURISTIC', sections: [section(1, 'Loan', -1)] })
    );
    await userEvent.click(screen.getByRole('button', { name: 'View' }));

    expect(getBlueprint).toHaveBeenLastCalledWith('4', 1);
    expect(await screen.findByText('Version 1 (read-only)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit sections' })).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Add keyword, press Enter')).not.toBeInTheDocument();
  });
});
