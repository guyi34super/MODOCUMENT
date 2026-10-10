import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ToastProvider } from '../context/ToastContext';
import TemplateExtractionSection, { hintErrors } from './TemplateExtractionSection';

import { updateField, updateTemplate } from '../api/templates';

vi.mock('../api/templates', () => ({
  updateField: vi.fn(),
  updateTemplate: vi.fn(),
}));

const template = {
  templateId: 5,
  name: 'Statements',
  description: 'Monthly',
  extractionMode: null,
  defaultExtractionMode: 'AI_WITH_CODE_FALLBACK',
};

const accountField = {
  templateFieldsId: 11,
  templateId: 5,
  fieldName: 'Account Number',
  templateDataTypeId: 1,
  piiKind: 'ACCOUNT_NUMBER',
  aliases: ['Acct No', 'A/C'],
  labelPattern: null,
  valuePattern: null,
  dateFormats: [],
};

const dateField = {
  templateFieldsId: 12,
  templateId: 5,
  fieldName: 'Value Date',
  templateDataTypeId: 3,
  piiKind: null,
  aliases: [],
  labelPattern: null,
  valuePattern: null,
  dateFormats: [],
};

function renderSection(props = {}) {
  const handlers = { onTemplateSaved: vi.fn(), onFieldSaved: vi.fn() };
  render(
    <ToastProvider>
      <TemplateExtractionSection
        template={template}
        fields={[accountField, dateField]}
        typeName={(id) => ({ 1: 'Text', 2: 'Number', 3: 'Date' })[id]}
        {...handlers}
        {...props}
      />
    </ToastProvider>
  );
  return handlers;
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

describe('TemplateExtractionSection — extraction mode', () => {
  beforeEach(() => {
    updateTemplate.mockReset();
    updateField.mockReset();
  });

  it('shows the inherited bank default when the template has no mode of its own', () => {
    renderSection({ template: { ...template, defaultExtractionMode: 'CODE_ONLY' } });

    const select = screen.getByRole('combobox', { name: 'Extraction mode' });
    expect(select).toHaveValue('');
    expect(within(select).getByRole('option', { name: 'Inherit bank default (Code only)' })).toBeInTheDocument();
    expect(screen.getByText('Inherited')).toBeInTheDocument();
    // The help text explains the effective (inherited) mode.
    expect(select).toHaveAccessibleDescription(/The AI is never called/);
    expect(screen.getByRole('button', { name: 'Save mode' })).toBeDisabled();
  });

  it('saves a chosen mode with the stored name and description', async () => {
    updateTemplate.mockResolvedValue({ templateId: 5, extractionMode: 'CODE_ONLY' });
    const { onTemplateSaved } = renderSection();

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Extraction mode' }), 'CODE_ONLY');
    await userEvent.click(screen.getByRole('button', { name: 'Save mode' }));

    expect(updateTemplate).toHaveBeenCalledWith({
      templateId: 5,
      name: 'Statements',
      description: 'Monthly',
      extractionMode: 'CODE_ONLY',
    });
    expect(onTemplateSaved).toHaveBeenCalledWith({ extractionMode: 'CODE_ONLY' });
  });

  it('going back to "inherit" sends an explicit null', async () => {
    updateTemplate.mockResolvedValue({ templateId: 5, extractionMode: null });
    const { onTemplateSaved } = renderSection({ template: { ...template, extractionMode: 'AI_ONLY' } });

    const select = screen.getByRole('combobox', { name: 'Extraction mode' });
    expect(select).toHaveValue('AI_ONLY');
    await userEvent.selectOptions(select, '');
    await userEvent.click(screen.getByRole('button', { name: 'Save mode' }));

    expect(updateTemplate).toHaveBeenCalledWith(expect.objectContaining({ extractionMode: null }));
    expect(onTemplateSaved).toHaveBeenCalledWith({ extractionMode: null });
  });

  it('a maker/checker answer shows the change as pending, not saved', async () => {
    updateTemplate.mockResolvedValue({ pendingChangeId: 31, status: 'REQUESTED', changeType: 'TEMPLATE_UPDATE' });
    const { onTemplateSaved } = renderSection();

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Extraction mode' }), 'AI_ONLY');
    await userEvent.click(screen.getByRole('button', { name: 'Save mode' }));

    expect(await screen.findByText('Pending approval')).toBeInTheDocument();
    expect(onTemplateSaved).not.toHaveBeenCalled();
  });

  it('shows the server refusal inline on the select', async () => {
    updateTemplate.mockRejectedValue(badRequest("Unknown extraction mode 'MAGIC'"));
    renderSection();

    const select = screen.getByRole('combobox', { name: 'Extraction mode' });
    await userEvent.selectOptions(select, 'CODE_ONLY');
    await userEvent.click(screen.getByRole('button', { name: 'Save mode' }));

    expect(await screen.findByRole('alert')).toHaveTextContent("Unknown extraction mode 'MAGIC'");
    expect(select).toHaveAttribute('aria-invalid', 'true');
    expect(select).toHaveAccessibleDescription(/Unknown extraction mode/);
  });
});

describe('TemplateExtractionSection — field hints', () => {
  beforeEach(() => {
    updateTemplate.mockReset();
    updateField.mockReset();
  });

  it('summarises each field and round-trips aliases, patterns and date formats', async () => {
    updateField.mockImplementation((id, body) => Promise.resolve({ ...body }));
    const { onFieldSaved } = renderSection();

    expect(screen.getByText('2 aliases')).toBeInTheDocument();
    expect(screen.getByText('No hints — matched by its name')).toBeInTheDocument();

    const toggle = screen.getByRole('button', { name: 'Edit hints for Value Date' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    const panel = screen.getByRole('group', { name: 'Extraction hints for Value Date' });
    const aliases = within(panel).getByLabelText('Aliases');
    expect(aliases).toHaveFocus();
    await userEvent.type(aliases, 'Val Dt{enter}Value date (d/m/y){enter}');
    await userEvent.type(within(panel).getByLabelText('Label pattern'), 'Val(ue)?\\s*D(ate|t)');
    await userEvent.type(within(panel).getByLabelText('Value pattern'), '(\\d{{2}/\\d{{2}/\\d{{4})');
    await userEvent.type(within(panel).getByLabelText('Date formats'), 'dd/MM/yyyy{enter}d MMM yyyy');
    await userEvent.click(within(panel).getByRole('button', { name: 'Save hints' }));

    expect(updateField).toHaveBeenCalledWith(12, {
      templateFieldsId: 12,
      templateId: 5,
      fieldName: 'Value Date',
      templateDataTypeId: 3,
      piiKind: null,
      aliases: ['Val Dt', 'Value date (d/m/y)'],
      labelPattern: 'Val(ue)?\\s*D(ate|t)',
      valuePattern: '(\\d{2}/\\d{2}/\\d{4})',
      dateFormats: ['dd/MM/yyyy', 'd MMM yyyy'],
    });
    expect(onFieldSaved).toHaveBeenCalledWith(
      expect.objectContaining({ templateFieldsId: 12, dateFormats: ['dd/MM/yyyy', 'd MMM yyyy'] })
    );
    // Closed again, focus back on its toggle.
    expect(screen.queryByRole('group', { name: 'Extraction hints for Value Date' })).not.toBeInTheDocument();
    expect(toggle).toHaveFocus();
  });

  it('prefills the stored hints and keeps the PII tag when clearing them', async () => {
    updateField.mockResolvedValue({ ...accountField, aliases: [] });
    renderSection();

    await userEvent.click(screen.getByRole('button', { name: 'Edit hints for Account Number' }));
    const aliases = screen.getByLabelText('Aliases');
    expect(aliases).toHaveValue('Acct No\nA/C');
    await userEvent.clear(aliases);
    await userEvent.click(screen.getByRole('button', { name: 'Save hints' }));

    expect(updateField).toHaveBeenCalledWith(
      11,
      expect.objectContaining({
        piiKind: 'ACCOUNT_NUMBER',
        aliases: [],
        labelPattern: null,
        valuePattern: null,
        dateFormats: [],
      })
    );
  });

  it('puts each 400 message next to the hint it names', async () => {
    updateField.mockRejectedValue(
      badRequest(
        'labelPattern: the pattern nests quantifiers (catastrophic backtracking risk). ' +
          "dateFormats: 'qq/zz' is not a valid date pattern (use letters such as dd, MM, yyyy)."
      )
    );
    renderSection();

    await userEvent.click(screen.getByRole('button', { name: 'Edit hints for Value Date' }));
    await userEvent.type(screen.getByLabelText('Label pattern'), '(a+)+');
    await userEvent.type(screen.getByLabelText('Date formats'), 'qq/zz');
    await userEvent.click(screen.getByRole('button', { name: 'Save hints' }));

    const label = screen.getByLabelText('Label pattern');
    expect(await screen.findAllByRole('alert')).toHaveLength(2);
    expect(label).toHaveAttribute('aria-invalid', 'true');
    expect(label).toHaveAccessibleDescription(/nests quantifiers/);
    const dates = screen.getByLabelText('Date formats');
    expect(dates).toHaveAccessibleDescription(/'qq\/zz' is not a valid date pattern/);
    expect(screen.getByLabelText('Value pattern')).not.toHaveAttribute('aria-invalid');
    // The panel stays open with what was typed.
    expect(label).toHaveValue('(a+)+');

    // Editing the hint clears its error.
    await userEvent.type(label, 'x');
    expect(label).not.toHaveAttribute('aria-invalid');
  });

  it('a read-only (shared) template shows the settings without any way to change them', async () => {
    renderSection({ readOnly: true, template: { ...template, extractionMode: 'CODE_ONLY' } });

    expect(screen.getByRole('combobox', { name: 'Extraction mode' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save mode' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'View hints for Account Number' }));
    expect(screen.getByLabelText('Aliases')).toHaveAttribute('readonly');
    expect(screen.queryByRole('button', { name: 'Save hints' })).not.toBeInTheDocument();
  });

  it('asks for fields first when the template has none', () => {
    renderSection({ fields: [] });
    expect(screen.getByText('Add fields above to give them hints.')).toBeInTheDocument();
  });
});

describe('hintErrors', () => {
  it('splits a combined message by hint', () => {
    expect(hintErrors('aliases: at most 50 aliases per field. valuePattern: too long.')).toEqual({
      aliases: 'at most 50 aliases per field.',
      valuePattern: 'too long.',
    });
  });

  it('keeps a message naming no hint for the form', () => {
    expect(hintErrors('Request failed (500)')).toEqual({ form: 'Request failed (500)' });
    expect(hintErrors('')).toEqual({});
  });
});
