import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearCaches, getTemplateDataTypes, getTemplates, invalidateTemplates } from './cache';

function jsonResponse(body) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('session caches', () => {
  beforeEach(() => {
    clearCaches();
  });

  it('fetches templates once and reuses the cached promise', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ templateId: 1, name: 'A' }]));
    vi.stubGlobal('fetch', fetchMock);

    const first = await getTemplates();
    const second = await getTemplates();

    expect(first).toEqual(second);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('refetches templates after invalidateTemplates()', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([{ templateId: 1 }]))
      .mockResolvedValueOnce(jsonResponse([{ templateId: 1 }, { templateId: 2 }]));
    vi.stubGlobal('fetch', fetchMock);

    await getTemplates();
    invalidateTemplates();
    const refreshed = await getTemplates();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(refreshed).toHaveLength(2);
  });

  it('fetches data types only once per session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ templateDataTypeId: 1, dataType: 'Text' }]));
    vi.stubGlobal('fetch', fetchMock);

    await getTemplateDataTypes();
    await getTemplateDataTypes();
    await getTemplateDataTypes();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not cache failures — the next call retries', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('down', { status: 500 }))
      .mockResolvedValueOnce(jsonResponse([{ templateId: 1 }]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getTemplates()).rejects.toThrow();
    await expect(getTemplates()).resolves.toEqual([{ templateId: 1 }]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
