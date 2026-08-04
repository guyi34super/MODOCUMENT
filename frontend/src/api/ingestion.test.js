import { describe, expect, it, vi, beforeEach } from 'vitest';
import { api } from './client';
import { createJobFromFile, waitForJob, needsIngestionWait } from './ingestion';

vi.mock('./client', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('ingestion API', () => {
  beforeEach(() => {
    api.get.mockReset();
    api.post.mockReset();
    api.get.mockResolvedValue({});
    api.post.mockResolvedValue({});
  });

  it('waitForJob defaults until to MAPPED', async () => {
    await waitForJob(7);
    expect(api.get).toHaveBeenCalledWith('/api/ingestion/jobs/7?wait=true&until=MAPPED');
  });

  it('createJobFromFile defaults runUntil to MAPPED', async () => {
    const file = new File(['a,b'], 'people.csv', { type: 'text/csv' });
    await createJobFromFile(file, 1);
    const form = api.post.mock.calls[0][1];
    expect(form.get('runUntil')).toBe('MAPPED');
    expect(form.get('wait')).toBe('false');
  });

  it('createJobFromFile sends wait=true for single-call processing', async () => {
    const file = new File(['a,b'], 'people.csv', { type: 'text/csv' });
    await createJobFromFile(file, 1, true, 'MAPPED', true);
    const form = api.post.mock.calls[0][1];
    expect(form.get('wait')).toBe('true');
  });

  it('needsIngestionWait is false for mapped completed jobs', () => {
    expect(
      needsIngestionWait({ status: 'COMPLETED', currentStep: 'MAPPED' }, 'MAPPED')
    ).toBe(false);
    expect(
      needsIngestionWait({ status: 'COMPLETED', currentStep: 'PARSED' }, 'MAPPED')
    ).toBe(true);
    expect(needsIngestionWait({ status: 'RUNNING', currentStep: 'PARSED' }, 'MAPPED')).toBe(true);
  });
});
