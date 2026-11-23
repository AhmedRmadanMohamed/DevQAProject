/**
 * Kotlin-published Project Brain v2 snapshot contract.
 * Large class/file collections are intentionally omitted from snapshots and
 * retrieved from bounded, versioned backend queries.
 */
export type ProjectBrainStatus =
  | 'ANALYSIS COMPLETE' | 'COMPLETED WITH WARNINGS' | 'PARTIALLY COMPLETED';
export type ProjectBrainQueryKind =
  | 'components' | 'component-options' | 'component-detail'
  | 'files' | 'file-options' | 'file-detail'
  | 'frameworks' | 'framework-detail' | 'diagram';

export type ComponentFacet = 'type' | 'role' | 'class' | 'module' | 'language' | 'confidence';

export interface ProjectBrainComponent {
  readonly id: string;
  readonly name: string;
  readonly qualifiedName?: string;
  readonly packageName?: string;
  readonly type: string;
  readonly module?: string;
  readonly language?: string;
  readonly confidence?: string;
  readonly path?: string;
  readonly sourceOffset?: number;
  readonly annotations?: readonly string[];
  readonly superTypes?: readonly string[];
  readonly interfaces?: readonly string[];
  readonly origin?: string;
}

export interface ProjectBrainComponentPage {
  readonly modelVersion: number;
  readonly kind: string;
  readonly status: 'OK';
  readonly total: number;
  readonly items: readonly ProjectBrainComponent[];
}
export interface ProjectBrainComponentDetail {
  readonly modelVersion: number;
  readonly kind: string;
  readonly status: 'OK';
  readonly item: ProjectBrainComponent | null;
}
export interface ProjectBrainOptionPage {
  readonly modelVersion: number;
  readonly kind: string;
  readonly status: 'OK';
  readonly total: number;
  readonly items: readonly string[];
}
export interface ProjectBrainModule {
  readonly name: string;
  readonly roleCounts?: Readonly<Record<string, number>>;
}
export interface ProjectBrainFramework {
  readonly name: string;
  readonly version?: string;
  readonly confidence?: string;
}
export interface ProjectArchitectureModule {
  readonly name: string;
  readonly classes?: number;
  readonly incoming?: number;
  readonly outgoing?: number;
  readonly layers?: Readonly<Record<string, number>>;
}
export interface ProjectArchitectureProfile {
  readonly style?: string;
  readonly confidence?: string;
  readonly crossModuleRelationships?: number;
  readonly moduleCount?: number;
  readonly evidence?: readonly string[];
  readonly modules?: readonly ProjectArchitectureModule[];
}
export interface ProjectBrainSnapshotV2 {
  readonly schemaVersion: 2;
  readonly projectName: string;
  readonly modelVersion: number;
  readonly status: ProjectBrainStatus;
  readonly primaryFramework: Readonly<{ name: string; confidence?: string }>;
  readonly primaryLanguage: Readonly<{ name: string; fileCount?: number }>;
  readonly architecture?: string;
  readonly architectureProfile?: ProjectArchitectureProfile;
  readonly metrics: Readonly<Record<string, number>>;
  readonly modules: readonly ProjectBrainModule[];
  readonly frameworks: readonly ProjectBrainFramework[];
  readonly roleCounts: Readonly<Record<string, number>>;
  readonly facets?: Readonly<{
    components?: Partial<Readonly<Record<ComponentFacet, readonly string[]>>>;
    files?: Partial<Readonly<Record<'filename' | 'extension' | 'language' | 'module' | 'category' | 'contentKind', readonly string[]>>>;
    frameworks?: Partial<Readonly<Record<'module' | 'confidence' | 'category', readonly string[]>>>;
    diagram?: Partial<Readonly<Record<'type' | 'module' | 'confidence' | 'relationship', readonly string[]>>>;
  }>;
  readonly diagnostics?: readonly string[];
}

/** Actual Kotlin ProjectBrainQueryService reply DTOs. No file content is transmitted. */
export interface ProjectFileSummary {
  readonly path: string; readonly name: string; readonly relativePath: string;
  readonly extension?: string; readonly category: string; readonly module?: string;
  readonly language?: string; readonly contentType?: string; readonly contentKind?: string;
  readonly sourceOffset?: number;
}
export interface ProjectFileDetail extends ProjectFileSummary {
  readonly fileType?: string; readonly mimeType?: string; readonly capability?: string;
  readonly sizeBytes?: number; readonly status?: string;
  readonly classes?: readonly string[]; readonly classesTotal?: number;
  readonly dependencies?: readonly string[]; readonly dependenciesTotal?: number;
}
export interface DependencyEvidence {
  readonly coordinate: string; readonly origin: string; readonly version?: string; readonly path: string;
}
export interface ProjectFrameworkSummary {
  readonly name: string; readonly category?: string; readonly confidence: string;
  readonly version?: string; readonly versionStatus?: string;
  readonly modules?: readonly string[]; readonly evidence?: readonly string[];
}
export interface ProjectFrameworkDetail extends ProjectFrameworkSummary {
  readonly declaredVersions?: readonly { version: string; path: string; dependency?: string }[];
  readonly directDependencies?: readonly DependencyEvidence[];
  readonly transitiveDependencies?: readonly DependencyEvidence[];
  readonly otherDeclarations?: readonly DependencyEvidence[];
  readonly relatedClasses?: readonly string[];
  readonly classSources?: readonly { name: string; path: string; offset?: number }[];
  readonly annotations?: readonly string[]; readonly configurationFiles?: readonly string[];
  readonly dependencyEvidenceTruncated?: boolean;
}
export interface ProjectGraphNode {
  readonly id: string; readonly name: string; readonly type: string;
  readonly count?: number; readonly module?: string; readonly path?: string;
  readonly sourceOffset?: number; readonly qualifiedName?: string;
  readonly incoming?: number; readonly outgoing?: number;
}
export interface ProjectGraphEdge {
  readonly source: string; readonly target: string; readonly type: string;
  readonly occurrences?: number;
}
export interface ProjectGraphReply {
  readonly kind: 'diagram'; readonly status: 'OK'; readonly modelVersion: number;
  readonly view: string; readonly nodes: readonly ProjectGraphNode[];
  readonly edges: readonly ProjectGraphEdge[]; readonly totalNodes: number;
  readonly totalEdges: number; readonly truncated: boolean; readonly notice?: string;
}
export interface QueryPage<T> {
  readonly kind: string; readonly status: 'OK'; readonly modelVersion: number;
  readonly total: number; readonly items: readonly T[];
}
export interface QueryDetail<T> {
  readonly kind: string; readonly status: 'OK'; readonly modelVersion: number; readonly item: T | null;
}
export interface ScanSettings {
  includeTests: boolean; includeGenerated: boolean; autoRefresh: boolean; maxFileBytes: number;
  includedModules: string[]; excludedDirectories: string[];
}
