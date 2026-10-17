import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import RouteErrorBoundary from './components/RouteErrorBoundary';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import { Spinner } from './components/ui';

// v1.2.0: every page but the two a cold start lands on (sign-in, home) is
// its own chunk, fetched on first navigation. Layout wraps its <Outlet/> in
// <Suspense>; the standalone routes below carry their own.
const LoginCompletePage = lazy(() => import('./pages/LoginCompletePage'));
const TemplatesPage = lazy(() => import('./pages/TemplatesPage'));
const TemplateEditorPage = lazy(() => import('./pages/TemplateEditorPage'));
const ValidationRulesPage = lazy(() => import('./pages/ValidationRulesPage'));
const IngestionListPage = lazy(() => import('./pages/IngestionListPage'));
const IngestionJobPage = lazy(() => import('./pages/IngestionJobPage'));
const BlueprintListPage = lazy(() => import('./pages/BlueprintListPage'));
const BlueprintDetailPage = lazy(() => import('./pages/BlueprintDetailPage'));
const DocInstancesPage = lazy(() => import('./pages/DocInstancesPage'));
const DataValidationPage = lazy(() => import('./pages/DataValidationPage'));
const DataTemplateRedirect = lazy(() => import('./pages/DataTemplateRedirect'));
const DataMappingListPage = lazy(() => import('./pages/DataMappingListPage'));
const DocBuilderPage = lazy(() => import('./pages/DocBuilderPage'));
const ExtractionSourcesPage = lazy(() => import('./pages/ExtractionSourcesPage'));
const ExtractionJobsPage = lazy(() => import('./pages/ExtractionJobsPage'));
const ExtractionJobDetailPage = lazy(() => import('./pages/ExtractionJobDetailPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const AdminUsersPage = lazy(() => import('./pages/AdminUsersPage'));
const PendingChangesPage = lazy(() => import('./pages/PendingChangesPage'));
const PendingReleasesPage = lazy(() => import('./pages/PendingReleasesPage'));
const AdminRetentionPage = lazy(() => import('./pages/AdminRetentionPage'));
const AdminAlertsPage = lazy(() => import('./pages/AdminAlertsPage'));
const ForceChangePasswordPage = lazy(() => import('./pages/ForceChangePasswordPage'));
const PlatformOverviewPage = lazy(() => import('./pages/PlatformOverviewPage'));
const PlatformBanksPage = lazy(() => import('./pages/PlatformBanksPage'));
const PlatformBankDetailPage = lazy(() => import('./pages/PlatformBankDetailPage'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const PlatformAnnouncementsPage = lazy(() => import('./pages/PlatformAnnouncementsPage'));
const PlatformReleasesPage = lazy(() => import('./pages/PlatformReleasesPage'));
const IntegrationsOverviewPage = lazy(() => import('./pages/IntegrationsOverviewPage'));
const IntegrationConnectionsPage = lazy(() => import('./pages/IntegrationConnectionsPage'));
const IntegrationFlowsPage = lazy(() => import('./pages/IntegrationFlowsPage'));
const IntegrationFlowBuilderPage = lazy(() => import('./pages/IntegrationFlowBuilderPage'));
const IntegrationRunsPage = lazy(() => import('./pages/IntegrationRunsPage'));
const IntegrationRunDetailPage = lazy(() => import('./pages/IntegrationRunDetailPage'));
const IntegrationApiClientsPage = lazy(() => import('./pages/IntegrationApiClientsPage'));
const IntegrationAgentsPage = lazy(() => import('./pages/IntegrationAgentsPage'));
const IntegrationEgressRulesPage = lazy(() => import('./pages/IntegrationEgressRulesPage'));
const PlatformEgressRulesPage = lazy(() => import('./pages/PlatformEgressRulesPage'));
const AuditLogPage = lazy(() => import('./pages/AuditLogPage'));

/** ws21: the platform owner has no domain home page — its home is the console. */
function Home() {
  const { user } = useAuth();
  return user?.role === 'PLATFORM_ADMIN' ? <Navigate to="/platform" replace /> : <DashboardPage />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* OIDC landing: exchanges the refresh cookie for the access token. */}
      <Route
        path="/login/complete"
        element={
          <RouteErrorBoundary>
            <Suspense fallback={<Spinner />}>
              <LoginCompletePage />
            </Suspense>
          </RouteErrorBoundary>
        }
      />
      {/* ws09: standalone (no app chrome) — the only page a temporary
          password can use; ProtectedRoute routes flagged users here. */}
      <Route
        path="/force-password-change"
        element={
          <ProtectedRoute>
            <RouteErrorBoundary>
              <Suspense fallback={<Spinner />}>
                <ForceChangePasswordPage />
              </Suspense>
            </RouteErrorBoundary>
          </ProtectedRoute>
        }
      />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<Home />} />
        <Route path="/templates" element={<TemplatesPage />} />
        <Route path="/templates/new" element={<TemplateEditorPage />} />
        <Route path="/templates/:id" element={<TemplateEditorPage />} />
        <Route path="/validation" element={<ValidationRulesPage />} />
        <Route path="/ingestion" element={<IngestionListPage />} />
        <Route path="/ingestion/:id" element={<IngestionJobPage />} />
        <Route path="/creation/blueprints" element={<BlueprintListPage />} />
        <Route path="/creation/blueprints/:id" element={<BlueprintDetailPage />} />
        {/* ws34: step 2 (Validation: data templates) and step 3 (Map fields). */}
        <Route path="/creation/data-templates" element={<DataValidationPage />} />
        <Route path="/creation/data-templates/:id" element={<DataTemplateRedirect />} />
        <Route path="/creation/mapping" element={<DataMappingListPage />} />
        <Route path="/creation/documents" element={<DocInstancesPage />} />
        <Route path="/creation/documents/:id" element={<DocBuilderPage />} />
        <Route path="/extraction/sources" element={<ExtractionSourcesPage />} />
        <Route path="/extraction/jobs" element={<ExtractionJobsPage />} />
        <Route path="/extraction/jobs/:id" element={<ExtractionJobDetailPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        {/* Rendered for ADMINs; the server enforces user:admin regardless. */}
        <Route path="/admin/users" element={<AdminUsersPage />} />
        {/* ws10 approval queue for ADMIN + APPROVER (server: change:approve). */}
        <Route path="/admin/changes" element={<PendingChangesPage />} />
        {/* ws11 release queue for ADMIN + APPROVER (server: doc:release). */}
        <Route path="/approvals/releases" element={<PendingReleasesPage />} />
        <Route path="/admin/retention" element={<AdminRetentionPage />} />
        {/* ws18 security alerts for ADMINs (server: user:admin, bank-scoped). */}
        <Route path="/admin/alerts" element={<AdminAlertsPage />} />
        {/* v1.2.0 audit log viewer (server: audit:read — ADMIN, APPROVER;
            the platform variant for the PLATFORM_ADMIN of the platform bank). */}
        <Route path="/admin/audit" element={<AuditLogPage mode="bank" />} />
        <Route path="/platform/audit" element={<AuditLogPage mode="platform" />} />
        {/* ws21 platform console (server: platform:read / platform:banks). */}
        <Route path="/platform" element={<PlatformOverviewPage />} />
        <Route path="/platform/banks" element={<PlatformBanksPage />} />
        <Route path="/platform/banks/:id" element={<PlatformBankDetailPage />} />
        {/* Phase 2 (ws22): admin dashboard (server: metrics:read) and the
            settings screens (server: settings:read / platform:read). */}
        <Route path="/admin" element={<AdminDashboardPage />} />
        <Route path="/admin/settings" element={<SettingsPage mode="bank" />} />
        <Route path="/platform/settings" element={<SettingsPage mode="platform" />} />
        <Route path="/platform/banks/:id/settings" element={<SettingsPage mode="platformBank" />} />
        {/* ws24 platform console (server: platform:read / platform:announce,
            PLATFORM_ADMIN of the platform bank only). */}
        <Route path="/platform/announcements" element={<PlatformAnnouncementsPage />} />
        <Route path="/platform/releases" element={<PlatformReleasesPage />} />
        {/* ws25/ws26 integration hub (server: integration:admin; the runs
            pages also integration:run). */}
        <Route path="/admin/integrations" element={<IntegrationsOverviewPage />} />
        <Route path="/admin/integrations/connections" element={<IntegrationConnectionsPage />} />
        <Route path="/admin/integrations/flows" element={<IntegrationFlowsPage />} />
        <Route path="/admin/integrations/flows/new" element={<IntegrationFlowBuilderPage />} />
        <Route path="/admin/integrations/flows/:id" element={<IntegrationFlowBuilderPage />} />
        <Route path="/admin/integrations/runs" element={<IntegrationRunsPage />} />
        <Route path="/admin/integrations/runs/:id" element={<IntegrationRunDetailPage />} />
        <Route path="/admin/integrations/api-clients" element={<IntegrationApiClientsPage />} />
        <Route path="/admin/integrations/agents" element={<IntegrationAgentsPage />} />
        <Route path="/admin/integrations/egress-rules" element={<IntegrationEgressRulesPage />} />
        {/* ws25 platform console: egress approvals (server: platform:banks). */}
        <Route path="/platform/egress-rules" element={<PlatformEgressRulesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
