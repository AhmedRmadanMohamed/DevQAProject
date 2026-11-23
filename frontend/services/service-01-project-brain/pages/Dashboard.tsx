import { useState } from 'react';
import type { ProjectBrainSnapshotV2 } from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import { MetricTile } from '../../../../core/frontend/ui-components/src/MetricTile';
import { count, duration, health, pretty, type ClassicTab } from '../model';

type Props = {
  readonly model: ProjectBrainSnapshotV2 | null;
  readonly adapter: ProjectBrainAdapter | null;
  readonly running: boolean;
  readonly outcome: string | null;
  readonly open: (tab: ClassicTab) => void;
};
type Graph = { readonly modelVersion: number; readonly nodes: readonly { id: string; name: string; type: string }[];
  readonly edges: readonly { source: string; target: string; type: string }[]; readonly totalNodes: number; readonly totalEdges: number };

function ModuleRelationshipMap({ graph }: { readonly graph: Graph }) {
  const nodes = graph.nodes.slice(0, 16);
  const visible = new Set(nodes.map((node) => node.id));
  const columns = Math.max(1, Math.min(4, Math.ceil(Math.sqrt(nodes.length * 1.7))));
  const rows = Math.max(1, Math.ceil(nodes.length / columns));
  const cardWidth = 176;
  const cardHeight = 48;
  const xGap = 46;
  const yGap = 42;
  const width = columns * (cardWidth + xGap) + 24;
  const height = rows * (cardHeight + yGap) + 24;
  const positions = new Map(nodes.map((node, index) => [node.id, {
    x: 12 + (index % columns) * (cardWidth + xGap),
    y: 12 + Math.floor(index / columns) * (cardHeight + yGap)
  }]));

  return <div className="pdb-module-diagram" aria-label="Verified module relationship diagram">
    <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="img">
      <defs><marker id="pdb-module-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
        <path d="M0,0 L7,3.5 L0,7 Z" fill="#7797bc" />
      </marker></defs>
      {graph.edges.filter((edge) => visible.has(edge.source) && visible.has(edge.target)).slice(0, 80).map((edge, index) => {
        const source = positions.get(edge.source);
        const target = positions.get(edge.target);
        if (!source || !target) return null;
        const x1 = source.x + cardWidth / 2;
        const y1 = source.y + cardHeight / 2;
        const x2 = target.x + cardWidth / 2;
        const y2 = target.y + cardHeight / 2;
        const bend = Math.max(28, Math.abs(x2 - x1) * .38);
        return <path key={`${edge.source}-${edge.target}-${index}`}
          d={`M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`}
          className="pdb-module-edge" markerEnd="url(#pdb-module-arrow)">
          <title>{edge.type}</title>
        </path>;
      })}
      {nodes.map((node) => {
        const position = positions.get(node.id)!;
        return <g key={node.id} transform={`translate(${position.x} ${position.y})`}>
          <rect width={cardWidth} height={cardHeight} rx="7" className="pdb-module-node" />
          <text x="11" y="21" className="pdb-module-node-name">
            {node.name.length > 24 ? node.name.slice(0, 22) + '…' : node.name}
          </text>
          <text x="11" y="37" className="pdb-module-node-meta">PROJECT MODULE</text>
        </g>;
      })}
    </svg>
  </div>;
}

export function Dashboard({ model, adapter, running, outcome, open }: Props) {
  const [graph, setGraph] = useState<Graph | null>(null);
  const [graphError, setGraphError] = useState('');
  const [graphLoading, setGraphLoading] = useState(false);
  const m = model?.metrics;
  const status = health(model, running, outcome);
  const pct = model ? Math.max(0, Math.min(100, Number(m?.classificationCoverage ?? 0))) : 0;
  const frameworks = model?.frameworks ?? [];
  const roles = Object.entries(model?.roleCounts ?? {}).filter(([, number]) => number > 0)
    .sort(([a, countA], [b, countB]) => countB - countA || a.localeCompare(b));
  const gaps = (m?.skippedFiles ?? 0) + (m?.unsupportedFiles ?? 0) + (m?.failedFiles ?? 0);
  const profile = model?.architectureProfile;

  async function loadLinks() {
    if (!adapter || !model || graphLoading) return;
    if (graph?.modelVersion === model.modelVersion) {
      setGraph(null);
      setGraphError('');
      return;
    }
    const version = model.modelVersion;
    setGraphLoading(true);
    setGraphError('');
    try {
      const response = await adapter.query<Graph>({ kind: 'diagram', view: 'modules',
        offset: 0, limit: 40, search: '', filters: {} });
      if (adapter.latest()?.modelVersion !== version) return;
      if (response.modelVersion !== version || !Array.isArray(response.nodes) ||
          !Array.isArray(response.edges) || response.nodes.length > 80 || response.edges.length > 240 ||
          !response.nodes.every((node) => node.type === 'MODULE')) {
        throw new Error('The IntelliJ backend returned an invalid module graph.');
      }
      setGraph(response);
    } catch (error) {
      setGraphError(error instanceof Error ? error.message : 'Could not load verified graph.');
    } finally { setGraphLoading(false); }
  }

  return <div className="pdb-dashboard" data-react-view="dashboard">
    <header className="pdb-hero">
      <div><span className="pdb-eyebrow">● &nbsp; DEVQA / Workspace Overview / PROJECT INTELLIGENCE</span>
        <h1>Understand your codebase.</h1>
        <p>Evidence-backed architecture, relationships, framework discovery and analysis health.</p>
        <span className="pdb-project-label">▦ {model?.projectName ?? 'Current IntelliJ Project'}</span>
        <span className="pdb-project-meta">{model?.primaryFramework.name ?? 'Waiting for analysis'} · {model?.primaryLanguage.name ?? 'Unknown'}</span>
      </div>
      <div className="pdb-hero-right">
        <span className={'tag ' + status.tone}>{status.label}</span>
        <small>Published model v{model ? model.modelVersion : '—'}</small>
        <button type="button" onClick={() => open('overview')}>Open Project Brain ↗</button>
      </div>
    </header>
    <section className="pdb-stats" aria-label="Actual project metrics">
      <MetricTile title="Code modules" value={model ? count(model.modules.length) : '—'} subtitle="Detected project boundaries" icon="▦" tint="blue" onOpen={() => open('diagram')} />
      <MetricTile title="Classes" value={count(m?.classes)} subtitle="Discovered source types" icon="◇" tint="violet" onOpen={() => open('components')} />
      <MetricTile title="Files" value={count(m?.projectFiles)} subtitle="Indexed project files" icon="▤" tint="amber" onOpen={() => open('files')} />
      <MetricTile title="Dependencies" value={count(m?.relationships)} subtitle="Typed relationships" icon="⌁" tint="cyan" onOpen={() => open('diagram')} />
      <MetricTile title="Classified" value={count(m?.components)} subtitle={count(m?.unclassified) + ' unresolved roles'} icon="⬡" tint="green" onOpen={() => open('components')} />
      <MetricTile title="Frameworks" value={model ? count(frameworks.length) : '—'} subtitle="Evidence-based detection" icon="◆" tint="pink" onOpen={() => open('frameworks')} />
    </section>
    <div className="pdb-main-grid">
      <section className="pdb-panel" aria-label="System architecture module map">
        <div className="pdb-heading"><div><span>SYSTEM ARCHITECTURE</span><h2>Module landscape</h2>
          <p>Detected modules with dependency links retrieved only on demand.</p></div>
          <button type="button" onClick={() => open('diagram')}>Explore full diagram ↗</button>
        </div>
        <div className="pdb-module-grid">
          {(model?.modules ?? []).slice(0, 16).map((module) =>
            <button key={module.name} type="button" className="pdb-module" onClick={() => open('diagram')}>
              <strong>{module.name}</strong><small>Project module</small>
            </button>)}
          {!model?.modules.length && <p className="pdb-muted">Run Analyze to discover modules.</p>}
        </div>
        {model && <>
          <button className="button" type="button" disabled={graphLoading} onClick={() => { void loadLinks(); }}>
            {graphLoading ? 'Loading verified links…' : graph ? 'Hide module diagram' : 'Show module diagram'}
          </button>
          {graph && graph.modelVersion === model.modelVersion && <>
            <ModuleRelationshipMap graph={graph} />
            <p className="pdb-muted">{count(graph.totalNodes)} graph nodes · {count(graph.totalEdges)} verified links</p>
          </>}
          {graphError && <p role="alert">{graphError}</p>}
        </>}
      </section>
      <section className="pdb-panel pdb-health" aria-label="Project health">
        <div className="pdb-heading"><div><span>PROJECT HEALTH</span><h2>Analysis integrity</h2></div>
          <span className={'tag ' + status.tone}>{status.shortLabel}</span></div>
        <div className="pdb-health-top">
          <div className="pdb-ring" style={{ background: 'conic-gradient(#32cfa8 0% ' + pct + '%, #354057 ' + pct + '% 100%)' }}
            role="img" aria-label={'Evidence-backed classification coverage ' + pct + '%' }>
            <div><strong>{model ? pct + '%' : '—'}</strong><span>Classified</span></div>
          </div>
          <div><strong>{status.shortLabel}</strong><p>{status.description}</p>
            <small>{count(m?.components)} / {count(m?.classes)} classes</small></div>
        </div>
        <div className="pdb-health-grid">
          {(['projectFiles', 'skippedFiles', 'unsupportedFiles', 'failedFiles'] as const).map((key, i) =>
            <div key={key}><span>{['Indexed', 'Skipped', 'Unsupported', 'Failed'][i]}</span><strong>{count(m?.[key])}</strong></div>)}
        </div>
        <p className="pdb-health-note">{!model ? 'Run Analyze to calculate coverage.' : gaps ?
          count(gaps) + ' files skipped, unsupported or failed. Review scan settings.' :
          'No file processing gaps were reported.'}</p>
      </section>
    </div>
    <div className="pdb-second-grid">
      <section className="pdb-panel" aria-label="Component distribution">
        <div className="pdb-heading"><div><span>CODE STRUCTURE</span><h2>Components by role</h2>
          <p>Real classified counts and unresolved roles.</p></div>
          <button type="button" onClick={() => open('components')}>Explore classes ↗</button>
        </div>
        <div className="pdb-role-legend">
          {roles.slice(0, 12).map(([role, amount]) =>
            <div key={role}><span>{pretty(role)}</span><strong>{count(amount)}</strong></div>)}
          {!roles.length && <p className="pdb-muted">No role classifications yet.</p>}
        </div>
      </section>
      <section className="pdb-panel" aria-label="Detected frameworks">
        <div className="pdb-heading"><div><span>TECHNOLOGY INVENTORY</span><h2>Detected frameworks</h2>
          <p>Grounded in build declarations and source evidence.</p></div>
          <button type="button" onClick={() => open('frameworks')}>View inventory ↗</button>
        </div>
        {frameworks.slice(0, 8).map((f, i) => <div className="pdb-framework" key={f.name + '-' + i}>
          <span className="pdb-framework-icon">{i === 0 ? '◆' : '◇'}</span><div><strong>{f.name}</strong>
          <small>Version: {f.version || 'Unknown'}</small></div>
          <em>{pretty(f.confidence)}</em>
        </div>)}
        {!frameworks.length && <p className="pdb-muted">No framework evidence available yet.</p>}
      </section>
    </div>
    <section className="pdb-panel pdb-evidence" aria-label="Verified architecture evidence">
      <div className="pdb-heading"><div><span>VERIFIED ARCHITECTURE</span><h2>Architecture evidence</h2>
        <p>Actual layers, module boundaries, and cross-module relationships.</p></div>
        <span className="tag accent">{pretty(profile?.confidence || 'NONE')} confidence</span>
      </div>
      <div className="pdb-evidence-stats">
        <div><span>Detected style</span><strong>{pretty(profile?.style || model?.architecture)}</strong></div>
        <div><span>Cross-module relationships</span><strong>{count(profile?.crossModuleRelationships)}</strong></div>
        <div><span>Analyzed modules</span><strong>{count(profile?.moduleCount)}</strong></div>
        <div><span>Analysis duration</span><strong>{duration(m?.durationMillis)}</strong></div>
      </div>
      <h3>Verified signals</h3>
      <div className="pdb-signals">{profile?.evidence?.length ? profile.evidence.map((signal, i) =>
        <span key={i}>{signal}</span>) : <span>No verified architecture signals yet.</span>}</div>
      <h3>Module architecture (analyzed types)</h3>
      <div className="pdb-table-scroll"><table><thead><tr><th>Module</th><th>Classes</th><th>Layers</th><th>Inbound</th><th>Outbound</th></tr></thead>
        <tbody>{(profile?.modules ?? []).map((mod) => <tr key={mod.name}>
          <td>{mod.name}</td><td>{count(mod.classes)}</td>
          <td>{Object.entries(mod.layers ?? {}).filter(([, n]) => n > 0).map(([layer, n]) => pretty(layer) + ' ' + count(n)).join(' · ') || 'Unclassified'}</td>
          <td>{count(mod.incoming)}</td><td>{count(mod.outgoing)}</td>
        </tr>)}</tbody>
      </table></div>
      {!profile?.modules?.length && <p className="pdb-muted">No architecture module profile available.</p>}
    </section>
    <div className="pdb-second-grid">
      <section className="pdb-panel pdb-audit" aria-label="Analysis health evidence">
        <div className="pdb-heading"><div><span>ANALYSIS INTEGRITY</span><h2>Scan accounting</h2>
          <p>Counts from the published Kotlin model, including unresolved files.</p></div>
          <span className={'tag ' + status.tone}>{status.shortLabel}</span>
        </div>
        <div className="pdb-evidence-stats">
          <div><span>Discovered Files</span><strong>{count(m?.discoveredFiles)}</strong></div>
          <div><span>Analyzed Files</span><strong>{count(m?.analyzedFiles)}</strong></div>
          <div><span>Unsupported Files</span><strong>{count(m?.unsupportedFiles)}</strong></div>
          <div><span>Skipped / Failed</span><strong>{count((m?.skippedFiles ?? 0) + (m?.failedFiles ?? 0))}</strong></div>
        </div>
        <p className="pdb-muted">{status.description}</p>
        <button type="button" className="pdb-outline" onClick={() => open('overview')}>
          Open full analysis overview ↗
        </button>
      </section>
      <section className="pdb-panel" aria-label="Project profile and diagnostics">
        <div className="pdb-heading"><div><span>CURRENT SNAPSHOT</span><h2>Project profile</h2></div>
          <span className="tag accent">Model v{model?.modelVersion ?? '—'}</span>
        </div>
        {([
          ['Project', model?.projectName ?? 'Current project'],
          ['Framework', model?.primaryFramework.name ?? 'Unknown'],
          ['Framework confidence', pretty(model?.primaryFramework.confidence)],
          ['Language', model?.primaryLanguage.name ?? 'Unknown'],
          ['Coverage', model ? pct + '%' : '—'],
          ['Relationships', count(m?.relationships)],
          ['Diagnostics', count(m?.diagnostics)],
          ['Errors / warnings', count(m?.diagnosticErrors) + ' / ' + count(m?.diagnosticWarnings)]
        ] as const).map(([label, value]) =>
          <div className="detail-row" key={label}><span>{label}</span><strong>{value}</strong></div>)}
        <h3>Recorded diagnostics</h3>
        {model?.diagnostics?.length ? model.diagnostics.slice(0, 8).map((message, i) =>
          <p key={i} className="pdb-diagnostic">{message.slice(0, 240)}</p>) :
          <p className="pdb-muted">No diagnostic messages recorded.</p>}
        <button type="button" className="pdb-outline" onClick={() => open('overview')}>
          Open full analysis overview ↗
        </button>
      </section>
    </div>
  </div>;
}
