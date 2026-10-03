import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
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

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<DashboardPage />} />
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
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
