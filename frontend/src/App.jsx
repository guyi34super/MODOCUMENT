import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import LoginCompletePage from './pages/LoginCompletePage';
import DashboardPage from './pages/DashboardPage';
import TemplatesPage from './pages/TemplatesPage';
import TemplateEditorPage from './pages/TemplateEditorPage';
import ValidationRulesPage from './pages/ValidationRulesPage';
import IngestionListPage from './pages/IngestionListPage';
import IngestionJobPage from './pages/IngestionJobPage';
import BlueprintListPage from './pages/BlueprintListPage';
import BlueprintDetailPage from './pages/BlueprintDetailPage';
import DocInstancesPage from './pages/DocInstancesPage';
import DocBuilderPage from './pages/DocBuilderPage';
import ExtractionSourcesPage from './pages/ExtractionSourcesPage';
import ExtractionJobsPage from './pages/ExtractionJobsPage';
import ExtractionJobDetailPage from './pages/ExtractionJobDetailPage';
import ProfilePage from './pages/ProfilePage';
import AdminUsersPage from './pages/AdminUsersPage';
import PendingChangesPage from './pages/PendingChangesPage';
import PendingReleasesPage from './pages/PendingReleasesPage';
import AdminRetentionPage from './pages/AdminRetentionPage';
import AdminAlertsPage from './pages/AdminAlertsPage';
import ForceChangePasswordPage from './pages/ForceChangePasswordPage';
import PlatformOverviewPage from './pages/PlatformOverviewPage';
import PlatformBanksPage from './pages/PlatformBanksPage';
import PlatformBankDetailPage from './pages/PlatformBankDetailPage';
import AdminDashboardPage from './pages/AdminDashboardPage';
import SettingsPage from './pages/SettingsPage';
import PlatformAnnouncementsPage from './pages/PlatformAnnouncementsPage';
import PlatformReleasesPage from './pages/PlatformReleasesPage';
import IntegrationsOverviewPage from './pages/IntegrationsOverviewPage';
import IntegrationConnectionsPage from './pages/IntegrationConnectionsPage';
import IntegrationFlowsPage from './pages/IntegrationFlowsPage';
import IntegrationFlowBuilderPage from './pages/IntegrationFlowBuilderPage';
import IntegrationRunsPage from './pages/IntegrationRunsPage';
import IntegrationRunDetailPage from './pages/IntegrationRunDetailPage';
import IntegrationApiClientsPage from './pages/IntegrationApiClientsPage';
import IntegrationAgentsPage from './pages/IntegrationAgentsPage';
import IntegrationEgressRulesPage from './pages/IntegrationEgressRulesPage';
import PlatformEgressRulesPage from './pages/PlatformEgressRulesPage';

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
      <Route path="/login/complete" element={<LoginCompletePage />} />
      {/* ws09: standalone (no app chrome) — the only page a temporary
          password can use; ProtectedRoute routes flagged users here. */}
      <Route
        path="/force-password-change"
        element={
          <ProtectedRoute>
            <ForceChangePasswordPage />
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
