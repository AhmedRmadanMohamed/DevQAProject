import type { ProjectBrainSnapshotV2 } from '../../../../contracts/service-01-project-brain/project-brain.v2';
import { MetricTile } from '../../../../core/frontend/ui-components/src/MetricTile';
import { count, duration, health, pretty, type ClassicTab } from '../model';

type Props = { readonly model: ProjectBrainSnapshotV2 | null;
  readonly running: boolean; readonly outcome: string | null; readonly open: (tab: ClassicTab) => void };

export function Overview({ model, running, outcome, open }: Props) {
  const m = model?.metrics;
  const status = health(model, running, outcome);
  const roleCounts = Object.entries(model?.roleCounts ?? {}).filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);
  const profile = model?.architectureProfile;

  return <div className="pb-overview" data-react-view="overview">
    <section className="pb-overview-intro" aria-label="Project analysis overview">
      <div><span className="pdb-eyebrow">ANALYSIS CONTROL CENTER</span>
        <h2>Project analysis overview</h2>
        <p>One verified model, with the same project metrics used by the workspace dashboard.</p>
      </div>
      <div className="pb-overview-status">
        <span className={'tag ' + status.tone}>{status.label}</span>
        <small>Model v{model?.modelVersion ?? '—'} · {model?.projectName ?? 'Current project'}</small>
      </div>
    </section>
    <section className="pdb-stats pb-overview-stats" aria-label="Project analysis key metrics">
      <MetricTile title="Code modules" value={model ? count(model.modules.length) : '—'} subtitle="Detected module boundaries" icon="▦" tint="blue" onOpen={() => open('diagram')} />
      <MetricTile title="All classes" value={count(m?.classes)} subtitle="Discovered source types" icon="◇" tint="violet" onOpen={() => open('components')} />
      <MetricTile title="Project files" value={count(m?.projectFiles)} subtitle="Successfully indexed files" icon="▤" tint="amber" onOpen={() => open('files')} />
      <MetricTile title="Relationships" value={count(m?.relationships)} subtitle="Typed dependency edges" icon="⌁" tint="cyan" onOpen={() => open('diagram')} />
      <MetricTile title="Classified" value={count(m?.components)} subtitle={count(m?.unclassified) + ' unresolved classes'} icon="⬡" tint="green" onOpen={() => open('components')} />
      <MetricTile title="Classification Coverage" value={model ? String(m?.classificationCoverage ?? 0) + '%' : '—'} subtitle="Evidence-based classification" icon="◉" tint="pink" onOpen={() => open('components')} />
    </section>

    <section className="pb-overview-section" aria-label="Analysis integrity">
      <div className="pdb-heading"><div><span>ANALYSIS HEALTH</span><h2>{status.shortLabel}</h2>
        <p>{status.description}</p></div><span className={'tag ' + status.tone}>{status.label}</span></div>
      <div className="metrics pb-overview-metrics">
        <article className="metric"><div className="metric-label">Discovered Files</div><div className="metric-value">{count(m?.discoveredFiles)}</div></article>
        <article className="metric"><div className="metric-label">Analyzed Files</div><div className="metric-value">{count(m?.analyzedFiles)}</div></article>
        <article className="metric"><div className="metric-label">Unsupported Files</div><div className="metric-value">{count(m?.unsupportedFiles)}</div></article>
        <article className="metric"><div className="metric-label">Skipped Files</div><div className="metric-value">{count(m?.skippedFiles)}</div></article>
        <article className="metric"><div className="metric-label">Failed Files</div><div className="metric-value">{count(m?.failedFiles)}</div></article>
        <article className="metric"><div className="metric-label">Unclassified</div><div className="metric-value">{count(m?.unclassified)}</div></article>
        <article className="metric"><div className="metric-label">Diagnostics</div><div className="metric-value">{count(m?.diagnostics)}</div>
          <div className="metric-note">{count(m?.diagnosticErrors)} errors · {count(m?.diagnosticWarnings)} warnings</div></article>
      </div>
    </section>

    <section className="pb-overview-section" aria-label="Additional analysis metrics">
      <div className="pdb-heading"><div><span>ANALYSIS DETAILS</span><h2>Scan and model statistics</h2>
        <p>Counts from the current published analysis, not estimated scores.</p></div></div>
      <div className="metrics pb-overview-metrics">
        {([
          ['Analysis Status', status.shortLabel, status.description],
          ['Primary Language', model?.primaryLanguage.name || 'Unknown', count(model?.primaryLanguage.fileCount) + ' source files'],
          ['Primary Framework', model?.primaryFramework.name || 'Unknown', pretty(model?.primaryFramework.confidence) + ' confidence'],
          ['Analysis Duration', duration(m?.durationMillis), 'Last published analysis'],
          ['Analyzed Files', count(m?.analyzedFiles), 'Indexed files with supported analysis'],
          ['Skipped Files', count(m?.skippedFiles), 'Excluded by scan preferences'],
          ['Unsupported Files', count(m?.unsupportedFiles), 'Included without analysis support'],
          ['Failed Files', count(m?.failedFiles), 'Recorded scan failures']
        ] as const).map(([label, value, note]) =>
          <article className="metric" key={label}><div className="metric-label">{label}</div>
            <div className="metric-value">{value}</div><div className="metric-note">{note}</div></article>)}
      </div>
    </section>

    <div className="details pb-overview-details">
      <section className="card pb-overview-panel">
        <div className="card-heading"><h2>Project Profile</h2><span className="tag accent">Model v{model?.modelVersion ?? '—'}</span></div>
        {([
          ['Primary Framework', model?.primaryFramework.name ?? 'Unknown'],
          ['Confidence', pretty(model?.primaryFramework.confidence)],
          ['Primary Language', model?.primaryLanguage.name ?? 'Unknown'],
          ['Architecture', pretty(model?.architecture)],
          ['Modules', model ? count(model.modules.length) : '—']
        ] as const).map(([label, value]) =>
          <div className="detail-row" key={label}><span>{label}</span><strong>{value}</strong></div>)}
      </section>
      <section className="card pb-overview-panel">
        <div className="card-heading"><h2>Detected Layer Inventory</h2><span className="tag">{count(m?.classes)} classes including unresolved</span></div>
        {roleCounts.length ? roleCounts.map(([role, amount]) =>
          <div className="layer-row" key={role}><span>{pretty(role)}</span>
            <div className="layer-track"><i style={{width: Math.min(100, amount / Math.max(1, m?.classes ?? 1) * 100) + '%'}} /></div>
            <strong>{count(amount)}</strong></div>) :
          <p>No classified roles yet.</p>}
      </section>
      <section className="card architecture-profile-card pb-overview-panel">
        <div className="card-heading"><h2>Architecture Evidence</h2><span className="tag accent">{pretty(profile?.confidence ?? 'NONE')} confidence</span></div>
        <div className="detail-row"><span>Detected style</span><strong>{pretty(profile?.style ?? model?.architecture)}</strong></div>
        <div className="detail-row"><span>Cross-module relationships</span><strong>{count(profile?.crossModuleRelationships)}</strong></div>
        <h3>Verified signals</h3><p className="architecture-evidence">{profile?.evidence?.join(' · ') || 'No verified architecture signals yet.'}</p>
        <h3>Module architecture (analyzed types)</h3>
        <div className="architecture-modules-table"><table><thead><tr>
          <th>Module</th><th>Classes</th><th>Layers</th><th>Inbound</th><th>Outbound</th></tr></thead><tbody>
          {(profile?.modules ?? []).map((mod) => <tr key={mod.name}>
            <td>{mod.name}</td><td>{count(mod.classes)}</td>
            <td>{Object.entries(mod.layers ?? {}).filter(([, amount]) => amount > 0).map(([name, amount]) => pretty(name) + ' ' + count(amount)).join(' · ') || 'Unclassified'}</td>
            <td>{count(mod.incoming)}</td><td>{count(mod.outgoing)}</td>
          </tr>)}
        </tbody></table></div>
      </section>
    </div>
  </div>;
}
