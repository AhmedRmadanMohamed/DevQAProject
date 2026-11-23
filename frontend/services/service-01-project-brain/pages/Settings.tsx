import { useEffect, useState } from 'react';
import type { ScanSettings, ProjectBrainSnapshotV2 } from '../../../../contracts/service-01-project-brain/project-brain.v2';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
type Props = { readonly model: ProjectBrainSnapshotV2 | null; readonly adapter: ProjectBrainAdapter | null };

const DEFAULTS: ScanSettings = {
  includeTests: true, includeGenerated: false, autoRefresh: true,
  maxFileBytes: 8 * 1024 * 1024, includedModules: [], excludedDirectories: []
};
export function Settings({ model, adapter }: Props) {
  const [settings, setSettings] = useState<ScanSettings>(DEFAULTS);
  const [excludedText, setExcludedText] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    setLoading(true); setError(''); setNotice('');
    if (!adapter) { setLoading(false); return; }
    void adapter.readSettings<ScanSettings>().then((value) => {
      if (!alive || !value) return;
      const loaded = {
        includeTests: value.includeTests === true,
        includeGenerated: value.includeGenerated === true,
        autoRefresh: value.autoRefresh === true,
        maxFileBytes: Math.max(16 * 1024, Math.min(128 * 1024 * 1024, value.maxFileBytes || DEFAULTS.maxFileBytes)),
        includedModules: Array.isArray(value.includedModules) ? [...value.includedModules] : [],
        excludedDirectories: Array.isArray(value.excludedDirectories) ? [...value.excludedDirectories] : []
      };
      setSettings(loaded);
      setExcludedText(loaded.excludedDirectories.join(', '));
    }).catch((cause: unknown) => {
      if (alive) setError(cause instanceof Error ? cause.message : 'Unable to load scan settings');
    }).finally(() => { if (alive) setLoading(false); });
    if (!adapter) setLoading(false);
    return () => { alive = false; };
  }, [adapter]);
  function modify(patch: Partial<ScanSettings>) {
    setSettings((current) => ({ ...current, ...patch }));
    setNotice('');
  }
  async function save() {
    if (!adapter) { setError('Scan settings are only available inside IntelliJ'); return; }
    setError(''); setNotice(''); setSaving(true);
    try {
      const dirs = excludedText.split(',').map((s) => s.trim().replace(/\\/g, '/').replace(/^\/+|\/+$/g, ''))
        .filter(Boolean);
      if (dirs.some((name) => name.includes('..') || /[\u0000\r\n]/.test(name))) {
        throw new Error('Excluded directories must be project-relative without traversal');
      }
      const next: ScanSettings = { ...settings, excludedDirectories: [...new Set(dirs)],
        includedModules: [...new Set(settings.includedModules)],
        maxFileBytes: Math.max(16 * 1024, Math.min(128 * 1024 * 1024, Math.round(settings.maxFileBytes))) };
      await adapter.writeSettings(next);
      setSettings(next); setNotice('Settings saved for this IntelliJ project. They apply on the next analysis.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Settings could not be saved');
    } finally { setSaving(false); }
  }
  return <div data-react-view="settings" className="pb-settings">
    <div className="section-heading"><div><h2>Project Scan Settings</h2>
      <p>Saved locally in IntelliJ and applied on the next analysis.</p></div>
      <span className="tag accent">MODEL v{model?.modelVersion ?? '—'}</span>
    </div>
    <section className="card settings-card">
      {loading && <p role="status">Loading project-level settings…</p>}
      <label className="settings-toggle">
        <input type="checkbox" checked={settings.includeTests}
          onChange={(e) => modify({ includeTests: e.target.checked })} /> Include test sources
      </label>
      <label className="settings-toggle">
        <input type="checkbox" checked={settings.includeGenerated}
          onChange={(e) => modify({ includeGenerated: e.target.checked })} /> Include generated sources
      </label>
      <label className="settings-toggle">
        <input type="checkbox" checked={settings.autoRefresh}
          onChange={(e) => modify({ autoRefresh: e.target.checked })} /> Automatically refresh changed files
      </label>
      <label><strong>Maximum file size (MB)</strong>
        <input type="number" min={0.015625} max={128} step={1}
          value={Math.round(settings.maxFileBytes / (1024 * 1024))}
          onChange={(e) => modify({ maxFileBytes: Number(e.target.value) * 1024 * 1024 })} />
      </label>
      <fieldset className="cm-filter-group">
        <legend>Include only selected modules (none means all)</legend>
        <div className="cm-filter-options">
          {(model?.modules ?? []).map((module) => <label key={module.name}>
            <input type="checkbox" checked={settings.includedModules.includes(module.name)}
              onChange={(event) => modify({ includedModules: event.target.checked ?
                [...new Set([...settings.includedModules, module.name])] :
                settings.includedModules.filter((name) => name !== module.name) })} />
            {module.name}
          </label>)}
          {!model?.modules.length && <p>Analyze the project to list available modules.</p>}
        </div>
      </fieldset>
      <label><strong>Excluded directories (project-relative, comma-separated)</strong>
        <input type="text" value={excludedText} placeholder="src/generated, docs/archive"
          onChange={(e) => { setExcludedText(e.target.value); setNotice(''); }} />
      </label>
      <button type="button" className="button primary" disabled={saving || loading}
        onClick={() => { void save(); }}>{saving ? 'Saving…' : 'Save Scan Settings'}</button>
      {notice && <p className="settings-notice" role="status">{notice}</p>}
      {error && <p className="error-banner" role="alert">{error}</p>}
    </section>
  </div>;
}
