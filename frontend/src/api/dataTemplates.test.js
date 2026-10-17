import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  copyFromFlowTemplate,
  copyPreview,
  createDataTemplate,
  deleteDataTemplate,
  getDataMapping,
  getDataTemplate,
  isCompatible,
  listDataTemplates,
  saveDataMapping,
  saveDataTemplateFields,
  saveDataTemplateRules,
  setBlueprintDataTemplate,
  testDataTemplateRules,
  toDataRuleInput,
  toFieldInput,
  updateDataTemplate,
} from './dataTemplates';

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

describe('api/dataTemplates', () => {
  beforeEach(() => {
    localStorage.setItem('modoc.token', 'test-token');
  });

  it('lists, creates, reads, renames and deletes data templates', async () => {
    const fetchMock = stubFetch({ id: 1 });
    await listDataTemplates();
    await createDataTemplate({ name: 'Customer', description: '' });
    await getDataTemplate(1);
    await updateDataTemplate(1, { name: 'Client', description: 'KYC' });
    await deleteDataTemplate(1);

    expect(call(fetchMock, 0)).toEqual({ path: '/api/doccreation/data-templates', method: 'GET', body: undefined });
    expect(call(fetchMock, 1)).toEqual({ path: '/api/doccreation/data-templates', method: 'POST', body: { name: 'Customer' } });
    expect(call(fetchMock, 2).path).toBe('/api/doccreation/data-templates/1');
    expect(call(fetchMock, 3)).toEqual({
      path: '/api/doccreation/data-templates/1',
      method: 'PUT',
      body: { name: 'Client', description: 'KYC' },
    });
    expect(call(fetchMock, 4).method).toBe('DELETE');
  });

  it('saves fields and rules with the base revision and tests sample values', async () => {
    const fetchMock = stubFetch({ revision: 2 });
    await saveDataTemplateFields(3, { baseRevision: 1, fields: [{ name: 'a', label: 'A', kind: 'TEXT' }] });
    await saveDataTemplateRules(3, { baseRevision: 2, rules: [] });
    await testDataTemplateRules(3, { values: { a: 'x' } });
    await testDataTemplateRules(3, { values: {}, rules: [] });

    expect(call(fetchMock, 0)).toEqual({
      path: '/api/doccreation/data-templates/3/fields',
      method: 'PUT',
      body: { baseRevision: 1, fields: [{ name: 'a', label: 'A', kind: 'TEXT' }] },
    });
    expect(call(fetchMock, 1).path).toBe('/api/doccreation/data-templates/3/rules');
    expect(call(fetchMock, 2)).toEqual({
      path: '/api/doccreation/data-templates/3/rules/test',
      method: 'POST',
      body: { values: { a: 'x' } },
    });
    expect(call(fetchMock, 3).body).toEqual({ values: {}, rules: [] });
  });

  it('previews and copies a flow template', async () => {
    const fetchMock = stubFetch({});
    await copyPreview(9);
    await copyFromFlowTemplate({ templateId: '9', name: 'Customers', fields: ['full_name'] });
    expect(call(fetchMock, 0).path).toBe('/api/doccreation/data-templates/copy-preview?templateId=9');
    expect(call(fetchMock, 1)).toEqual({
      path: '/api/doccreation/data-templates/from-flow-template',
      method: 'POST',
      body: { templateId: 9, name: 'Customers', fields: ['full_name'] },
    });
  });

  it('reads and saves a PDF template’s data template and mapping', async () => {
    const fetchMock = stubFetch({});
    await getDataMapping(4);
    await setBlueprintDataTemplate(4, '7');
    await setBlueprintDataTemplate(4, '');
    await saveDataMapping(4, { full_name: 'name' });
    expect(call(fetchMock, 0)).toEqual({ path: '/api/doccreation/blueprints/4/data-mapping', method: 'GET', body: undefined });
    expect(call(fetchMock, 1)).toEqual({
      path: '/api/doccreation/blueprints/4/data-template',
      method: 'PUT',
      body: { dataTemplateId: 7 },
    });
    expect(call(fetchMock, 2).body).toEqual({ dataTemplateId: null });
    expect(call(fetchMock, 3)).toEqual({
      path: '/api/doccreation/blueprints/4/data-mapping',
      method: 'PUT',
      body: { mapping: { full_name: 'name' } },
    });
  });

  it('turns received fields and rules back into inputs and knows the compatible kinds', () => {
    expect(
      toDataRuleInput({ id: 3, fieldName: 'dob', ruleType: 'DATE_RANGE', config: { Max: '-18y' }, origin: 'IMPORTED', sourceTemplateId: 9, sourceFieldName: 'date_of_birth', sortOrder: 0 })
    ).toEqual({
      fieldName: 'dob',
      ruleType: 'DATE_RANGE',
      config: { Max: '-18y' },
      message: null,
      severity: 'ERROR',
      origin: 'IMPORTED',
      sourceTemplateId: 9,
      sourceFieldName: 'date_of_birth',
    });
    expect(toFieldInput({ name: 'a', label: 'A', kind: 'TEXT', piiKind: null, props: null, sortOrder: 0 })).toEqual({
      name: 'a',
      label: 'A',
      kind: 'TEXT',
    });
    expect(isCompatible('TEXT', 'DATE')).toBe(true);
    expect(isCompatible('MULTILINE', 'NUMBER')).toBe(false);
    expect(isCompatible('SIGNATURE', 'IMAGE')).toBe(true);
    expect(isCompatible('CHECKBOX', 'TEXT')).toBe(false);
  });
});
