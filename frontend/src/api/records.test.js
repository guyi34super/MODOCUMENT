import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { getRecord, recordLabel, searchRecords } from './records';
import {
  addBlueprintSource,
  deleteBlueprintSource,
  fillFromRecord,
  getFillPreview,
  listBlueprintSources,
  saveBlueprintSource,
  upgradeInstance,
} from './doccreation';

vi.mock('./client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() },
  downloadFile: vi.fn(),
}));

describe('flow records API (contract §4)', () => {
  beforeEach(() => {
    for (const fn of Object.values(api)) {
      fn.mockReset();
      fn.mockResolvedValue({});
    }
  });

  it('searches records with the query, the optional field and a capped page size', async () => {
    await searchRecords({ templateId: 3, q: '  Jane Doe ', field: 'nic', page: 2, size: 500 });
    expect(api.get).toHaveBeenCalledWith('/api/doccreation/records?templateId=3&q=Jane+Doe&field=nic&page=2&size=100');
    await searchRecords({ templateId: '3', q: '' });
    expect(api.get).toHaveBeenLastCalledWith('/api/doccreation/records?templateId=3&page=0&size=20');
    await getRecord(12);
    expect(api.get).toHaveBeenLastCalledWith('/api/doccreation/records/12');
  });

  it('labels a record by its first values', () => {
    expect(recordLabel({ recordId: 1, values: { a: 'Jane', b: '', c: 'J12', d: 'x' } })).toBe('Jane · J12');
    expect(recordLabel({ recordId: 7, values: {} })).toBe('Record 7');
  });

  it('calls the blueprint source routes', async () => {
    await listBlueprintSources(4);
    expect(api.get).toHaveBeenCalledWith('/api/doccreation/blueprints/4/sources');
    await addBlueprintSource(4, { templateId: '3', role: 'PRIMARY', linkFieldPrimary: 'x', linkFieldThis: 'y' });
    expect(api.post).toHaveBeenCalledWith('/api/doccreation/blueprints/4/sources', { templateId: 3, role: 'PRIMARY' });
    await addBlueprintSource(4, { templateId: 7, role: 'LINKED', linkFieldPrimary: 'nic', linkFieldThis: 'national_id' });
    expect(api.post).toHaveBeenLastCalledWith('/api/doccreation/blueprints/4/sources', {
      templateId: 7,
      role: 'LINKED',
      linkFieldPrimary: 'nic',
      linkFieldThis: 'national_id',
    });
    await saveBlueprintSource(4, 5, { mapping: { a: 'b' } });
    expect(api.put).toHaveBeenCalledWith('/api/doccreation/blueprints/4/sources/5', { mapping: { a: 'b' } });
    await deleteBlueprintSource(4, 5);
    expect(api.del).toHaveBeenCalledWith('/api/doccreation/blueprints/4/sources/5');
  });

  it('calls the fill and upgrade routes', async () => {
    await getFillPreview(5, 12);
    expect(api.get).toHaveBeenCalledWith('/api/doccreation/instances/5/fill-preview?recordId=12');
    const body = { recordId: 12, linkedRecordIds: { 7: 91 }, fields: ['dob'], overwrite: 'ALL' };
    await fillFromRecord(5, body);
    expect(api.post).toHaveBeenCalledWith('/api/doccreation/instances/5/fill-from-record', body);
    await upgradeInstance(5);
    expect(api.post).toHaveBeenLastCalledWith('/api/doccreation/instances/5/upgrade');
  });
});
