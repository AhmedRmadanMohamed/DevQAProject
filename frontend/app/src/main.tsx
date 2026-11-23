import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { App } from './App';
import { installProjectBrainAdapter } from '../../../core/frontend/jcef-bridge/src/bridge';

// Register all Kotlin callbacks BEFORE the IDE sends its initial project model.
const adapter = installProjectBrainAdapter();
const mount = document.getElementById('app-root');
if (!mount) throw new Error('DevQA React root is missing from the JCEF document');

try {
  flushSync(() => createRoot(mount).render(<App />));
  window.DEVQA_REACT_BOOTSTRAPPED = true;
  // JCEF's frontend-ready handshake now belongs exclusively to React.
  void adapter.ready().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    window.receiveDevQAProjectBrainError?.('React initialized but IntelliJ handshake failed: ' + message);
  });
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  mount.textContent = 'DevQA React could not start: ' + message;
  mount.setAttribute('role', 'alert');
  throw error;
}
