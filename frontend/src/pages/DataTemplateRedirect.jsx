import { Navigate, useParams } from 'react-router-dom';

/** `/creation/data-templates/:id` → the Validation page with that template chosen (contract §6). */
export default function DataTemplateRedirect() {
  const { id } = useParams();
  return <Navigate replace to={`/creation/data-templates?template=${encodeURIComponent(id)}`} />;
}
