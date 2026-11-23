import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import type {
  ComponentFacet, ProjectBrainComponent, ProjectBrainComponentDetail,
  ProjectBrainComponentPage, ProjectBrainOptionPage, ProjectBrainSnapshotV2
} from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import { count, pretty, selectedRoleCounts } from '../model';

const PAGE_SIZE = 40;
const FACETS: readonly ComponentFacet[] = ['type', 'role', 'class', 'module', 'language', 'confidence'];
const FIELD_LABELS: Readonly<Record<ComponentFacet, string>> = {
  type: 'Component Type', role: 'Role', class: 'Class', module: 'Module',
  language: 'Language', confidence: 'Confidence'
};
type SortDirection = 'ASC' | 'DESC';
type Filters = Partial<Record<ComponentFacet, string[]>>;
type AdvancedField = 'name' | 'qualifiedName' | 'package' | 'module' | 'role' | 'type' | 'language' | 'confidence' | 'incoming' | 'outgoing';
type TextOperator = 'EQ' | 'NE' | 'LIKE' | 'NOT_LIKE' | 'IN' | 'NOT_IN' | 'GT' | 'GE' | 'LT' | 'LE' | 'BETWEEN';
interface AdvancedRule {
  field: AdvancedField; operator: TextOperator; value: string; join: 'AND' | 'OR'; negate: boolean;
}
interface Applied {
  modelVersion: number;
  scope: readonly string[];
  filters: Filters;
  search: string;
  sort: SortDirection;
  advanced: readonly AdvancedRule[];
  offset: number;
}
type Props = {
  readonly model: ProjectBrainSnapshotV2 | null;
  readonly adapter: ProjectBrainAdapter | null;
};

const EMPTY_FILTERS: Filters = {};
const ALLOWED_FIELDS: readonly AdvancedField[] = [
  'name', 'qualifiedName', 'package', 'module', 'role', 'type', 'language', 'confidence', 'incoming', 'outgoing'
];
const TEXT_OPERATORS: readonly TextOperator[] = ['EQ', 'NE', 'LIKE', 'NOT_LIKE', 'IN', 'NOT_IN'];
const NUMBER_OPERATORS: readonly TextOperator[] = ['GT', 'GE', 'LT', 'LE', 'BETWEEN'];
const ALLOWED_OPERATORS: readonly TextOperator[] = [...TEXT_OPERATORS, ...NUMBER_OPERATORS];
const numeric = (field: AdvancedField) => field === 'incoming' || field === 'outgoing';

function normalizeFilters(filters: Filters, scope: readonly string[]): Record<string, string[]> {
  const active = new Set(scope);
  const narrowed = filters.module?.length
    ? filters.module.filter((name) => active.has(name))
    : [...scope];
  const result: Record<string, string[]> = { module: narrowed.length ? narrowed : [...scope] };
  for (const field of FACETS) {
    if (field !== 'module' && filters[field]?.length) result[field] = [...filters[field]!];
  }
  return result;
}

export function Components({ model, adapter }: Props) {
  const [draftScope, setDraftScope] = useState<string[]>([]);
  const [scopeSearch, setScopeSearch] = useState('');
  const [scopeOpen, setScopeOpen] = useState(false);
  const [draftFilters, setDraftFilters] = useState<Filters>(EMPTY_FILTERS);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortDirection>('ASC');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [rules, setRules] = useState<AdvancedRule[]>([]);
  const [applied, setApplied] = useState<Applied | null>(null);
  const [items, setItems] = useState<readonly ProjectBrainComponent[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectBrainComponent | null>(null);
  const [classSearch, setClassSearch] = useState('');
  const [classSuggestions, setClassSuggestions] = useState<readonly string[]>([]);
  const [suggestionNotice, setSuggestionNotice] = useState('');
  const sequence = useRef(0);
  const detailSequence = useRef(0);
  const modules = useMemo(() => (model?.modules ?? []).map((module) => module.name)
    .filter((name, i, list) => list.indexOf(name) === i).sort((a, b) => a.localeCompare(b)), [model]);

  const roleSummary = selectedRoleCounts(model?.modules ?? [], draftScope);
  const isScopePending = applied !== null &&
    [...applied.scope].sort().join('|') !== [...draftScope].sort().join('|');

  useEffect(() => {
    // Model changes invalidate every scoped result and pending async request.
    sequence.current++;
    detailSequence.current++;
    setApplied(null); setItems([]); setTotal(0); setSelectedId(null); setDetail(null);
    setDraftScope([]); setDraftFilters({}); setSearch(''); setRules([]); setClassSuggestions([]);
    setScopeOpen(false); setAdvancedOpen(false);
    setError('');
  }, [model?.modelVersion]);

  useEffect(() => {
    if (!applied || !adapter || !model || applied.modelVersion !== model.modelVersion) return;
    const current = ++sequence.current;
    const version = model.modelVersion;
    let alive = true;
    setLoading(true); setError('');
    const payload: { kind: 'components'; filters: Record<string, string[]>;
      search: string; offset: number; limit: number; sortNameDirection: SortDirection;
      advanced?: { rules: readonly AdvancedRule[] } } = {
        kind: 'components',
        filters: normalizeFilters(applied.filters, applied.scope),
        search: applied.search,
        offset: applied.offset,
        limit: PAGE_SIZE,
        sortNameDirection: applied.sort
      };
    if (applied.advanced.length) payload.advanced = { rules: applied.advanced };
    void adapter.query<ProjectBrainComponentPage>(payload).then((response) => {
      if (!alive || current !== sequence.current || adapter.latest()?.modelVersion !== version) return;
      if (response.modelVersion !== version || !Array.isArray(response.items) ||
          !Number.isSafeInteger(response.total) || response.total < 0 ||
          response.items.length > PAGE_SIZE) {
        throw new Error('Invalid bounded component page from IntelliJ.');
      }
      setItems(response.items); setTotal(response.total);
    }).catch((cause: unknown) => {
      if (!alive || current !== sequence.current) return;
      setError(cause instanceof Error ? cause.message : 'Unable to load components.');
    }).finally(() => {
      if (alive && current === sequence.current) setLoading(false);
    });
    return () => { alive = false; };
  }, [adapter, applied, model]);

  useEffect(() => {
    if (!selectedId || !model || !adapter) return;
    const current = ++detailSequence.current;
    const version = model.modelVersion;
    let alive = true;
    setDetail(null);
    void adapter.query<ProjectBrainComponentDetail>({ kind: 'component-detail', id: selectedId })
      .then((result) => {
        if (alive && detailSequence.current === current && adapter.latest()?.modelVersion === version &&
            result.modelVersion === version && result.item?.id === selectedId) setDetail(result.item);
      })
      .catch((cause: unknown) => {
        if (alive && detailSequence.current === current)
          setError(cause instanceof Error ? cause.message : 'Unable to load component details.');
      });
    return () => { alive = false; };
  }, [selectedId, model, adapter]);

  useEffect(() => {
    if (!classSearch.trim() || !applied?.scope.length || !adapter || !model) {
      setClassSuggestions([]); setSuggestionNotice('');
      return;
    }
    let alive = true;
    const version = model.modelVersion;
    const timer = setTimeout(() => {
      const filters = normalizeFilters(
        Object.fromEntries(Object.entries(draftFilters).filter(([field]) => field !== 'class')) as Filters,
        applied.scope
      );
      void adapter.query<ProjectBrainOptionPage>({
        kind: 'component-options', filters, search: classSearch.trim().slice(0, 200), limit: 100
      }).then((result) => {
        if (!alive || adapter.latest()?.modelVersion !== version) return;
        if (result.modelVersion !== version || !Array.isArray(result.items) ||
            result.items.length > 100 || !result.items.every((name) => typeof name === 'string')) return;
        setClassSuggestions(result.items);
        setSuggestionNotice(result.total > result.items.length
          ? 'Showing 100 class names. Refine your search for more matches.'
          : count(result.total) + ' matching class names');
      }).catch(() => { if (alive) setSuggestionNotice('Unable to retrieve class suggestions.'); });
    }, 250);
    return () => { alive = false; clearTimeout(timer); };
  }, [classSearch, applied?.scope, adapter, model, draftFilters]);

  function updateFacet(field: ComponentFacet, value: string, checked: boolean) {
    setDraftFilters((current) => {
      const values = new Set(current[field] ?? []);
      if (checked) values.add(value); else values.delete(value);
      return { ...current, [field]: [...values].slice(0, 100) };
    });
  }

  function applyFilters(offset = 0) {
    const scope = applied?.scope ?? [];
    if (!scope.length) return;
    const validRules = rules.filter((rule) =>
      rule.value.trim() && rule.value.length <= 200 &&
      ALLOWED_FIELDS.includes(rule.field) && ALLOWED_OPERATORS.includes(rule.operator) &&
      (numeric(rule.field) ? NUMBER_OPERATORS.includes(rule.operator) &&
        (rule.operator === 'BETWEEN'
          ? /^\d+,\s*\d+$/.test(rule.value)
          : /^\d+$/.test(rule.value))
        : TEXT_OPERATORS.includes(rule.operator))
    ).slice(0, 12);
    setSelectedId(null); setDetail(null);
    setApplied({ modelVersion: model?.modelVersion ?? -1, scope, filters: { ...draftFilters }, search: search.slice(0, 200),
      sort, advanced: validRules, offset });
  }
  function showClasses() {
    if (!draftScope.length) return;
    setScopeOpen(false);
    setSelectedId(null); setDetail(null);
    setDraftFilters({}); setSearch(''); setRules([]); setClassSearch('');
    setApplied({ modelVersion: model?.modelVersion ?? -1, scope: [...draftScope], filters: {}, search: '', sort: 'ASC', advanced: [], offset: 0 });
  }
  function clearServices() {
    setScopeOpen(false);
    setDraftScope([]); setDraftFilters({}); setSearch(''); setRules([]);
    setApplied(null); setItems([]); setTotal(0); setSelectedId(null); setDetail(null);
    sequence.current++;
  }
  function clearFilters() {
    setDraftFilters({}); setSearch(''); setRules([]); setSort('ASC'); setClassSearch('');
    if (applied) setApplied({ ...applied, filters: {}, search: '', sort: 'ASC', advanced: [], offset: 0 });
  }
  async function openSource(component: ProjectBrainComponent) {
    if (!component.path || !adapter) return;
    try { await adapter.openSource(component.path, component.sourceOffset ?? 0); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'IntelliJ could not open the source file.'); }
  }
  const filteredModules = modules.filter((name) => name.toLowerCase().includes(scopeSearch.toLowerCase()));
  const summaryRoles = Object.entries(roleSummary.counts).filter(([, amount]) => amount > 0)
    .sort(([a], [b]) => a.localeCompare(b));
  const available = model?.facets?.components ?? {};
  const activeScope = applied?.scope ?? [];
  const facetOptions = (key: ComponentFacet): readonly string[] => {
    if (key === 'module') return activeScope;
    if (key === 'role') return [...new Set([...(available.role ?? []), ...Object.keys(roleSummary.counts)])].sort();
    return available[key] ?? [];
  };

  return <div className="pb-components" data-react-view="components">
    <div className="section-heading"><div><h2>Components</h2>
      <p>Select services and query actual Kotlin classified classes. {count(model?.metrics.classes)} project-wide.</p></div>
      <span className="tag accent">MODEL v{model?.modelVersion ?? '—'}</span>
    </div>
    <section className="cm-scope" aria-label="Select services before viewing classes">
      <div className="cm-scope-heading"><div><span className="cm-kicker">01 / SELECT YOUR SERVICES</span>
        <h2>Choose modules to explore</h2>
        <p>Nothing loads until you choose modules and click Show Classes.</p></div>
        <span className="tag accent">{applied ? activeScope.length + ' active' : 'No scope applied'}</span></div>
      <div className="cm-scope-toolbar">
        <div className="cm-dropdown">
          <button className="cm-selector" type="button" aria-expanded={scopeOpen}
            onClick={() => setScopeOpen((open) => !open)}>
            <span>{draftScope.length ? `${draftScope.length} module(s) selected` : 'Select modules'}</span>
            <b aria-hidden="true">{scopeOpen ? '▴' : '▾'}</b>
          </button>
          {scopeOpen && <div className="cm-menu" aria-label="Available modules">
            <input type="search" value={scopeSearch} onChange={(e) => setScopeSearch(e.target.value)}
              placeholder="Search modules…" aria-label="Search services and modules" autoFocus />
            <div className="cm-option-list">
              {filteredModules.map((module) => <label key={module} className="cm-option" data-scope-choice="true">
                <input type="checkbox" checked={draftScope.includes(module)}
                  onChange={(event) => setDraftScope((prev) => event.target.checked
                    ? [...new Set([...prev, module])].slice(0, 100) : prev.filter((x) => x !== module))} />
                <span>{module}</span></label>)}
              {!filteredModules.length && <p>No matching modules. Run Analyze if none were discovered.</p>}
            </div>
          </div>}
        </div>
        <button className="button primary cm-show" type="button" disabled={!draftScope.length} onClick={showClasses}>Show Classes →</button>
        <button className="button" type="button" disabled={!draftScope.length && !applied} onClick={clearServices}>Clear Services</button>
      </div>
      {!!draftScope.length && <div className="cm-chips">{draftScope.map((name) =>
        <span key={name} className="cm-chip">{name}</span>)}</div>}
      {isScopePending && <p className="cm-notice" role="status">Selection changed. Click Show Classes to apply the new scope.</p>}
    </section>
    {!!draftScope.length && <section className="cm-scoped-roles" aria-label="Counts for selected services">
      <p className="cm-role-hint">SELECTED SERVICE BREAKDOWN · {draftScope.length} module(s) · {count(roleSummary.total)} classes
        {!roleSummary.complete && ' · Some modules have no role breakdown yet.'}</p>
      <div className="role-cards">{summaryRoles.map(([role, amount]) =>
        <button type="button" key={role} className="role-card"
          onClick={() => updateFacet('role', role, !(draftFilters.role ?? []).includes(role))}>
          <strong>{pretty(role)}</strong><span>{count(amount)}</span>
        </button>)}
      </div>
    </section>}
    {applied ? <>
      <div className="cm-filter-workspace">
        <div className="cm-section-title"><span className="cm-kicker">02 / FIND & FILTER</span>
          <h2>Explore classes in {activeScope.length} selected module(s)</h2>
          <p>Search within the applied scope. Draft filters never trigger a query until Apply Filters.</p>
        </div>
        <div className="cm-search-row">
          <label className="cm-main-search">Class search · commas, AND / OR / NOT / LIKE
            <input type="search" value={search} onChange={(e) => setSearch(e.target.value.slice(0, 200))}
              placeholder="OrderService, PaymentService" aria-label="Search class names in selected modules" />
          </label>
          <button className="button primary" type="button" onClick={() => applyFilters()}>Apply Filters</button>
          <button className="button" type="button" onClick={clearFilters}>Clear Filters</button>
        </div>
        <div className="cm-token-palette" aria-label="Class name search operators">
          {(['AND', 'OR', 'NOT', 'LIKE'] as const).map((token) => <button key={token}
            type="button" onClick={() => setSearch((value) => (value + ' ' + token + ' ').slice(0, 200))}>{token}</button>)}
        </div>
        <button className="button cm-advanced-toggle" type="button" aria-expanded={advancedOpen}
          onClick={() => setAdvancedOpen(!advancedOpen)}>
          <span>Advanced Search</span>
          <small>{Object.values(draftFilters).reduce((sum, values) => sum + (values?.length ?? 0), 0) + rules.length} active</small>
          <b aria-hidden="true">{advancedOpen ? '▴' : '▾'}</b>
        </button>
        {advancedOpen && <div className="cm-advanced">
          <div className="cm-filter-grid">
            {FACETS.filter((field) => field !== 'class').map((field) =>
              <details className="cm-filter-dropdown" key={field} data-facet-container={field}>
                <summary><span>{FIELD_LABELS[field]}</span>
                  <small>{(draftFilters[field] ?? []).length || 'All'}</small></summary>
                <div className="cm-filter-options">{facetOptions(field).slice(0, 200).map((value) =>
                  <label key={value}><input type="checkbox" checked={(draftFilters[field] ?? []).includes(value)}
                    onChange={(e) => updateFacet(field, value, e.target.checked)} />{pretty(value)}</label>)}
                  {!facetOptions(field).length && <p>No options in snapshot.</p>}
                </div>
              </details>)}
          </div>
          <div className="cm-search-row cm-advanced-search-row">
            <label className="cm-main-search">Find class suggestions
              <input type="search" value={classSearch} onChange={(e) => setClassSearch(e.target.value.slice(0, 200))}
                placeholder="Search the full class catalog" />
            </label>
            <label className="cm-main-search cm-sort-control">Sort by class name
              <select value={sort} onChange={(e) => setSort(e.target.value as SortDirection)}>
                <option value="ASC">Ascending</option><option value="DESC">Descending</option></select>
            </label>
          </div>
          {!!classSuggestions.length && <div className="cm-chips">
            {classSuggestions.slice(0, 30).map((name) => <button className="cm-chip" type="button" key={name}
              onClick={() => { updateFacet('class', name, true); setClassSearch(''); }}>{name} +</button>)}
          </div>}
          {!!draftFilters.class?.length && <p className="cm-role-hint">
            Selected classes: {draftFilters.class.join(', ')} (Apply Filters to search)
          </p>}
          {!!suggestionNotice && <p className="cm-notice" role="status">{suggestionNotice}</p>}
          <div className="cm-rule-builder">
            <div className="cm-advanced-head"><div><h3>Structured rules</h3>
              <p>Validated by Kotlin · maximum 12 text or numeric rules.</p></div>
              <button className="button compact" disabled={rules.length >= 12} type="button"
                onClick={() => setRules((prev) => [...prev, { field: 'name', operator: 'LIKE', value: '%Service%',
                  join: 'AND', negate: false }])}>+ Add rule</button>
            </div>
            {rules.map((rule, index) => <div className="cm-rule" key={index}>
              <span className="cm-rule-count">{index + 1}</span>
              {index > 0 && <select aria-label="Rule join" value={rule.join}
                onChange={(e) => setRules((prev) => prev.map((r, i) => i === index ? { ...r, join: e.target.value as 'AND' | 'OR' } : r))}>
                <option value="AND">AND</option><option value="OR">OR</option></select>}
              <select data-advanced-select="field" aria-label="Advanced field" value={rule.field}
                onChange={(e) => setRules((prev) => prev.map((r, i) => i === index ? { ...r, field: e.target.value as AdvancedField, operator: (e.target.value === 'incoming' || e.target.value === 'outgoing') ? 'GT' : 'LIKE' } : r))}>
                {ALLOWED_FIELDS.map((field) => <option key={field} value={field}>{field}</option>)}</select>
              <select data-advanced-select="operator" aria-label="Advanced operator" value={rule.operator}
                onChange={(e) => setRules((prev) => prev.map((r, i) => i === index ? { ...r, operator: e.target.value as TextOperator } : r))}>
                {(numeric(rule.field) ? NUMBER_OPERATORS : TEXT_OPERATORS).map((operator) => <option key={operator} value={operator}>{operator}</option>)}</select>
              <input aria-label="Advanced value" value={rule.value} maxLength={200}
                onChange={(e) => setRules((prev) => prev.map((r, i) => i === index ? { ...r, value: e.target.value } : r))} />
              <label className={'cm-not-button ' + (rule.negate ? 'active' : '')}><input type="checkbox" checked={rule.negate}
                onChange={(e) => setRules((prev) => prev.map((r, i) => i === index ? { ...r, negate: e.target.checked } : r))} />NOT</label>
              <button type="button" className="button compact" aria-label={'Remove rule ' + (index + 1)}
                onClick={() => setRules((prev) => prev.filter((_, i) => i !== index))}>×</button>
            </div>)}
            {!rules.length && <p className="cm-help">No structured rules. Add one only when the normal filters are not enough.</p>}
          </div>
        </div>}
      </div>
      {error && <div className="error-banner" role="alert">{error}</div>}
      <div className="result-summary" role="status">
        {loading ? 'Loading results…' : count(total) + ' matching results'} · Page {Math.floor(applied.offset / PAGE_SIZE) + 1}
      </div>
      <div className="table-shell cm-results"><table><thead><tr>
        <th>Class</th><th>Role</th><th>Module</th><th>Language</th><th>Confidence</th><th>Source</th>
      </tr></thead><tbody>
        {items.map((item, i) => <Fragment key={item.id}>
          {(i === 0 || items[i - 1]?.module !== item.module) && <tr className="cm-module-row">
            <td colSpan={6}><span>▦ {item.module || 'Project'}</span><small>Service / module · grouped alphabetically</small></td>
          </tr>}
          <tr className="selectable" onClick={() => setSelectedId(item.id)}>
            <td><strong>{item.name}</strong><small>{item.packageName || 'default'}</small></td>
            <td><span className="tag accent">{pretty(item.type)}</span></td>
            <td>{item.module || 'Project'}</td><td>{item.language || model?.primaryLanguage.name || 'Unknown'}</td>
            <td>{pretty(item.confidence)}</td>
            <td><button type="button" className="button compact"
              disabled={!item.path} onClick={(e) => { e.stopPropagation(); void openSource(item); }}>Open</button></td>
          </tr>
        </Fragment>)}
        {!loading && !items.length && <tr><td colSpan={6}>No matching classes in the selected modules.</td></tr>}
      </tbody></table></div>
      {total > PAGE_SIZE && <div className="pagination">
        <button className="button" type="button" disabled={loading || applied.offset === 0}
          onClick={() => setApplied({ ...applied, offset: Math.max(0, applied.offset - PAGE_SIZE) })}>Previous</button>
        <span>{applied.offset + 1}–{Math.min(applied.offset + PAGE_SIZE, total)} of {total}</span>
        <button className="button" type="button" disabled={loading || applied.offset + PAGE_SIZE >= total}
          onClick={() => setApplied({ ...applied, offset: applied.offset + PAGE_SIZE })}>Next</button>
      </div>}
      {selectedId && <aside className="inspect-panel">
        <div className="card-heading"><h2>Component Details</h2>
          <button type="button" className="button compact" onClick={() => setSelectedId(null)}>Close</button></div>
        {!detail ? <p>Loading component evidence…</p> : <>
          <div className="detail-row"><span>Class</span><strong>{detail.qualifiedName || detail.name}</strong></div>
          <div className="detail-row"><span>Role</span><strong>{pretty(detail.type)}</strong></div>
          <div className="detail-row"><span>Module</span><strong>{detail.module || 'Unknown'}</strong></div>
          <div className="detail-row"><span>Language</span><strong>{detail.language || 'Unknown'}</strong></div>
          <div className="detail-row"><span>Confidence</span><strong>{pretty(detail.confidence)}</strong></div>
          <div className="detail-row"><span>Path</span><strong>{detail.path || 'Unknown'}</strong></div>
          <h3>Annotations</h3><p>{detail.annotations?.join(' · ') || 'No annotations'}</p>
          <h3>Extends</h3><p>{detail.superTypes?.join(' · ') || 'No superclass evidence'}</p>
          <button type="button" className="button primary" disabled={!detail.path}
            onClick={() => { void openSource(detail); }}>Open Source</button>
        </>}
      </aside>}
    </> : <section className="cm-empty" role="status">
      <span className="cm-empty-icon">▦</span><h2>No classes loaded</h2>
      <p>Select one or more services above, then click <strong>Show Classes</strong>.
        The frontend never loads all project classes implicitly.</p>
    </section>}
  </div>;
}
