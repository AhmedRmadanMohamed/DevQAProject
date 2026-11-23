import { useEffect, useState } from 'react';
import type { ProjectBrainSnapshotV2, ProjectFileSummary, ProjectFileDetail, ProjectBrainOptionPage } from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import { count, pretty } from '../model';
import { FacetControls } from '../components/FacetControls';
import { useDetail, usePage, type AppliedFilters } from '../hooks/query-hooks';

const LABELS = { filename: 'Filename', extension: 'Extension', language: 'Language',
  module: 'Module', category: 'Category', contentKind: 'Content Type' } as const;
type Props = { readonly model: ProjectBrainSnapshotV2 | null; readonly adapter: ProjectBrainAdapter | null };

export function Files({ model, adapter }: Props) {
  const version = model?.modelVersion;
  const [draft, setDraft] = useState<Readonly<Record<string, readonly string[]>>>({});
  const [search, setSearch] = useState('');
  const [applied, setApplied] = useState<AppliedFilters>({ modelVersion: -1, search: '', offset: 0, filters: {} });
  const [filenameTerm, setFilenameTerm] = useState('');
  const [nameOptions, setNameOptions] = useState<readonly string[]>([]);
  const [suggestionStatus, setSuggestionStatus] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [openError, setOpenError] = useState('');
  const page = usePage<ProjectFileSummary>(adapter, version, 'files', applied);
  const detail = useDetail<ProjectFileDetail>(adapter, version, 'file-detail', selected ? { path: selected } : null);

  useEffect(() => {
    setDraft({}); setSearch(''); setApplied({ modelVersion: -1, search: '', offset: 0, filters: {} });
    setSelected(null); setNameOptions([]);
  }, [version]);

  const options: Readonly<Record<string, readonly string[]>> = { ...model?.facets?.files,
    filename: [...new Set([...(model?.facets?.files?.filename ?? []), ...nameOptions])] };
  const onChange = (field: string, values: readonly string[]) =>
    setDraft((old) => ({ ...old, [field]: values }));

  async function findNames() {
    if (!adapter || !model || !filenameTerm.trim()) return;
    setSuggestionStatus('Searching indexed filenames…');
    try {
      const response = await adapter.query<ProjectBrainOptionPage>({
        kind: 'file-options', search: filenameTerm.slice(0, 200), limit: 100,
        filters: Object.fromEntries(Object.entries(draft).filter(([key]) => key !== 'filename'))
      });
      if (response.modelVersion !== model.modelVersion) return;
      setNameOptions(response.items);
      setSuggestionStatus(count(response.total) + ' matching filename(s). Select options then Apply.');
    } catch (error) { setSuggestionStatus(error instanceof Error ? error.message : 'Filename search failed'); }
  }
  async function open(path: string) {
    try { setOpenError(''); await adapter?.openSource(path, 0); }
    catch (error) { setOpenError(error instanceof Error ? error.message : 'Could not open source'); }
  }
  const reset = () => {
    setDraft({}); setSearch(''); setSelected(null);
    setApplied({ modelVersion: version ?? -1, search: '', offset: 0, filters: {} });
  };
  return <div data-react-view="files" className="pb-files">
    <div className="section-heading"><div><h2>Project Files</h2>
      <p>File-type aware intelligence. Search and paginate actual indexed files.</p></div>
      <span className="tag accent">MODEL v{version ?? '—'}</span>
    </div>
    <div className="filter-panel">
      <div className="filter-search">
        <input type="search" aria-label="Search files" value={search}
          placeholder="Filename or relative path" maxLength={200}
          onChange={(event) => setSearch(event.target.value)} />
        <button type="button" className="button primary" onClick={() => {
          setSelected(null); setApplied({ modelVersion: version ?? -1, search, offset: 0, filters: { ...draft } });
        }}>Apply Filters</button>
        <button type="button" className="button" onClick={reset}>Clear Filters</button>
      </div>
      <div className="class-facet-finder">
        <label><span>Find filenames beyond the dropdown limit</span>
          <input type="search" maxLength={200} value={filenameTerm}
            onChange={(event) => setFilenameTerm(event.target.value)} /></label>
        <button type="button" className="button" onClick={() => { void findNames(); }}>Find Files</button>
        <span role="status">{suggestionStatus}</span>
      </div>
      <FacetControls labels={LABELS} options={options} selected={draft} onChange={onChange} />
      <p className="filter-hint">Choose any number of values, then Apply. File content never leaves Kotlin.</p>
    </div>
    {(page.error || detail.error || openError) &&
      <div role="alert" className="error-banner">{page.error || detail.error || openError}</div>}
    <div className="result-summary" role="status">{page.loading ? 'Loading…' : count(page.total) + ' matching files'}
      {' · Page ' + (Math.floor(applied.offset / 40) + 1)}</div>
    <div className="table-shell"><table><thead><tr>
      <th>File</th><th>Type</th><th>Module</th><th>Language</th><th>Content</th><th>Source</th>
    </tr></thead><tbody>
      {page.items.map((file) => <tr className="selectable" key={file.path}
        onClick={() => setSelected(file.path)}>
        <td><strong>{file.name}</strong><small>{file.relativePath}</small></td>
        <td>{pretty(file.category)}<small>{file.extension ? '.' + file.extension : 'No extension'}</small></td>
        <td>{file.module || 'Project'}</td><td>{file.language || 'Unknown'}</td>
        <td>{file.contentType || pretty(file.contentKind)}</td>
        <td><button type="button" className="button compact" onClick={(e) => {
          e.stopPropagation(); void open(file.path); }}>Open</button></td>
      </tr>)}
      {!page.loading && !page.items.length &&
        <tr><td colSpan={6}>No matching files in the current project.</td></tr>}
    </tbody></table></div>
    {page.total > 40 && <div className="pagination">
      <button type="button" className="button" disabled={page.loading || applied.offset === 0}
        onClick={() => setApplied((a) => ({ ...a, offset: Math.max(0, a.offset - 40) }))}>Previous</button>
      <span>{applied.offset + 1}–{Math.min(applied.offset + 40, page.total)} of {page.total}</span>
      <button type="button" className="button" disabled={page.loading || applied.offset + 40 >= page.total}
        onClick={() => setApplied((a) => ({ ...a, offset: a.offset + 40 }))}>Next</button>
    </div>}
    {selected && <aside className="inspect-panel">
      <div className="card-heading"><h3>File Details</h3>
        <button type="button" className="button" onClick={() => setSelected(null)}>Close</button></div>
      {!detail.detail ? <p>Loading file evidence…</p> : <>
        {([
          ['Full Path', detail.detail.path], ['Module', detail.detail.module],
          ['File Type', detail.detail.category], ['Extension', detail.detail.extension],
          ['Language', detail.detail.language], ['Content Type', detail.detail.contentType],
          ['IntelliJ Type', detail.detail.fileType], ['MIME Type', detail.detail.mimeType],
          ['Analysis Status', detail.detail.status], ['Analysis Capability', detail.detail.capability]
        ] as const).map(([key, value]) =>
          <div className="detail-row" key={key}><span>{key}</span><strong>{value || 'Unknown'}</strong></div>)}
        <h3>Associated Classes ({detail.detail.classesTotal ?? detail.detail.classes?.length ?? 0})</h3>
        <p>{detail.detail.classes?.join(' · ') || 'None discovered'}</p>
        <h3>Referenced Dependencies ({detail.detail.dependenciesTotal ?? detail.detail.dependencies?.length ?? 0})</h3>
        <p>{detail.detail.dependencies?.join(' · ') || 'None resolved'}</p>
        <button type="button" className="button primary" onClick={() => { void open(detail.detail!.path); }}>
          Open Source
        </button>
      </>}
    </aside>}
  </div>;
}
