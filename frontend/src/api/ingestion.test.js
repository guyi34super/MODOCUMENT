import { describe, expect, it, vi, beforeEach } from 'vitest';
import { api } from './client';
import {
  createJobFromFile,
  databaseExportRequest,
  getSupportedTypes,
  listJobSummaries,
  needsIngestionWait,
  waitForJob,
} from './ingestion';

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

  it('asks for job summaries one page at a time', async () => {
    await listJobSummaries({ page: 2, size: 25 });
    expect(api.get).toHaveBeenCalledWith('/api/ingestion/jobs?view=summary&page=2&size=25');
  });

  it('asks for the supported types once per session, but retries a failure', async () => {
    api.get.mockRejectedValueOnce(new Error('down'));
    await expect(getSupportedTypes()).rejects.toThrow('down');
    api.get.mockResolvedValue({ accept: '.csv' });
    await getSupportedTypes();
    await getSupportedTypes();
    const calls = api.get.mock.calls.filter(([path]) => path === '/api/ingestion/supported-types');
    expect(calls).toHaveLength(2);
  });

  it('builds the vendor/host database export body (no JDBC URL), leaving out empty optionals', () => {
    const body = databaseExportRequest(
      {
        vendor: 'SQLSERVER',
        host: ' sql.bank.internal ',
        port: '1433',
        database: 'core',
        schema: '',
        sslmode: 'verify-full',
        username: '',
        password: '',
        table: 'Payroll',
        createTableIfMissing: false,
      },
      'Quarterly load'
    );
    expect(body).toEqual({
      vendor: 'SQLSERVER',
      host: 'sql.bank.internal',
      port: 1433,
      database: 'core',
      sslmode: 'verify-full',
      table: 'Payroll',
      tableName: 'Payroll',
      createTableIfMissing: false,
      reason: 'Quarterly load',
    });
    expect(body).not.toHaveProperty('jdbcUrl');
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
