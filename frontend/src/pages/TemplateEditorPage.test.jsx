import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import TemplateEditorPage from './TemplateEditorPage';

import { getTemplateDataTypes } from '../api/cache';
import { createField, createWithFields, getAllFields, getTemplate } from '../api/templates';

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

function renderAs(role, entry, path) {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify({ userId: 5, role }));
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path={path} element={<TemplateEditorPage />} />
            <Route path="/templates/:id" element={<div>EDITOR OF CREATED</div>} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('TemplateEditorPage (ws22 bank-shared templates)', () => {
  beforeEach(() => {
    getTemplateDataTypes.mockResolvedValue([{ templateDataTypeId: 1, dataType: 'Text' }]);
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 8, fieldName: 'Amount', templateDataTypeId: 1 },
    ]);
    createWithFields.mockReset().mockResolvedValue({ templateId: 9, visibility: 'BANK' });
  });

  it('?visibility=BANK preselects sharing for an administrator and sends it', async () => {
    renderAs('ADMIN', '/templates/new?visibility=BANK', '/templates/new');

    expect(await screen.findByRole('heading', { name: 'New shared template' })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Share with bank' })).toHaveAttribute(
      'aria-checked',
      'true'
    );
    await userEvent.type(screen.getByLabelText('Name'), 'KYC');
    await userEvent.type(screen.getByPlaceholderText('Field name'), 'Amount');
    await userEvent.click(screen.getByRole('button', { name: 'Create template' }));

    await waitFor(() =>
      expect(createWithFields).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'KYC', visibility: 'BANK' })
      )
    );
  });

  it('creates a private template for a non-administrator even from the shared link', async () => {
    renderAs('READ_WRITE', '/templates/new?visibility=BANK', '/templates/new');

    expect(await screen.findByText(/Only an administrator can share a template/)).toBeInTheDocument();
    expect(screen.queryByRole('switch', { name: 'Share with bank' })).toBeNull();
    await userEvent.type(screen.getByLabelText('Name'), 'Mine');
    await userEvent.type(screen.getByPlaceholderText('Field name'), 'Amount');
    await userEvent.click(screen.getByRole('button', { name: 'Create template' }));

    await waitFor(() =>
      expect(createWithFields).toHaveBeenCalledWith(
        expect.objectContaining({ visibility: 'PRIVATE' })
      )
    );
  });

  it('opens a colleague\'s shared template read-only', async () => {
    getTemplate.mockResolvedValue({
      templateId: 8,
      name: 'KYC form',
      description: 'bank-wide',
      visibility: 'BANK',
      ownedByMe: false,
      editable: false,
      ownerName: 'Grace Admin',
    });
    renderAs('READ_WRITE', '/templates/8', '/templates/:id');

    expect(await screen.findByText('Shared template — read only')).toBeInTheDocument();
    expect(screen.getByText(/Grace Admin shared this template/)).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveAttribute('readonly');
    expect(screen.queryByRole('button', { name: 'Save details' })).toBeNull();
    expect(screen.queryByPlaceholderText('New field name')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    expect(screen.getByText('Amount')).toBeInTheDocument();
  });

  it('keeps a shared template editable for an administrator, with a reminder', async () => {
    getTemplate.mockResolvedValue({
      templateId: 8,
      name: 'KYC form',
      description: '',
      visibility: 'BANK',
      ownedByMe: false,
      editable: true,
      ownerName: 'Grace Admin',
    });
    renderAs('ADMIN', '/templates/8', '/templates/:id');

    expect(await screen.findByPlaceholderText('New field name')).toBeInTheDocument();
    expect(screen.getByText(/changes affect all of them/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save details' })).toBeInTheDocument();
  });
});

