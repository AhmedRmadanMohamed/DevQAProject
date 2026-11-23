import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { getProjectBrainAdapter } from '../../../core/frontend/jcef-bridge/src/bridge';
import { Dashboard } from './pages/Dashboard';
import { Overview } from './pages/Overview';
import { Components } from './pages/Components';
import { Files } from './pages/Files';
import { Diagram } from './pages/Diagram';
import { Frameworks } from './pages/Frameworks';
import { Settings } from './pages/Settings';
import { health, type Route } from './model';

const TABS: readonly { id: Exclude<Route, 'dashboard'>; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: '◈' },
  { id: 'components', label: 'Components', icon: '▦' },
  { id: 'files', label: 'Project Files', icon: '▤' },
  { id: 'diagram', label: 'Live Diagram', icon: '⌁' },
  { id: 'frameworks', label: 'Framework Inventory', icon: '◆' },
  { id: 'settings', label: 'Scan Settings', icon: '⚙' }
];
function subscribeModel(onChange: () => void): () => void {
  return getProjectBrainAdapter()?.subscribe(onChange) ?? (() => {});
}
function currentModel() { return getProjectBrainAdapter()?.latest() ?? null; }

/**
 * React-only production host for all Service 01 screens. No legacy DOM, and no
 * JavaScript navigation fallback. The JCEF adapter is initialized before React.
 */
export function ProjectBrainHost() {
  const model = useSyncExternalStore(subscribeModel, currentModel, () => null);
  const adapter = getProjectBrainAdapter();
  const [route, setRoute] = useState<Route>('dashboard');
  const [running, setRunning] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [error, setError] = useState('');
  const status = health(model, running, outcome);

  useEffect(() => {
    const progress = (event: Event) => {
      const current = (event as CustomEvent<{ status?: string }>).detail?.status;
      if (current === 'RUNNING') { setRunning(true); setOutcome(null); setError(''); }
      if (current === 'CANCELED') { setRunning(false); setOutcome('CANCELED'); }
    };
    const failed = (event: Event) => {
      const message = String((event as CustomEvent<unknown>).detail ?? 'Analysis failed');
      setRunning(false); setOutcome('FAILED'); setError(message);
    };
    const sourceError = (event: Event) =>
      setError(String((event as CustomEvent<unknown>).detail ?? 'Source navigation failed'));
    window.addEventListener('devqa:project-brain-progress', progress);
    window.addEventListener('devqa:project-brain-error', failed);
    window.addEventListener('devqa:project-brain-source-error', sourceError);
    return () => {
      window.removeEventListener('devqa:project-brain-progress', progress);
      window.removeEventListener('devqa:project-brain-error', failed);
      window.removeEventListener('devqa:project-brain-source-error', sourceError);
    };
  }, []);
  useEffect(() => {
    if (model) { setRunning(false); setOutcome(null); setError(''); }
  }, [model?.modelVersion]);

  const open = useCallback((next: Route) => {
    setRoute(next); setError('');
  }, []);
  async function analyze() {
    if (!adapter || running) return;
    setRunning(true); setOutcome(null); setError('');
    try { await adapter.analyze(); }
    catch (cause) {
      setRunning(false); setOutcome('FAILED');
      setError(cause instanceof Error ? cause.message : 'Analysis could not start');
    }
  }
  async function cancel() {
    try { await adapter?.cancel(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Cancellation failed'); }
  }

  return <div className="app-shell" data-devqa-host="react" data-react-migration="complete">
    <aside className="sidebar">
      <div className="brand"><div className="brand-mark" aria-hidden="true">D</div>
        <div><h1 className="brand-title">DevQA</h1><p className="brand-copy">Engineering Intelligence</p></div></div>
      <section className="project-card" aria-label="Current project">
        <p className="eyebrow">Current project</p>
        <h2 className="project-name">{model?.projectName ?? 'Current IntelliJ Project'}</h2>
        <div className="project-meta"><span className="dot" />{running ? 'ANALYZING' : status.label}</div>
        <p className="side-framework"><span>Framework</span><strong>{model?.primaryFramework.name ?? 'Unknown'}</strong></p>
        <p className="side-framework"><span>Language</span><strong>{model?.primaryLanguage.name ?? 'Unknown'}</strong></p>
      </section>
      <nav className="side-nav" aria-label="DevQA navigation">
        <p className="nav-section">Workspace</p>
        <button type="button" className={'nav-link' + (route === 'dashboard' ? ' active' : '')}
          onClick={() => open('dashboard')}><span>⌂</span>Dashboard</button>
        <p className="nav-section">Project Brain</p>
        {TABS.map((tab) => <button type="button" key={tab.id} onClick={() => open(tab.id)}
          className={'nav-link' + (route === tab.id ? ' active' : '')}>
          <span>{tab.icon}</span>{tab.label}</button>)}
      </nav>
      <div className="sidebar-footer"><span className="connection-dot" />
        <span>{adapter?.mode === 'intellij-bridge' ? 'Backend connected' : 'Standalone React'}</span></div>
    </aside>
    <main className="main">
      <header className="topbar">
        <div className="breadcrumb"><span>DevQA</span><b>/</b>
          <strong>{route === 'dashboard' ? 'Workspace Overview' :
            'Project Brain / ' + (TABS.find((item) => item.id === route)?.label ?? 'Overview')}</strong></div>
        <div className="topbar-actions">
          <span className="model-state"><span className="connection-dot" />Live model v{model?.modelVersion ?? '—'}</span>
          <button className="button primary" type="button" disabled={!adapter || running}
            onClick={() => { void analyze(); }}>{running ? 'Analyzing…' : '▶ Analyze'}</button>
          {running && <button type="button" className="button" onClick={() => { void cancel(); }}>Cancel</button>}
        </div>
      </header>
      <div className="content">
        {error && <div role="alert" className="error-banner">{error}</div>}
        {route === 'dashboard' && <Dashboard model={model} adapter={adapter}
          running={running} outcome={outcome} open={open} />}
        {route !== 'dashboard' && <div className="pb-workspace-header">
          <div><span className="pdb-eyebrow">DEVQA / PROJECT INTELLIGENCE</span><h1>Project Brain</h1>
            <p>Evidence-backed architecture and source intelligence.</p></div>
          <div className="pb-workspace-status"><span className={'tag ' + status.tone}>{status.label}</span>
            <small>Published model v{model?.modelVersion ?? '—'}</small></div>
        </div>}
        {route !== 'dashboard' && <nav className="tabs pb-tabs" aria-label="Project Brain views">
          {TABS.map((tab) => <button key={tab.id} type="button" role="tab"
            aria-selected={route === tab.id} className={'tab ' + (route === tab.id ? 'active' : '')}
            onClick={() => open(tab.id)}>{tab.label}{tab.id === 'components' && model ?
              ' (' + model.metrics.classes + ')' : ''}</button>)}
        </nav>}
        {route !== 'dashboard' && <section className="tab-panel">
          {route === 'overview' && <Overview model={model} running={running} outcome={outcome} open={open} />}
          {route === 'components' && <Components model={model} adapter={adapter} />}
          {route === 'files' && <Files model={model} adapter={adapter} />}
          {route === 'diagram' && <Diagram model={model} adapter={adapter} />}
          {route === 'frameworks' && <Frameworks model={model} adapter={adapter} />}
          {route === 'settings' && <Settings model={model} adapter={adapter} />}
        </section>}
      </div>
      <footer className="statusbar">
        <span>DevQA · Project Brain · React</span>
        <span>{model?.projectName ?? 'Current project'} · Model v{model?.modelVersion ?? '—'} ·
          {' '}{model?.primaryFramework.name ?? 'Unknown'}</span>
      </footer>
    </main>
  </div>;
}
