import { useEffect, useState } from 'react';
import type { ProjectBrainSnapshotV2, ProjectFrameworkSummary,
  ProjectFrameworkDetail, DependencyEvidence } from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import { count, pretty } from '../model';
import { FacetControls } from '../components/FacetControls';
import { useDetail, usePage, type AppliedFilters } from '../hooks/query-hooks';

const LABELS = { module: 'Module', confidence: 'Confidence', category: 'Category' } as const;
type Props = { readonly model: ProjectBrainSnapshotV2 | null; readonly adapter: ProjectBrainAdapter | null };

function Dependencies({ entries, empty }: { entries?: readonly DependencyEvidence[]; empty: string }) {
  if (!entries?.length) return <p>{empty}</p>;
  return <ul className="dependency-list">{entries.map((item, index) =>
    <li key={item.coordinate + ':' + index}><strong>{item.coordinate}</strong>
      <small>{pretty(item.origin)} · {item.version || 'Unknown version'} · {item.path}</small>
    </li>)}</ul>;
}

export function Frameworks({ model, adapter }: Props) {
  const version = model?.modelVersion;
  const [draft, setDraft] = useState<Readonly<Record<string, readonly string[]>>>({});
  const [search, setSearch] = useState('');
  const [applied, setApplied] = useState<AppliedFilters>({ modelVersion: -1, search: '', offset: 0, filters: {} });
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [openError, setOpenError] = useState('');
  const page = usePage<ProjectFrameworkSummary>(adapter, version, 'frameworks', applied);
  const details = useDetail<ProjectFrameworkDetail>(adapter, version, 'framework-detail',
    selectedName ? { name: selectedName } : null);
  useEffect(() => {
    setDraft({}); setSearch(''); setApplied({ modelVersion: -1, search: '', offset: 0, filters: {} });
    setSelectedName(null);
  }, [version]);

  async function open(path: string, offset = 0) {
    try { await adapter?.openSource(path, offset); }
    catch (cause) { setOpenError(cause instanceof Error ? cause.message : 'Unable to navigate source'); }
  }

  return <div data-react-view="frameworks" className="pb-frameworks">
    <div className="section-heading"><div><h2>Framework Inventory</h2>
      <p>Actual project technologies, version evidence, declarations, and related classes.</p></div>
      <span className="tag accent">MODEL v{version ?? '—'}</span>
    </div>
    <div className="filter-panel">
      <div className="filter-search">
        <input type="search" aria-label="Search frameworks" maxLength={200}
          placeholder="Spring Boot, Gradle, React…" value={search}
          onChange={(event) => setSearch(event.target.value)} />
        <button type="button" className="button primary" onClick={() => {
          setSelectedName(null); setApplied({ modelVersion: version ?? -1, search, offset: 0, filters: { ...draft } });
        }}>Apply Filters</button>
        <button type="button" className="button" onClick={() => {
          setDraft({}); setSearch(''); setSelectedName(null);
          setApplied({ modelVersion: version ?? -1, search: '', offset: 0, filters: {} });
        }}>Clear Filters</button>
      </div>
      <details className="framework-filter-drawer">
        <summary><span>Advanced framework filters</span>
          <small>{Object.values(draft).reduce((sum, values) => sum + values.length, 0)} active</small></summary>
        <FacetControls options={model?.facets?.frameworks ?? {}} selected={draft}
          labels={LABELS} onChange={(field, values) =>
            setDraft((current) => ({ ...current, [field]: values }))} />
      </details>
      <p className="filter-hint">Frameworks and versions are reported from verified source/build evidence.</p>
    </div>
    {(page.error || details.error || openError) &&
      <div role="alert" className="error-banner">{page.error || details.error || openError}</div>}
    <div className="result-summary" role="status">
      {page.loading ? 'Loading frameworks…' : count(page.total) + ' matching frameworks'} · Page {Math.floor(applied.offset / 40) + 1}
    </div>
    <div className="framework-grid">{page.items.map((framework) =>
      <button key={framework.name} type="button" className="card framework-tile framework-card selectable-tile"
        onClick={() => setSelectedName(framework.name)}>
        <div className="framework-title"><span className="inventory-icon">◆</span><div><h3>{framework.name}</h3>
          <small>{pretty(framework.category || 'Technology')}</small></div></div>
        <div className="framework-card-tags"><span className="tag accent">{pretty(framework.confidence)}</span>
          <span className="tag">{framework.version && framework.version !== 'Unknown' ?
            'Declared ' + framework.version : 'Version unverified'}</span></div>
        <div className="framework-card-section"><span>MODULES</span>
          <p>{framework.modules?.join(', ') || 'Project-wide'}</p></div>
        <div className="framework-card-section"><span>EVIDENCE</span>
          <p>{framework.evidence?.slice(0, 2).join(' · ') || 'Evidence unavailable'}</p></div>
        <span className="framework-card-open">Inspect evidence →</span>
      </button>)}
      {!page.loading && !page.items.length && <p>No matching frameworks.</p>}
    </div>
    {page.total > 40 && <div className="pagination">
      <button type="button" className="button" disabled={page.loading || applied.offset === 0}
        onClick={() => setApplied((a) => ({ ...a, offset: Math.max(0, a.offset - 40) }))}>Previous</button>
      <span>{applied.offset + 1}–{Math.min(applied.offset + 40, page.total)} of {page.total}</span>
      <button type="button" className="button" disabled={page.loading || applied.offset + 40 >= page.total}
        onClick={() => setApplied((a) => ({ ...a, offset: a.offset + 40 }))}>Next</button>
    </div>}
    {selectedName && <aside className="inspect-panel">
      <div className="card-heading"><h2>{selectedName}</h2>
        <button type="button" className="button compact" onClick={() => setSelectedName(null)}>Close</button>
      </div>
      {!details.detail ? <p>Loading verified framework details…</p> : <>
        {([
          ['Exact Declared Version', details.detail.version || 'Unknown'],
          ['Category', details.detail.category || 'Unknown'],
          ['Version Status', pretty(details.detail.versionStatus)],
          ['Confidence', pretty(details.detail.confidence)],
          ['Modules', details.detail.modules?.join(', ') || 'Unknown']
        ] as const).map(([label, value]) => <div className="detail-row" key={label}>
          <span>{label}</span><strong>{value}</strong></div>)}
        <h3>Manifest version declarations</h3>
        <p>{details.detail.declaredVersions?.map((entry) =>
          entry.version + ' (' + entry.path + (entry.dependency ? ' · ' + entry.dependency : '') + ')').join(' · ') ||
          'No exact literal version found; the build may resolve one dynamically.'}</p>
        <h3>Detection Evidence</h3><p>{details.detail.evidence?.join(' · ') || 'Unavailable'}</p>
        <h3>Direct Dependencies ({details.detail.directDependencies?.length ?? 0})</h3>
        <Dependencies entries={details.detail.directDependencies} empty="No directly declared dependencies identified." />
        <h3>Transitive Dependencies ({details.detail.transitiveDependencies?.length ?? 0})</h3>
        <Dependencies entries={details.detail.transitiveDependencies} empty="Transitive dependencies cannot be fully resolved from manifests alone." />
        <h3>Other Manifest Declarations ({details.detail.otherDeclarations?.length ?? 0})</h3>
        <Dependencies entries={details.detail.otherDeclarations} empty="No managed, plugin, parent, or unverified declarations." />
        <p className="filter-hint">Manifest evidence alone does not prove a dependency was loaded at runtime.
          {details.detail.dependencyEvidenceTruncated && ' Evidence truncated at the scan safety limit.'}</p>
        <h3>Related Classes</h3>
        {details.detail.classSources?.length ? <ul className="dependency-list">
          {details.detail.classSources.map((source) => <li key={source.path + ':' + source.name}>
            <button type="button" className="dependency-link" onClick={() => { void open(source.path, source.offset ?? 0); }}>
              {source.name}
            </button></li>)}
        </ul> : <p>{details.detail.relatedClasses?.join(' · ') || 'None tied to source evidence.'}</p>}
        <h3>Annotations</h3><p>{details.detail.annotations?.join(' · ') || 'Not identified'}</p>
        <h3>Configuration Files</h3><p>{details.detail.configurationFiles?.join(' · ') || 'Not identified'}</p>
      </>}
    </aside>}
  </div>;
}
