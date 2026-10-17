import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import DataMappingListPage from './DataMappingListPage';
import { expectAccessible } from '../test/a11y';

import { listBlueprints } from '../api/doccreation';

vi.mock('../api/doccreation', () => ({ listBlueprints: vi.fn() }));

function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creation/mapping']}>
      <Routes>
        <Route path="/creation/mapping" element={<DataMappingListPage />} />
        <Route path="/creation/blueprints/:id" element={<Where />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('DataMappingListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listBlueprints.mockResolvedValue([
      { id: 4, name: 'Loan form', status: 'READY', dataTemplateId: 3, dataTemplateName: 'Customer', mappedCount: 5, mappableCount: 7 },
      { id: 5, name: 'KYC form', status: 'READY', dataTemplateId: 3, dataTemplateName: 'Customer', mappedCount: 4, mappableCount: 4 },
      { id: 6, name: 'Car form', status: 'READY', dataTemplateId: null, dataTemplateName: null, mappedCount: 0, mappableCount: 0 },
    ]);
  });

  it('lists each PDF template with its data template and "x of y mapped"', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: 'PDF templates and their mapping' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Loan formCustomer5 of 7 mapped');
    expect(within(rows[0]).getByText('5 of 7 mapped')).toHaveClass('bg-amber-100');
    expect(within(rows[1]).getByText('4 of 4 mapped')).toHaveClass('bg-success-bg');
    expect(rows[2]).toHaveTextContent('Car formNone chosenNo data template yet');
    expect(within(rows[0]).getByRole('link', { name: 'Loan form' })).toHaveAttribute('href', '/creation/blueprints/4?step=mapping');
    expect(within(rows[2]).getByRole('link', { name: 'Map the fields of Car form' })).toHaveTextContent('Choose a data template');
    expectAccessible();
  });

  it('opens the template on its Map fields step', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('link', { name: 'Map the fields of KYC form' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/creation/blueprints/5?step=mapping');
  });
});
