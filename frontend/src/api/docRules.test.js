import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getRules, importPreview, importRules, saveRules, testRules, toRuleInput } from './docRules';
import { listInstances, validateInstance } from './doccreation';

function stubFetch(body = {}) {
  const fetchMock = vi.fn(() =>
    Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const call = (fetchMock, i = 0) => {
  const [path, opts] = fetchMock.mock.calls[i];
  return { path, method: opts.method, body: opts.body ? JSON.parse(opts.body) : undefined };
};

describe('api/docRules and the validation calls', () => {
  beforeEach(() => {
    localStorage.setItem('modoc.token', 'test-token');
  });

  it('reads, saves and tests the field rules of a blueprint', async () => {
    const fetchMock = stubFetch({ revision: 1 });
    await getRules(4);
    await saveRules(4, { baseRevision: 3, rules: [{ regionKey: 'a', ruleType: 'REQUIRED', config: {} }] });
    await testRules(4, { values: { a: 'x' } });
    await testRules(4, { values: {}, rules: [] });

    expect(call(fetchMock, 0)).toEqual({ path: '/api/doccreation/blueprints/4/rules', method: 'GET', body: undefined });
    expect(call(fetchMock, 1)).toEqual({
      path: '/api/doccreation/blueprints/4/rules',
      method: 'PUT',
      body: { baseRevision: 3, rules: [{ regionKey: 'a', ruleType: 'REQUIRED', config: {} }] },
    });
    expect(call(fetchMock, 2)).toEqual({ path: '/api/doccreation/blueprints/4/rules/test', method: 'POST', body: { values: { a: 'x' } } });
    expect(call(fetchMock, 3).body).toEqual({ values: {}, rules: [] });
  });

  it('previews and runs an import from the data sources or a template', async () => {
    const fetchMock = stubFetch({});
    await importPreview(4);
    await importPreview(4, 9);
    await importRules(4, { baseRevision: 2, mapping: { a: 'b' }, mode: 'MERGE' });
    expect(call(fetchMock, 0).path).toBe('/api/doccreation/blueprints/4/rules/import-preview');
    expect(call(fetchMock, 1).path).toBe('/api/doccreation/blueprints/4/rules/import-preview?templateId=9');
    expect(call(fetchMock, 2)).toEqual({
      path: '/api/doccreation/blueprints/4/rules/import',
      method: 'POST',
      body: { baseRevision: 2, mapping: { a: 'b' }, mode: 'MERGE' },
    });
  });

  it('validates a document and filters my documents by status', async () => {
    const fetchMock = stubFetch([]);
    await validateInstance(5);
    await listInstances({ status: 'VALIDATED' });
    await listInstances();
    await listInstances(3);
    expect(call(fetchMock, 0)).toEqual({ path: '/api/doccreation/instances/5/validate', method: 'POST', body: undefined });
    expect(call(fetchMock, 1).path).toBe('/api/doccreation/instances?status=VALIDATED');
    expect(call(fetchMock, 2).path).toBe('/api/doccreation/instances');
    expect(call(fetchMock, 3).path).toBe('/api/doccreation/instances?blueprintId=3');
  });

  it('turns a received rule back into a RuleInput', () => {
    expect(
      toRuleInput({
        id: 2,
        regionKey: 'dob',
        ruleType: 'DATE_RANGE',
        config: { Max: '-18y' },
        message: null,
        severity: 'ERROR',
        origin: 'IMPORTED',
        sourceTemplateId: 9,
        sourceTemplateName: 'Customers',
        sourceFieldName: 'date_of_birth',
        sortOrder: 0,
        orphan: false,
      })
    ).toEqual({
      regionKey: 'dob',
      ruleType: 'DATE_RANGE',
      config: { Max: '-18y' },
      message: null,
      severity: 'ERROR',
      origin: 'IMPORTED',
      sourceTemplateId: 9,
      sourceFieldName: 'date_of_birth',
    });
  });
});
