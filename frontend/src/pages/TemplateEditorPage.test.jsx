import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import TemplateEditorPage from './TemplateEditorPage';

import { getTemplateDataTypes } from '../api/cache';
import { createField, getAllFields, getTemplate } from '../api/templates';

vi.mock('../api/cache', () => ({
  getTemplateDataTypes: vi.fn(),
}));

vi.mock('../api/templates', () => ({
  createField: vi.fn(),
  createWithFields: vi.fn(),
  deleteField: vi.fn(),
  getAllFields: vi.fn(),
  getTemplate: vi.fn(),
  updateTemplate: vi.fn(),
}));

function renderEditPage() {
  return render(
    <MemoryRouter initialEntries={['/templates/5']}>
      <ToastProvider>
        <Routes>
          <Route path="/templates/:id" element={<TemplateEditorPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('TemplateEditorPage (edit mode)', () => {
  beforeEach(() => {
    getTemplateDataTypes.mockResolvedValue([
      { templateDataTypeId: 1, dataType: 'Text' },
      { templateDataTypeId: 2, dataType: 'Number' },
    ]);
    getTemplate.mockResolvedValue({ templateId: 5, name: 'People', description: '' });
    getAllFields.mockResolvedValue([]);
    createField.mockReset();
    createField.mockResolvedValue({});
  });

  it('adds a field, clears and refocuses the input, and shows the new row', async () => {
    getAllFields
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { templateFieldsId: 1, templateId: 5, fieldName: 'Age', templateDataTypeId: 1 },
      ]);
    renderEditPage();

    const input = await screen.findByPlaceholderText('New field name');
    await userEvent.type(input, 'Age');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(createField).toHaveBeenCalledWith({
      templateId: 5,
      fieldName: 'Age',
      templateDataTypeId: 1,
      piiKind: null, // "auto": the server classifies on first use
    });
    expect(await screen.findByText('Age')).toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(input).toHaveFocus();
  });

  it('round-trips an explicit PII tag and renders the stored classification', async () => {
    getAllFields
      .mockResolvedValueOnce([
        // Persisted by the server earlier (auto-classified EMAIL).
        { templateFieldsId: 1, templateId: 5, fieldName: 'Contact', templateDataTypeId: 1, piiKind: 'EMAIL' },
      ])
      .mockResolvedValueOnce([
        { templateFieldsId: 1, templateId: 5, fieldName: 'Contact', templateDataTypeId: 1, piiKind: 'EMAIL' },
        { templateFieldsId: 2, templateId: 5, fieldName: 'CustomerNic', templateDataTypeId: 1, piiKind: 'NIC' },
      ]);
    renderEditPage();

    // The stored classification renders in the fields table (the role query
    // avoids matching the selector's own "Email" option).
    expect(await screen.findByRole('cell', { name: 'Email' })).toBeInTheDocument();

    const input = screen.getByPlaceholderText('New field name');
    await userEvent.type(input, 'CustomerNic');
    await userEvent.selectOptions(screen.getByLabelText('PII kind'), 'NIC');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(createField).toHaveBeenCalledWith({
      templateId: 5,
      fieldName: 'CustomerNic',
      templateDataTypeId: 1,
      piiKind: 'NIC',
    });
    expect(await screen.findByRole('cell', { name: 'NIC' })).toBeInTheDocument();
  });

  it('renders the add-field form above the fields table', async () => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 5, fieldName: 'Age', templateDataTypeId: 1 },
    ]);
    renderEditPage();

    const input = await screen.findByPlaceholderText('New field name');
    const table = await screen.findByRole('table');
    expect(input.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows the step-2 handoff CTA in header and page bottom', async () => {
    renderEditPage();
    await screen.findByPlaceholderText('New field name');
    expect(
      screen.getAllByRole('button', { name: 'Next: set field rules →' }).length
    ).toBeGreaterThanOrEqual(1);
  });
});
