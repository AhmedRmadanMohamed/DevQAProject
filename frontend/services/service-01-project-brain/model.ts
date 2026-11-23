import type { ProjectBrainSnapshotV2, ProjectBrainModule } from '../../../contracts/service-01-project-brain/project-brain.v2';

export type Snapshot = ProjectBrainSnapshotV2;
export type Route = 'dashboard' | 'overview' | 'components' | 'files' | 'diagram' | 'frameworks' | 'settings';
export type ClassicTab = 'dashboard' | 'overview' | 'components' | 'files' | 'diagram' | 'frameworks' | 'settings';

export function count(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? Math.round(value).toLocaleString('en-US') : '—';
}

export function pretty(value: unknown): string {
  return String(value ?? 'Unknown').toLowerCase().split('_')
    .map((part) => part ? part.charAt(0).toUpperCase() + part.slice(1) : '')
    .join(' ');
}

export function duration(ms: unknown): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0) return '—';
  return ms < 1000 ? String(Math.round(ms)) + ' ms' : (ms / 1000).toFixed(2) + ' s';
}

export interface HealthState {
  label: string; shortLabel: string; tone: string; description: string;
}
export function health(model: Snapshot | null, running: boolean, outcome: string | null): HealthState {
  if (running) return { label: 'ANALYZING', shortLabel: 'Running', tone: 'accent',
    description: model ? 'A new scan is running; the last published model remains visible.' : 'Scanning project files.' };
  if (outcome === 'FAILED') return { label: 'ANALYSIS FAILED', shortLabel: 'Failed', tone: 'danger',
    description: model ? 'Latest scan failed; the previous results are stale.' : 'No usable analysis is available.' };
  if (outcome === 'CANCELED') return { label: 'ANALYSIS CANCELED', shortLabel: 'Canceled', tone: 'warning',
    description: model ? 'Latest scan was canceled; the previous results are stale.' : 'No usable analysis is available.' };
  if (!model) return { label: 'NOT ANALYZED', shortLabel: 'Not started', tone: '',
    description: 'Run Analyze to build the project model.' };
  if (model.status === 'PARTIALLY COMPLETED') return { label: 'PARTIALLY COMPLETED', shortLabel: 'Partial', tone: 'warning',
    description: 'Some files were skipped, unsupported or failed, or analysis reported errors.' };
  if (model.status === 'COMPLETED WITH WARNINGS') return { label: 'COMPLETED WITH WARNINGS', shortLabel: 'Warnings', tone: 'warning',
    description: 'The project model has warnings or unresolved class roles.' };
  if (model.status === 'ANALYSIS COMPLETE') return { label: 'ANALYSIS COMPLETE', shortLabel: 'Complete', tone: 'success',
    description: 'No incomplete files, unresolved roles or reported analysis errors.' };
  return { label: model.status, shortLabel: 'Unknown', tone: 'warning', description: 'Review analysis status.' };
}

export function selectedRoleCounts(modules: readonly ProjectBrainModule[], selection: readonly string[]) {
  const selected = new Set(selection);
  const counts: Record<string, number> = {};
  let complete = true;
  for (const module of modules) {
    if (!selected.has(module.name)) continue;
    if (!module.roleCounts) { complete = false; continue; }
    for (const [role, amount] of Object.entries(module.roleCounts)) {
      if (Number.isSafeInteger(amount) && amount >= 0) counts[role] = (counts[role] ?? 0) + amount;
    }
  }
  return { counts, complete, total: Object.values(counts).reduce((sum, n) => sum + n, 0) };
}
