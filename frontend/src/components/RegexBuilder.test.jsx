import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import RegexBuilder, { buildPattern, escapeLiteral, PRESETS } from './RegexBuilder';

describe('buildPattern', () => {
  it('escapes exact text literals', () => {
    expect(buildPattern([{ type: 'text', value: 'PP.1' }])).toBe('^PP\\.1$');
  });

  it('composes character parts with counts', () => {
    expect(buildPattern([{ type: 'digits', countMode: 'exact', n: 4 }])).toBe('^\\d{4}$');
    expect(buildPattern([{ type: 'letters', countMode: 'oneOrMore' }])).toBe('^[A-Za-z]+$');
    expect(buildPattern([{ type: 'lettersDigits', countMode: 'range', n: 2, m: 5 }])).toBe(
      '^[A-Za-z0-9]{2,5}$'
    );
  });

  it('builds alternations from a comma list, escaping each option', () => {
    expect(buildPattern([{ type: 'oneOf', value: 'Yes, No, N/A' }])).toBe('^(Yes|No|N/A)$');
  });

  it('joins parts in order', () => {
    const pattern = buildPattern([
      { type: 'text', value: 'INV-' },
      { type: 'digits', countMode: 'exact', n: 4 },
    ]);
    expect(pattern).toBe('^INV-\\d{4}$');
    expect(new RegExp(pattern).test('INV-2026')).toBe(true);
    expect(new RegExp(pattern).test('INV-26')).toBe(false);
  });

  it('returns empty for no usable parts', () => {
    expect(buildPattern([{ type: 'text', value: '' }])).toBe('');
  });
});

describe('escapeLiteral', () => {
  it('escapes regex metacharacters', () => {
    expect(new RegExp(`^${escapeLiteral('a.b(c)*')}$`).test('a.b(c)*')).toBe(true);
  });
});

describe('RegexBuilder component', () => {
  it('selecting a preset emits its pattern and shows the example', async () => {
    const onChange = vi.fn();
    render(<RegexBuilder pattern="" onChange={onChange} />);

    const email = PRESETS.find((p) => p.key === 'email');
    await userEvent.selectOptions(screen.getByLabelText('What should this field look like?'), 'email');
    expect(onChange).toHaveBeenLastCalledWith(email.pattern);
    expect(screen.getByText(email.example)).toBeInTheDocument();
  });

  it('custom builder composes a pattern from parts', async () => {
    const onChange = vi.fn();
    render(<RegexBuilder pattern="" onChange={onChange} />);

    await userEvent.click(screen.getByRole('tab', { name: 'Custom builder' }));
    await userEvent.type(screen.getByLabelText('Part 1 value'), 'ID');
    expect(onChange).toHaveBeenLastCalledWith('^ID$');

    await userEvent.click(screen.getByText('+ Add part'));
    await userEvent.selectOptions(screen.getByLabelText('Part 2 count'), 'exact');
    expect(onChange).toHaveBeenLastCalledWith('^ID\\d{1}$');
  });

  it('the tester is collapsed by default and reports matches once opened', async () => {
    render(<RegexBuilder pattern="^\d+$" onChange={() => {}} />);

    expect(
      screen.queryByPlaceholderText('Type a value this field could contain…')
    ).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Test this pattern' }));

    const sample = screen.getByPlaceholderText('Type a value this field could contain…');
    await userEvent.type(sample, '123');
    expect(screen.getByText('✓ Matches')).toBeInTheDocument();

    await userEvent.type(sample, 'a');
    expect(screen.getByText("✗ Doesn't match")).toBeInTheDocument();
  });
});
