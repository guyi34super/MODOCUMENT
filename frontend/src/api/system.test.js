import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { getVersion, resetVersionCache } from './system';

vi.mock('./client', () => ({ api: { get: vi.fn() } }));

describe('getVersion memo', () => {
  beforeEach(() => {
    resetVersionCache();
    api.get.mockReset();
  });

  it('asks the server once per session', async () => {
    api.get.mockResolvedValue({ version: '1.2.0' });
    const [a, b] = await Promise.all([getVersion(), getVersion()]);
    await getVersion();
    expect(a).toEqual({ version: '1.2.0' });
    expect(b).toBe(a);
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/api/system/version');
  });

  it('fresh: true asks again and updates the memo', async () => {
    api.get.mockResolvedValueOnce({ version: '1.2.0' }).mockResolvedValueOnce({ version: '1.3.0' });
    await getVersion();
    expect(await getVersion({ fresh: true })).toEqual({ version: '1.3.0' });
    expect(await getVersion()).toEqual({ version: '1.3.0' });
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('never memoizes a failure', async () => {
    api.get.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ version: '1.2.0' });
    await expect(getVersion()).rejects.toThrow('offline');
    expect(await getVersion()).toEqual({ version: '1.2.0' });
    expect(api.get).toHaveBeenCalledTimes(2);
  });
});
