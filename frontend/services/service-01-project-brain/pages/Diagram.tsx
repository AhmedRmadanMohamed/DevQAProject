import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import type {
  ProjectBrainSnapshotV2, ProjectGraphNode, ProjectGraphReply,
  ProjectBrainComponent
} from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import { useDetail } from '../hooks/query-hooks';
import { count, pretty } from '../model';

type Mode = 'modules' | 'layers' | 'expanded' | 'dependency' | 'impact';
type Level = 'system' | 'service' | 'module' | 'class';
type Filters = Readonly<Record<string, readonly string[]>>;
type ViewState = { mode: Mode; level: Level; module: string; role: string;
  focus: string; focusId: string; filters: Filters; search: string; groupType: string; groupModule: string };
type Props = { readonly model: ProjectBrainSnapshotV2 | null; readonly adapter: ProjectBrainAdapter | null };
const EMPTY_VIEW: ViewState = { mode: 'modules', level: 'system', module: '', role: '',
  focus: '', focusId: '', filters: {}, search: '', groupType: '', groupModule: '' };
const MODES: readonly { id: Mode; label: string }[] = [
  { id: 'modules', label: 'Module View' }, { id: 'layers', label: 'Layer View' },
  { id: 'expanded', label: 'Expanded Group' }, { id: 'dependency', label: 'Dependency View' },
  { id: 'impact', label: 'Impact View' }
];
const GRAPH_FACETS = ['type', 'module', 'confidence', 'relationship'] as const;
type NodePos = { x: number; y: number };

type DragState =
  | { kind: 'canvas'; pointerId: number; clientX: number; clientY: number; scrollLeft: number; scrollTop: number }
  | { kind: 'node'; pointerId: number; nodeId: string; clientX: number; clientY: number; origin: NodePos };

function GraphCanvas({ graph, zoom, setZoom, selectedId, select, layoutVersion }: {
  graph: ProjectGraphReply; zoom: number; selectedId: string | null;
  setZoom: (next: number | ((current: number) => number)) => void;
  select: (node: ProjectGraphNode) => void;
  layoutVersion: number;
}) {
  const shell = useRef<HTMLDivElement | null>(null);
  const drag = useRef<DragState | null>(null);
  const moved = useRef(false);
  // Deterministic, bounded initial layout. Nodes remain movable after rendering.
  const columns = Math.max(1, Math.min(5, Math.ceil(Math.sqrt(graph.nodes.length / 1.6))));
  const rows = Math.max(1, Math.ceil(graph.nodes.length / columns));
  const cardWidth = 190, cardHeight = 62, xGap = 54, yGap = 48;
  const width = columns * (cardWidth + xGap) + 40;
  const height = Math.max(410, rows * (cardHeight + yGap) + 80);
  const initialPositions = useMemo(() => new Map(graph.nodes.map((node, index) => [node.id, {
    x: 28 + (index % columns) * (cardWidth + xGap),
    y: 48 + Math.floor(index / columns) * (cardHeight + yGap)
  }])), [graph.nodes, columns]);
  const [positions, setPositions] = useState<Map<string, NodePos>>(() => new Map(initialPositions));

  useEffect(() => {
    setPositions(new Map(initialPositions));
    requestAnimationFrame(() => shell.current?.scrollTo({ left: 0, top: 0, behavior: 'smooth' }));
  }, [initialPositions, layoutVersion]);

  function finishDrag(event: ReactPointerEvent<HTMLDivElement>) {
    if (drag.current?.pointerId !== event.pointerId) return;
    try { event.currentTarget.releasePointerCapture(event.pointerId); } catch { /* capture can end on disposal */ }
    drag.current = null;
  }
  function move(event: ReactPointerEvent<HTMLDivElement>) {
    const active = drag.current;
    if (!active || active.pointerId !== event.pointerId) return;
    const dx = event.clientX - active.clientX;
    const dy = event.clientY - active.clientY;
    if (Math.abs(dx) + Math.abs(dy) > 3) moved.current = true;
    if (active.kind === 'canvas') {
      event.currentTarget.scrollLeft = active.scrollLeft - dx;
      event.currentTarget.scrollTop = active.scrollTop - dy;
      return;
    }
    setPositions((current) => {
      const next = new Map(current);
      next.set(active.nodeId, {
        x: Math.max(8, active.origin.x + dx / zoom),
        y: Math.max(8, active.origin.y + dy / zoom)
      });
      return next;
    });
  }

  const related = new Set(graph.edges.filter((edge) => edge.source === selectedId || edge.target === selectedId)
    .flatMap((edge) => [edge.source, edge.target]));

  return <div ref={shell} className="diagram-shell architecture-canvas professional-diagram-canvas"
    onPointerDown={(event) => {
      if (event.button !== 0) return;
      moved.current = false;
      drag.current = { kind: 'canvas', pointerId: event.pointerId, clientX: event.clientX, clientY: event.clientY,
        scrollLeft: event.currentTarget.scrollLeft, scrollTop: event.currentTarget.scrollTop };
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={move} onPointerUp={finishDrag} onPointerCancel={finishDrag}
    onWheel={(event) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setZoom((current) => Math.max(.45, Math.min(2.2, current + (event.deltaY < 0 ? .1 : -.1))));
    }}>
    <svg role="img" aria-label="Live project architecture diagram" viewBox={'0 0 ' + width + ' ' + height}
      width={width * zoom} height={height * zoom} className="professional-diagram-svg">
      <defs><marker id="devqa-graph-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5"
        orient="auto" markerUnits="strokeWidth">
        <path d="M 0 0 L 7 3.5 L 0 7 z" fill="#8299b9" />
      </marker></defs>
      {graph.edges.map((edge, i) => {
        const a = positions.get(edge.source), b = positions.get(edge.target);
        if (!a || !b) return null;
        const ax = a.x + cardWidth / 2, ay = a.y + cardHeight / 2;
        const bx = b.x + cardWidth / 2, by = b.y + cardHeight / 2;
        if (ax === bx && ay === by) return null;
        const bend = Math.max(34, Math.abs(bx - ax) * .42);
        const active = !!selectedId && (edge.source === selectedId || edge.target === selectedId);
        return <path key={i} d={`M ${ax} ${ay} C ${ax + bend} ${ay}, ${bx - bend} ${by}, ${bx} ${by}`}
          className={'professional-edge ' + (active ? 'active' : '')} markerEnd="url(#devqa-graph-arrow)">
          <title>{edge.type + ' · ' + (edge.occurrences ?? 1) + ' occurrence(s)'}</title>
        </path>;
      })}
      {graph.nodes.map((node) => {
        const p = positions.get(node.id) ?? initialPositions.get(node.id)!;
        const selected = node.id === selectedId;
        const dimmed = !!selectedId && !selected && !related.has(node.id);
        return <g key={node.id} role="button" tabIndex={0} aria-label={'Explore ' + node.name}
          transform={`translate(${p.x} ${p.y})`}
          className={'professional-node ' + (selected ? 'selected ' : '') + (dimmed ? 'dimmed' : '')}
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            event.stopPropagation(); moved.current = false;
            drag.current = { kind: 'node', pointerId: event.pointerId, nodeId: node.id,
              clientX: event.clientX, clientY: event.clientY, origin: p };
            shell.current?.setPointerCapture(event.pointerId);
          }}
          onClick={() => { if (moved.current) { moved.current = false; return; } select(node); }}
          onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') select(node); }}
          style={{ cursor: 'grab' }}>
          <rect width={cardWidth} height={cardHeight} rx={9} className="professional-node-body" />
          <circle cx="15" cy="17" r="4" className="professional-node-dot" />
          <text x="27" y="22" className="professional-node-name">
            {node.name.length > 27 ? node.name.slice(0, 25) + '…' : node.name}
          </text>
          <text x="13" y="45" className="professional-node-meta">
            {pretty(node.type) + (node.count && node.count > 1 ? ' · ' + count(node.count) : '')}
          </text>
        </g>;
      })}
    </svg>
    <div className="diagram-interaction-hint">Drag canvas to pan · Drag nodes to arrange · Ctrl + wheel to zoom</div>
  </div>;
}

export function Diagram({ model, adapter }: Props) {
  const version = model?.modelVersion;
  const [view, setView] = useState<ViewState>(EMPTY_VIEW);
  const [draftSearch, setDraftSearch] = useState('');
  const [draftFocus, setDraftFocus] = useState('');
  const [draftFilters, setDraftFilters] = useState<Filters>({});
  const [relationship, setRelationship] = useState('');
  const [zoom, setZoom] = useState(1);
  const [layoutVersion, setLayoutVersion] = useState(0);
  const [focusCanvas, setFocusCanvas] = useState(false);
  const [graph, setGraph] = useState<ProjectGraphReply | null>(null);
  const [selected, setSelected] = useState<ProjectGraphNode | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  const isClassNode = selected && !['modules', 'layers'].includes(view.mode);
  const details = useDetail<ProjectBrainComponent>(adapter, version, 'component-detail',
    isClassNode && selected ? { id: selected.id } : null);
  const facets = model?.facets?.diagram ?? {};

  useEffect(() => {
    setView(EMPTY_VIEW); setGraph(null); setSelected(null);
    setDraftSearch(''); setDraftFocus(''); setDraftFilters({}); setRelationship(''); setZoom(1);
    setLayoutVersion((value) => value + 1); setFocusCanvas(false);
  }, [version]);

  const filters = useMemo(() => {
    const base: Record<string, readonly string[]> = { ...view.filters };
    if (view.module && !base.module?.length) base.module = [view.module];
    return base;
  }, [view]);

  useEffect(() => {
    setGraph(null); setSelected(null); setError('');
    if (!model || !adapter) return;
    const current = ++sequence.current;
    let active = true;
    setLoading(true);
    const payload: Record<string, unknown> = {
      kind: 'diagram', view: view.mode, search: view.search.slice(0, 200),
      filters, offset: 0, limit: 80,
      focus: view.focus, focusId: view.focusId,
      groupType: view.groupType, groupModule: view.groupModule
    };
    void adapter.query<ProjectGraphReply>(payload as { kind: 'diagram'; [key: string]: unknown })
      .then((result) => {
        if (!active || current !== sequence.current || adapter.latest()?.modelVersion !== model.modelVersion) return;
        setGraph(result);
      }).catch((cause: unknown) => {
        if (active && current === sequence.current)
          setError(cause instanceof Error ? cause.message : 'Architecture query failed');
      }).finally(() => { if (active && current === sequence.current) setLoading(false); });
    return () => { active = false; };
  }, [adapter, model, view, filters]);

  function changeMode(mode: Mode) {
    setView({ ...EMPTY_VIEW, mode, level: mode === 'modules' ? 'system' : mode === 'layers' ? 'module' : 'class' });
    setSelected(null); setDraftFocus(''); setDraftFilters({}); setDraftSearch('');
  }
  function advance(node: ProjectGraphNode) {
    setSelected(node);
    if (view.mode === 'modules') {
      // Native module list has stable IDs and valid module selectors.
      setView({ ...EMPTY_VIEW, mode: 'layers', level: 'service',
        module: node.module || node.name, filters: { module: [node.module || node.name] } });
    } else if (view.mode === 'layers') {
      setView((old) => ({ ...old, mode: 'expanded', level: 'module',
        role: node.type, groupType: node.type, groupModule: old.module }));
    } else if (view.mode === 'expanded') {
      setView((old) => ({ ...old, mode: 'dependency', level: 'class',
        focus: node.qualifiedName || node.name, focusId: node.id,
        groupType: '', groupModule: '' }));
    }
  }
  function back(level: Level) {
    if (level === 'system') { setView(EMPTY_VIEW); setSelected(null); }
    else if (level === 'service') {
      setView((old) => ({ ...old, mode: 'layers', level: 'service',
        focus: '', focusId: '', groupType: '', groupModule: '' }));
    } else if (level === 'module') {
      setView((old) => ({ ...old, mode: 'expanded', level: 'module',
        focus: '', focusId: '', groupType: old.role, groupModule: old.module }));
    }
  }
  function applyFilters() {
    const next: Record<string, string[]> = {};
    for (const [key, values] of Object.entries(draftFilters)) {
      if (values.length) next[key] = [...values];
    }
    if (relationship) next.relationship = [relationship];
    if (view.module) {
      next.module = next.module?.length ?
        next.module.filter((name) => name === view.module) : [view.module];
    }
    setView((current) => ({ ...current, filters: next, search: draftSearch, focus: draftFocus }));
  }
  async function openSource(path?: string, offset = 0) {
    if (!path) return;
    try { await adapter?.openSource(path, offset); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not open source'); }
  }

  return <div data-react-view="diagram" className="architecture-explorer">
    <div className="architecture-heading">
      <div><p className="architecture-eyebrow">PROJECT BRAIN / ARCHITECTURE EXPLORER</p>
        <h2>Architecture Explorer <span className="tag accent">Model v{version ?? '—'}</span></h2>
        <p>Live hierarchy and dependencies extracted from project code, never invented graph edges.</p></div>
      <div className="diagram-tools">
        <select aria-label="Diagram view" value={view.mode} onChange={(e) => changeMode(e.target.value as Mode)}>
          {MODES.map((v) => <option value={v.id} key={v.id}>{v.label}</option>)}
        </select>
        <button type="button" className="button compact" title="Zoom in"
          onClick={() => setZoom((z) => Math.min(2.2, z + 0.15))}>+  Zoom</button>
        <span className="diagram-zoom-value">{Math.round(zoom * 100)}%</span>
        <button type="button" className="button compact" title="Zoom out"
          onClick={() => setZoom((z) => Math.max(.45, z - 0.15))}>−  Zoom</button>
        <button type="button" className="button compact" onClick={() => {
          const size = graph?.nodes.length ?? 0;
          setZoom(size > 45 ? .55 : size > 24 ? .7 : size > 12 ? .85 : 1);
          setLayoutVersion((value) => value + 1);
        }}>Fit</button>
        <button type="button" className="button compact" onClick={() => {
          setZoom(1); setLayoutVersion((value) => value + 1); setSelected(null);
        }}>Reset</button>
        <button type="button" className={'button compact ' + (focusCanvas ? 'architecture-active-toggle' : '')}
          aria-pressed={focusCanvas} onClick={() => setFocusCanvas((value) => !value)}>
          {focusCanvas ? 'Show panels' : 'Focus canvas'}
        </button>
      </div>
    </div>
    <nav className="architecture-breadcrumbs" aria-label="Architecture hierarchy">
      {(['system', 'service', 'module', 'class'] as const).map((level, index) => <button key={level}
        type="button" className={'architecture-crumb ' + (view.level === level ? 'current' : '')}
        disabled={!view.module && index > 0} onClick={() => back(level)}
        aria-current={view.level === level ? 'step' : undefined}>{index + 1}. {pretty(level)}</button>)}
      {!!view.module && <span className="architecture-scope">{view.module}{view.role ? ' / ' + pretty(view.role) : ''}</span>}
    </nav>
    <div className={'architecture-workbench ' + (focusCanvas ? 'focus-canvas' : '')}>
      <aside className="architecture-rail" aria-label="Project explorer">
        <h3>Project Explorer</h3><p className="architecture-rail-sub">{model?.projectName ?? 'Current project'}</p>
        <label className="architecture-field">Search current scope
          <input type="search" value={draftSearch} maxLength={200}
            onChange={(e) => setDraftSearch(e.target.value)} placeholder="Class, module, path…" /></label>
        <label className="architecture-field">Focus class for dependency/impact
          <input type="text" value={draftFocus} onChange={(e) => setDraftFocus(e.target.value.slice(0, 200))}
            placeholder="com.example.OrderService" /></label>
        <label className="architecture-field">Relationship type
          <select value={relationship} onChange={(e) => setRelationship(e.target.value)}>
            <option value="">All relationships</option>
            {(facets.relationship ?? []).map((name) =>
              <option key={name} value={name}>{pretty(name)}</option>)}
          </select></label>
        {(['type', 'module', 'confidence'] as const).map((key) =>
          <fieldset key={key} className="cm-filter-group">
            <legend>{pretty(key)}</legend>
            <div className="cm-filter-options">
              {(facets[key] ?? []).slice(0, 200).map((name) => <label key={name}>
                <input type="checkbox" checked={(draftFilters[key] ?? []).includes(name)}
                  onChange={(e) => setDraftFilters((old) => {
                    const values = new Set(old[key] ?? []);
                    if (e.target.checked) values.add(name); else values.delete(name);
                    return { ...old, [key]: [...values].slice(0, 100) };
                  })} />{pretty(name)}</label>)}
            </div>
          </fieldset>)}
        <button type="button" className="button primary compact architecture-wide" onClick={applyFilters}>Apply Filters</button>
        <button type="button" className="button compact architecture-wide" onClick={() => {
          setDraftSearch(''); setDraftFocus(''); setDraftFilters({}); setRelationship('');
          setView(EMPTY_VIEW);
        }}>Reset Explorer</button>
        <div className="architecture-rail-label">VERIFIED NODES</div>
        <div className="architecture-item-list">
          {graph?.nodes.map((node) => <button className={'architecture-list-node ' +
            (selected?.id === node.id ? 'active' : '')} key={node.id}
            type="button" onClick={() => advance(node)} title={node.name}>
            <span className="architecture-dot" /><span>{node.name}</span>
            <small>{count(node.count ?? 1)}</small>
          </button>)}
          {!graph?.nodes.length && <p className="architecture-muted">{loading ? 'Loading…' : 'No nodes in scope'}</p>}
        </div>
      </aside>
      <section className="architecture-map" aria-label="Architecture diagram">
        <div className="architecture-map-top">
          <div><span className="architecture-map-kicker">{view.level.toUpperCase()} LEVEL</span>
            <h3>{view.module || model?.projectName || 'Current project'}</h3></div>
          <span className="tag accent">{graph ? count(graph.totalNodes) : '—'} nodes</span>
        </div>
        {error && <div className="error-banner" role="alert">{error}</div>}
        {graph?.notice && <p className="diagram-notice" role="status">{graph.notice}</p>}
        {graph && graph.truncated && <p className="filter-hint">Graph is bounded at 80 nodes and 240 links. Narrow filters to inspect more.</p>}
        {loading && <p>Loading architecture…</p>}
        {graph && !!graph.nodes.length && <GraphCanvas graph={graph} zoom={zoom} setZoom={setZoom}
          layoutVersion={layoutVersion} selectedId={selected?.id || null} select={advance} />}
        {graph && !graph.nodes.length && !loading && <p>No matching nodes. For Dependencies/Impact, select a focused class.</p>}
        {graph && <p className="result-summary">{count(graph.nodes.length)} / {count(graph.totalNodes)} nodes ·
          {' '}{count(graph.edges.length)} / {count(graph.totalEdges)} relationships</p>}
      </section>
      <aside className="inspect-panel architecture-inspector" aria-label="Node details">
        <h3>Architecture Inspector</h3>
        {selected ? <>
          <div className="detail-row"><span>Name</span><strong>{selected.name}</strong></div>
          <div className="detail-row"><span>Type</span><strong>{pretty(selected.type)}</strong></div>
          <div className="detail-row"><span>Module</span><strong>{selected.module || 'Unknown'}</strong></div>
          <div className="detail-row"><span>Inbound</span><strong>{count(selected.incoming)}</strong></div>
          <div className="detail-row"><span>Outbound</span><strong>{count(selected.outgoing)}</strong></div>
          {selected.path && <button type="button" className="button compact"
            onClick={() => { void openSource(selected.path, selected.sourceOffset ?? 0); }}>Open Source</button>}
          {details.detail && <>
            <h3>Class Evidence</h3>
            <p>{details.detail.qualifiedName || details.detail.name}</p>
            <p>Annotations: {details.detail.annotations?.join(' · ') || 'None'}</p>
            <p>Extends: {details.detail.superTypes?.join(' · ') || 'None'}</p>
          </>}
          <button className="button" type="button" onClick={() => {
            if (selected) setView((old) => ({ ...old, mode: 'impact', level: 'class',
              focus: selected.qualifiedName || selected.name, focusId: selected.id }));
          }}>Explore Impact</button>
        </> : <p>Select a node to explore its verified connections and source evidence.</p>}
      </aside>
    </div>
  </div>;
}
