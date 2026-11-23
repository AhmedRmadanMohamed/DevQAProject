import { ProjectBrainHost } from '../../services/service-01-project-brain/ProjectBrainHost';
import { ErrorBoundary } from '../../../core/frontend/ui-components/src/ErrorBoundary';

export function App() {
  return (
    <ErrorBoundary>
      <ProjectBrainHost />
    </ErrorBoundary>
  );
}
