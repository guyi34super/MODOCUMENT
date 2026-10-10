import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDashboard } from '../api/dashboard';
import PageHeader from '../components/PageHeader';
import Modal from '../components/Modal';
import CreateUserForm from '../components/CreateUserForm';
import InviteUserDialog from '../components/InviteUserDialog';
import { Button, ErrorBanner, KpiTile, Spinner } from '../components/ui';

const sum = (counts) =>
  counts ? Object.values(counts).reduce((total, n) => total + (Number(n) || 0), 0) : null;

const nonZero = (counts, labels) =>
  Object.entries(counts || {})
    .filter(([, n]) => Number(n) > 0)
    .map(([key, n]) => `${n} ${labels?.[key] || key.toLowerCase().replace(/_/g, ' ')}`)
    .join(' · ');

function formatDateTime(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

/** "—" stays "—": a counter the backend does not track yet is null, not zero. */
const tracked = (value) => (value === null || value === undefined ? null : value);

function Section({ title, children }) {
  return (
    <section className="mb-6" aria-label={title}>
      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">{title}</h2>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">{children}</div>
    </section>
  );
}

/**
 * The bank administrator's dashboard (phase 2, ws22): counts for the
 * caller's bank from GET /api/admin/dashboard (cached 30 s server-side) as
 * KPI tiles (`data-testid="kpi-<metric>"`, value in `kpi-value`), plus the
 * quick actions an administrator needs most: add a user, invite one (with a
 * copyable invitation — there is no email channel), create a bank-shared
 * template, review approvals. Counters the backend does not track yet
 * (null — the integration hub's until its tables exist) show "—".
 */
export default function AdminDashboardPage() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [dialog, setDialog] = useState(null); // 'add' | 'invite' | null

  const load = useCallback(async () => {
    try {
      setData(await getDashboard());
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const users = data?.users;
  const approvals = data?.approvals;
  const ingestion = data?.ingestion;
  const alerts = data?.alerts;
  const retention = data?.retention;
  const templates = data?.templates;
  const integrations = data?.integrations;
  const severity = alerts?.last7dBySeverity;
  const urgentAlerts = (Number(severity?.CRITICAL) || 0) + (Number(severity?.HIGH) || 0);
  const mfaPct =
    users && users.total > 0 ? Math.round((users.mfaEnrolled / users.total) * 100) : null;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Dashboard' }]}
        title="Admin dashboard"
        subtitle={data?.bank?.name ? `${data.bank.name} at a glance` : 'Your bank at a glance'}
        actions={
          <Button variant="secondary" onClick={load}>
            Refresh
          </Button>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <section aria-label="Quick actions" className="mb-6 flex flex-wrap gap-2">
        <Button onClick={() => setDialog('add')}>Add user</Button>
        <Button variant="secondary" onClick={() => setDialog('invite')}>
          Invite
        </Button>
        <Button variant="secondary" onClick={() => navigate('/templates/new?visibility=BANK')}>
          New shared template
        </Button>
        <Button variant="secondary" onClick={() => navigate('/admin/changes')}>
          Review approvals
        </Button>
      </section>

      {!data && !error && <Spinner label="Loading the dashboard…" />}
      {data && (
        <>
          <Section title="People">
            <KpiTile
              testId="kpi-users-active"
              label="Active users"
              value={users?.active}
              hint={`${users?.total ?? 0} accounts · ${users?.activeLast7d ?? 0} signed in this week`}
              to="/admin/users"
            />
            <KpiTile
              testId="kpi-users-locked"
              label="Locked accounts"
              value={users?.locked}
              tone={users?.locked > 0 ? 'warning' : 'neutral'}
              hint={`${users?.mustChangePassword ?? 0} still on a temporary password`}
              to="/admin/users"
            />
            <KpiTile
              testId="kpi-users-disabled"
              label="Disabled users"
              value={users?.disabled}
              to="/admin/users"
            />
            <KpiTile
              testId="kpi-mfa-enrolment"
              label="MFA enrolment"
              value={mfaPct === null ? null : `${mfaPct}%`}
              hint={`${users?.mfaEnrolled ?? 0} of ${users?.total ?? 0} accounts`}
              tone={mfaPct !== null && mfaPct < 100 ? 'warning' : 'success'}
            />
            <KpiTile
              testId="kpi-admins-without-mfa"
              label="Admins without MFA"
              value={users?.adminsWithoutMfa}
              tone={users?.adminsWithoutMfa > 0 ? 'danger' : 'success'}
              hint="They are made to enrol at their next sign-in"
              to="/admin/users"
            />
          </Section>

          <Section title="Approvals & settings">
            <KpiTile
              testId="kpi-pending-approvals"
              label="Pending approvals"
              value={approvals?.pendingChanges}
              tone={approvals?.pendingChanges > 0 ? 'warning' : 'neutral'}
              hint="Changes waiting for a second administrator"
              to="/admin/changes"
            />
            <KpiTile
              testId="kpi-pending-releases"
              label="Documents to release"
              value={approvals?.pendingReleases}
              to="/approvals/releases"
            />
            <KpiTile
              testId="kpi-pending-security-settings"
              label="Pending security settings"
              value={data.settings?.pendingSecurityChanges}
              hint={
                data.settings?.clamped > 0
                  ? `${data.settings.clamped} clamped by the platform`
                  : 'Setting changes awaiting approval'
              }
              to="/admin/settings"
            />
          </Section>

          <Section title="Documents">
            <KpiTile
              testId="kpi-ingestion-today"
              label="Uploads today"
              value={sum(ingestion?.today?.byStatus)}
              hint={nonZero(ingestion?.today?.byStatus) || 'No uploads yet today'}
            />
            <KpiTile
              testId="kpi-ingestion-7d"
              label="Uploads (7 days)"
              value={sum(ingestion?.last7d?.byStatus)}
              hint={nonZero(ingestion?.last7d?.byStatus) || undefined}
            />
            <KpiTile
              testId="kpi-ingestion-failures"
              label="Failed uploads (7 days)"
              value={ingestion?.failures7d}
              tone={ingestion?.failures7d > 0 ? 'danger' : 'neutral'}
            />
            <KpiTile
              testId="kpi-extraction-7d"
              label="Extraction jobs (7 days)"
              value={sum(data.extraction?.last7d?.byStatus)}
              hint={nonZero(data.extraction?.last7d?.byStatus) || undefined}
            />
            <KpiTile
              testId="kpi-parsed-by-ai"
              label="Parsed by AI"
              value={ingestion?.parsedBy?.AI}
            />
            <KpiTile
              testId="kpi-parsed-by-code"
              label="Parsed by code"
              value={ingestion?.parsedBy?.CODE}
              hint="Code-based extraction (fallback or code-only templates)"
            />
            <KpiTile
              testId="kpi-parsed-by-ai-partial"
              label="AI with code fallback"
              value={ingestion?.parsedBy?.AI_PARTIAL}
              hint="Some batches re-parsed by code"
            />
            <KpiTile
              testId="kpi-templates-private"
              label="Private templates"
              value={templates?.private}
              to="/templates"
            />
            <KpiTile
              testId="kpi-templates-shared"
              label="Shared templates"
              value={templates?.shared}
              hint="Usable by everyone in the bank"
              to="/templates"
            />
          </Section>

          <Section title="Security & data">
            <KpiTile
              testId="kpi-alerts-7d"
              label="Security alerts (7 days)"
              value={sum(severity)}
              tone={urgentAlerts > 0 ? 'danger' : 'neutral'}
              hint={nonZero(severity) || 'None raised'}
              to="/admin/alerts"
            />
            <KpiTile
              testId="kpi-retention-last-run"
              label="Retention purge"
              value={formatDateTime(retention?.lastRunAt) || 'Never'}
              hint={
                retention
                  ? `${retention.lastPurged ?? 0} items purged · ${retention.days}-day retention${
                      retention.enabled ? '' : ' · scheduled purge OFF'
                    }`
                  : undefined
              }
              tone={retention && !retention.enabled ? 'warning' : 'neutral'}
              to="/admin/retention"
            />
            <KpiTile
              testId="kpi-version"
              label="Running version"
              value={data.version?.version}
              hint={data.version?.commit ? `commit ${data.version.commit}` : undefined}
            />
          </Section>

          <Section title="Integrations">
            <KpiTile
              testId="kpi-integration-runs"
              label="Integration runs (24 h)"
              value={tracked(integrations?.runs24h?.ok)}
              hint={
                tracked(integrations?.runs24h?.failed) === null
                  ? 'Available with the integration hub'
                  : `${integrations.runs24h.ok ?? 0} ok · ${integrations.runs24h.failed} failed`
              }
              tone={integrations?.runs24h?.failed > 0 ? 'danger' : 'neutral'}
              to="/admin/integrations/runs"
            />
            <KpiTile
              testId="kpi-integration-dead-letters"
              label="Dead letters"
              value={tracked(integrations?.deadLetters)}
              hint="Deliveries that ran out of retries"
              tone={integrations?.deadLetters > 0 ? 'danger' : 'neutral'}
              to="/admin/integrations/runs"
            />
            <KpiTile
              testId="kpi-agents-offline"
              label="Agents offline"
              value={tracked(integrations?.agentsOffline)}
              tone={integrations?.agentsOffline > 0 ? 'warning' : 'neutral'}
              to="/admin/integrations/agents"
            />
            <KpiTile
              testId="kpi-api-calls"
              label="API calls (24 h)"
              value={tracked(integrations?.apiCalls24h)}
              hint="Integration API calls by your API clients"
              to="/admin/integrations/api-clients"
            />
          </Section>

          <p className="text-xs text-muted">
            Numbers as of {formatDateTime(data.generatedAt) || 'now'} — refreshed at most every
            30 seconds.
          </p>
        </>
      )}

      <Modal
        open={dialog === 'add'}
        onClose={() => setDialog(null)}
        title="Add user"
        description="A second administrator approves new accounts before they can sign in."
      >
        <CreateUserForm
          onSubmitted={() => {
            setDialog(null);
            load();
          }}
        />
      </Modal>
      <InviteUserDialog
        open={dialog === 'invite'}
        onClose={() => setDialog(null)}
        bankName={data?.bank?.name}
        onInvited={load}
      />
    </div>
  );
}
