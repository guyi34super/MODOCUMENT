import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getOverview } from '../api/platform';
import PageHeader from '../components/PageHeader';
import { ErrorBanner, KpiTile, Spinner } from '../components/ui';
import { BankStatusBadge, formatDateTime, formatPercent } from './PlatformCommon';

/**
 * The platform owner's landing page (ws21, `/platform`): every customer bank
 * with counts-only figures, plus totals. Nothing here is a bank's data — the
 * server answers numbers and identifying columns only.
 */
export default function PlatformOverviewPage() {
  const [overview, setOverview] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    getOverview()
      .then(setOverview)
      .catch((err) => setError(err.message));
  }, []);

  const totals = overview?.totals;
  const banks = overview?.banks ?? [];

  return (
    <div>
      <PageHeader
        title="Platform overview"
        subtitle="Every customer bank at a glance — counts only, never a bank's data."
        actions={
          <Link
            to="/platform/banks"
            className="rounded border border-line px-3 py-1.5 text-sm text-accent hover:bg-pill/50"
          >
            Manage banks
          </Link>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="mb-6 flex flex-wrap gap-3">
        <KpiTile label="Banks" value={totals?.banks} to="/platform/banks" />
        <KpiTile label="Active" value={totals?.activeBanks} tone="success" />
        <KpiTile
          label="Suspended"
          value={totals?.suspendedBanks}
          tone={totals?.suspendedBanks > 0 ? 'warning' : 'neutral'}
        />
        <KpiTile label="Users" value={totals?.users} hint="Enabled accounts in all banks" />
      </div>

      {!overview && !error && <Spinner />}
      {overview && banks.length === 0 && (
        <p className="py-6 text-sm text-muted">
          No customer banks yet. <Link to="/platform/banks" className="text-accent hover:underline">Onboard the first one</Link>.
        </p>
      )}
      {banks.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-line">
          <table aria-label="Banks" className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="bg-sidebar text-left">
                {['Code', 'Name', 'Status', 'Users', 'MFA enrolled', 'Jobs (7 days)',
                  'Failures (7 days)', 'Last sign-in'].map((label) => (
                  <th key={label} className="border-b border-line px-3 py-2 font-medium text-muted">
                    {label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {banks.map((bank) => (
                <tr key={bank.bankId} className="hover:bg-sidebar/60">
                  <td className="border-b border-line px-3 py-2 font-mono">
                    <Link to={`/platform/banks/${bank.bankId}`} className="text-accent hover:underline">
                      {bank.code}
                    </Link>
                  </td>
                  <td className="border-b border-line px-3 py-2">{bank.name}</td>
                  <td className="border-b border-line px-3 py-2">
                    <BankStatusBadge status={bank.status} />
                  </td>
                  <td className="border-b border-line px-3 py-2">{bank.users}</td>
                  <td className="border-b border-line px-3 py-2">
                    {formatPercent(bank.mfaEnrolledPct)}
                  </td>
                  <td className="border-b border-line px-3 py-2">{bank.jobs7d}</td>
                  <td className="border-b border-line px-3 py-2">{bank.failures7d}</td>
                  <td className="border-b border-line px-3 py-2">
                    {formatDateTime(bank.lastActivityAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
