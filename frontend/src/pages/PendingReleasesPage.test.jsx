import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import PendingReleasesPage from './PendingReleasesPage';

import {
  downloadReviewFile,
  listPendingReleases,
  rejectInstance,
  releaseInstance,
} from '../api/doccreation';

vi.mock('../api/doccreation', () => ({
  listPendingReleases: vi.fn(),
  releaseInstance: vi.fn(),
  rejectInstance: vi.fn(),
  downloadReviewFile: vi.fn(),
}));

/** #11 is AI-composed (the C10 case), #12 engine-assembled. */
const ENTRIES = [
  {
    instanceId: 11,
    title: 'Compliance report Q3',
    blueprintName: 'Compliance',
    ownerUserId: 2,
    ownerEmail: 'author@bank.example',
    submittedByUserId: 2,
    submittedByEmail: 'author@bank.example',
    submittedAt: '2026-10-01T09:00:00',
    lastGeneratedDate: '2026-10-01T08:55:00',
    aiComposed: true,
    sections: [
      {
        sectionId: 1,
        title: 'Findings',
        blocks: [
          {
            blockId: 100,
            blockType: 'PARAGRAPH',
            orderIndex: 0,
            text: 'The model wrote this paragraph.',
          },
        ],
      },
      { sectionId: 2, title: 'Appendix', blocks: [] },
    ],
  },
  {
    instanceId: 12,
    title: 'Site survey',
    blueprintName: 'Survey',
    ownerUserId: 3,
    ownerEmail: 'other@bank.example',
    submittedByUserId: 3,
    submittedByEmail: 'other@bank.example',
    submittedAt: '2026-10-02T10:00:00',
    lastGeneratedDate: '2026-10-02T09:00:00',
    aiComposed: false,
    sections: [],
  },
];

function renderPage() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <PendingReleasesPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('PendingReleasesPage', () => {
  beforeEach(() => {
    listPendingReleases.mockReset().mockResolvedValue(ENTRIES);
    releaseInstance.mockReset().mockResolvedValue({ instanceId: 11, status: 'RELEASED' });
    rejectInstance.mockReset().mockResolvedValue({ instanceId: 11, status: 'GENERATED' });
    downloadReviewFile.mockReset().mockResolvedValue(undefined);
  });

  it('renders the queue with authors, content blocks and the AI badge (C10)', async () => {
    renderPage();

    expect(await screen.findByText('#11 · Compliance report Q3')).toBeInTheDocument();
    expect(screen.getByText('#12 · Site survey')).toBeInTheDocument();
    // The approver sees WHAT the model wrote, not just a title...
    expect(screen.getByText('The model wrote this paragraph.')).toBeInTheDocument();
    // ...and that a human release is the review of AI-composed content.
    expect(screen.getAllByText('AI-composed')).toHaveLength(1);
    expect(
      screen.getByText(/composed by AI — releasing it records your human review/)
    ).toBeInTheDocument();
    expect(screen.getByText(/by author@bank.example/)).toBeInTheDocument();
  });

  it('releases with the typed review comment', async () => {
    renderPage();
    await screen.findByText('#11 · Compliance report Q3');

    await userEvent.type(
      screen.getByRole('textbox', { name: 'Review comment for document 11' }),
      'figures verified'
    );
    await userEvent.click(screen.getAllByRole('button', { name: 'Release' })[0]);

    await waitFor(() => expect(releaseInstance).toHaveBeenCalledWith(11, 'figures verified'));
    expect(await screen.findByText('"Compliance report Q3" released')).toBeInTheDocument();
    // The queue reloads after a decision.
    await waitFor(() => expect(listPendingReleases).toHaveBeenCalledTimes(2));
  });

  it('rejects with the typed comment', async () => {
    renderPage();
    await screen.findByText('#11 · Compliance report Q3');

    await userEvent.type(
      screen.getByRole('textbox', { name: 'Review comment for document 11' }),
      'needs the Q3 figures'
    );
    await userEvent.click(screen.getAllByRole('button', { name: 'Reject' })[0]);

    await waitFor(() => expect(rejectInstance).toHaveBeenCalledWith(11, 'needs the Q3 figures'));
  });

  it('surfaces the self-release 409 clearly (toast and banner)', async () => {
    releaseInstance.mockRejectedValue(
      new Error(
        'Self-release is blocked: the author or submitter of a report cannot release '
          + 'or reject it — a different user must.'
      )
    );
    renderPage();
    await screen.findByText('#11 · Compliance report Q3');

    await userEvent.click(screen.getAllByRole('button', { name: 'Release' })[0]);

    await waitFor(() => expect(releaseInstance).toHaveBeenCalledWith(11, ''));
    const messages = await screen.findAllByText(/Self-release is blocked/);
    // Once in the toast, once in the persistent page banner.
    expect(messages.length).toBeGreaterThanOrEqual(2);
    // Nothing was decided: no reload beyond the initial one.
    expect(listPendingReleases).toHaveBeenCalledTimes(1);
  });

  it('downloads the submitted document for review', async () => {
    renderPage();
    await screen.findByText('#11 · Compliance report Q3');

    await userEvent.click(screen.getAllByRole('button', { name: 'Download .docx' })[0]);

    await waitFor(() =>
      expect(downloadReviewFile).toHaveBeenCalledWith(11, 'docx', 'Compliance report Q3.docx')
    );
  });
});
