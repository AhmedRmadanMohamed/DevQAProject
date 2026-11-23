import type { ProjectBrainSnapshotV2, ProjectBrainQueryKind } from '../../../../contracts/service-01-project-brain/project-brain.v2';

export interface IntelliJHostBridge {
  request(request: string): Promise<string>;
  analyzeProject(requestId: string): Promise<string>;
  cancelAnalysis(): Promise<string>;
  readSettings(): Promise<string>;
  writeSettings(options: unknown): Promise<string>;
  query(payload: unknown): Promise<string>;
  frontendReady(): Promise<string>;
  openSource(path: string, offset: number): Promise<string>;
}

export interface QueryRequest {
  kind: ProjectBrainQueryKind;
  [key: string]: unknown;
}
export interface ProjectBrainAdapter {
  readonly mode: string;
  latest(): ProjectBrainSnapshotV2 | null;
  subscribe(listener: () => void): () => void;
  ready(): Promise<unknown>;
  analyze(): Promise<string>;
  cancel(): Promise<void>;
  query<T = unknown>(request: QueryRequest): Promise<T>;
  readSettings<T = unknown>(): Promise<T | null>;
  writeSettings(options: unknown): Promise<void>;
  openSource(path: string, offset?: number): Promise<unknown>;
}

declare global {
  interface Window {
    DevQAIntellijBridge?: IntelliJHostBridge;
    DevQAProjectBrain?: ProjectBrainAdapter;
    DEVQA_BACKEND_ADAPTERS?: Record<string, ProjectBrainAdapter>;
    receiveDevQAProjectBrainSnapshot?: (value: unknown) => void;
    receiveDevQAProjectBrainError?: (message: unknown) => void;
    receiveDevQAProjectBrainProgress?: (value: unknown) => void;
    receiveDevQASourceNavigationError?: (message: unknown) => void;
    DEVQA_REACT_BOOTSTRAPPED?: boolean;
  }
}

const SNAPSHOT_STATUSES = new Set(['ANALYSIS COMPLETE', 'COMPLETED WITH WARNINGS', 'PARTIALLY COMPLETED']);
const COUNT_KEYS = ['classes', 'projectFiles', 'discoveredFiles', 'components',
  'unclassified', 'relationships', 'diagnostics', 'diagnosticErrors', 'diagnosticWarnings',
  'durationMillis', 'analyzedFiles', 'unsupportedFiles', 'skippedFiles', 'failedFiles',
  'classificationCoverage'] as const;
let adapterInstance: ProjectBrainAdapter | null = null;
let requestId = 0;

function object(value: unknown, name: string): Record<string, unknown> {
  let parsed: unknown = value;
  if (typeof value === 'string') {
    try { parsed = JSON.parse(value); }
    catch { throw new TypeError('IntelliJ returned invalid JSON for ' + name); }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new TypeError('IntelliJ returned invalid object for ' + name);
  }
  return parsed as Record<string, unknown>;
}
function validSnapshot(value: unknown): ProjectBrainSnapshotV2 {
  const snapshot = object(value, 'snapshot');
  const m = object(snapshot.metrics, 'snapshot metrics');
  if (snapshot.schemaVersion !== 2 || typeof snapshot.projectName !== 'string' ||
      !Number.isSafeInteger(snapshot.modelVersion) || (snapshot.modelVersion as number) < 0 ||
      !SNAPSHOT_STATUSES.has(String(snapshot.status)) ||
      !snapshot.primaryFramework || !snapshot.primaryLanguage ||
      !Array.isArray(snapshot.modules) || !Array.isArray(snapshot.frameworks) ||
      !snapshot.facets || !snapshot.roleCounts ||
      COUNT_KEYS.some((key) => !Number.isSafeInteger(m[key]) || (m[key] as number) < 0)) {
    throw new TypeError('Project Brain v2 snapshot is incomplete');
  }
  const n = (key: typeof COUNT_KEYS[number]) => m[key] as number;
  if (n('projectFiles') !== n('analyzedFiles') + n('unsupportedFiles') ||
      n('discoveredFiles') !== n('projectFiles') + n('skippedFiles') + n('failedFiles') ||
      n('classes') !== n('components') + n('unclassified') ||
      n('classificationCoverage') !== (n('classes') ? Math.floor(n('components') * 100 / n('classes')) : 0) ||
      n('classificationCoverage') > 100 ||
      n('diagnosticErrors') + n('diagnosticWarnings') > n('diagnostics') ||
      (snapshot.status === 'ANALYSIS COMPLETE' &&
        (n('failedFiles') || n('skippedFiles') || n('unsupportedFiles') || n('unclassified') ||
         n('diagnosticErrors') || n('diagnosticWarnings'))) ||
      (snapshot.status === 'COMPLETED WITH WARNINGS' &&
        (n('failedFiles') || n('skippedFiles') || n('unsupportedFiles') || n('diagnosticErrors')))) {
    throw new TypeError('Project Brain snapshot metrics are inconsistent');
  }
  const framework = snapshot.primaryFramework as Record<string, unknown>;
  const language = snapshot.primaryLanguage as Record<string, unknown>;
  if (typeof framework.name !== 'string' || typeof language.name !== 'string') {
    throw new TypeError('Project Brain snapshot has no language/framework');
  }
  return snapshot as unknown as ProjectBrainSnapshotV2;
}
function validReply(reply: Record<string, unknown>, request: QueryRequest, version: number) {
  if (reply.status !== 'OK' || reply.kind !== request.kind ||
      reply.requestId !== request.requestId || reply.modelVersion !== version) {
    throw new Error('IntelliJ response does not match the query or current model version');
  }
  if (['components', 'component-options', 'files', 'file-options', 'frameworks'].includes(request.kind)) {
    if (!Array.isArray(reply.items) || !Number.isSafeInteger(reply.total) || (reply.total as number) < 0 ||
        reply.items.length > 100 ||
        (['component-options', 'file-options'].includes(request.kind) &&
          !reply.items.every((item) => typeof item === 'string'))) {
      throw new TypeError('Invalid bounded project query page');
    }
  } else if (request.kind === 'diagram') {
    const nodes = reply.nodes as unknown;
    const edges = reply.edges as unknown;
    if (!Array.isArray(nodes) || !Array.isArray(edges) || nodes.length > 80 || edges.length > 240 ||
        !nodes.every((n) => n && typeof n.id === 'string' && typeof n.name === 'string' && typeof n.type === 'string') ||
        !edges.every((e) => e && typeof e.source === 'string' && typeof e.target === 'string' && typeof e.type === 'string') ||
        !Number.isSafeInteger(reply.totalNodes) || !Number.isSafeInteger(reply.totalEdges) ||
        (reply.totalNodes as number) < nodes.length || (reply.totalEdges as number) < edges.length) {
      throw new TypeError('Invalid bounded architecture graph');
    }
  } else if (reply.item !== null && (!reply.item || typeof reply.item !== 'object' || Array.isArray(reply.item))) {
    throw new TypeError('Invalid project detail response');
  }
}

export function installProjectBrainAdapter(): ProjectBrainAdapter {
  if (adapterInstance) return adapterInstance;
  let snapshot: ProjectBrainSnapshotV2 | null = null;
  const subscribers = new Set<() => void>();
  const emit = (type: string, detail: unknown) => window.dispatchEvent(new CustomEvent(type, { detail }));
  const bridge = () => window.DevQAIntellijBridge;

  const adapter: ProjectBrainAdapter = {
    get mode() { return bridge() ? 'intellij-bridge' : 'standalone-preview'; },
    latest: () => snapshot,
    subscribe(listener) { subscribers.add(listener); return () => { subscribers.delete(listener); }; },
    ready: () => bridge()?.frontendReady() ?? Promise.resolve(),
    async analyze() {
      if (!bridge()) throw new Error('Analysis requires IntelliJ');
      const id = 'analysis-' + ++requestId;
      const ack = object(await bridge()!.analyzeProject(id), 'analysis acknowledgment');
      if (ack.type !== 'ack' || ack.accepted !== true) throw new Error('IntelliJ did not accept analysis');
      return id;
    },
    async cancel() {
      if (bridge()) await bridge()!.cancelAnalysis();
    },
    async query<T>(payload: QueryRequest): Promise<T> {
      if (!bridge()) throw new Error('Project queries require IntelliJ');
      if (!snapshot) throw new Error('No project analysis yet');
      const version = snapshot.modelVersion;
      const request: QueryRequest = { ...payload, requestId: 'query-' + ++requestId };
      const reply = object(await bridge()!.query(request), 'project query');
      if (snapshot?.modelVersion !== version) throw new Error('Project model changed; apply filters again');
      if (reply.status === 'NO_MODEL') throw new Error('Run Analyze to build a project model');
      if (reply.error) throw new Error(String(reply.error));
      validReply(reply, request, version);
      return reply as unknown as T;
    },
    async readSettings<T>(): Promise<T | null> {
      if (!bridge()) return null;
      return object(await bridge()!.readSettings(), 'scan settings') as T;
    },
    async writeSettings(options) {
      if (!bridge()) throw new Error('Scan settings require IntelliJ');
      const ack = object(await bridge()!.writeSettings(options), 'settings acknowledgment');
      if (ack.accepted !== true) throw new Error('Settings were rejected by IntelliJ');
    },
    async openSource(path, offset = 0) {
      if (!bridge()) throw new Error('Source navigation requires IntelliJ');
      if (!path?.trim() || path.length > 4096 || /[\r\n\u0000]/.test(path) ||
          !Number.isSafeInteger(offset) || offset < 0) throw new TypeError('Invalid source path/offset');
      const ack = object(await bridge()!.openSource(path, offset), 'source navigation');
      if (ack.type !== 'ack' || ack.accepted !== true) throw new Error('IntelliJ rejected source navigation');
      return ack;
    }
  };

  // Kotlin injects these names before loading this compiled React bundle.
  // Retain the last valid model and isolate malformed native notifications.
  window.receiveDevQAProjectBrainSnapshot = (raw) => {
    try {
      const next = validSnapshot(raw);
      if (snapshot && next.modelVersion <= snapshot.modelVersion) return;
      snapshot = Object.freeze(next);
      subscribers.forEach((notify) => notify());
      emit('devqa:project-brain-updated', snapshot);
    } catch (error) {
      emit('devqa:project-brain-error', error instanceof Error ? error.message : 'Invalid snapshot');
    }
  };
  window.receiveDevQAProjectBrainError = (message) => emit('devqa:project-brain-error', String(message || 'Analysis failed'));
  window.receiveDevQAProjectBrainProgress = (event) => emit('devqa:project-brain-progress', event);
  window.receiveDevQASourceNavigationError = (message) =>
    emit('devqa:project-brain-source-error', String(message || 'Unable to open source file'));
  window.DEVQA_BACKEND_ADAPTERS = { ...window.DEVQA_BACKEND_ADAPTERS, '01': adapter };
  window.DevQAProjectBrain = adapter;
  adapterInstance = adapter;
  return adapter;
}
export function getProjectBrainAdapter(): ProjectBrainAdapter | null {
  return adapterInstance;
}
export function getIntelliJHostBridge(): IntelliJHostBridge | null {
  return window.DevQAIntellijBridge ?? null;
}
