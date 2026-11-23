package com.devqa.core.model

import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.type.ComponentType

data class ArchitectureProfile(
    val style: ArchitectureStyle,
    val confidence: ClassificationConfidence,
    val componentCounts: Map<ComponentType, Int>,
    val evidence: List<String>,
    val modules: List<ModuleArchitectureProfile> = emptyList(),
    val crossModuleRelationships: Int = 0
)

data class ModuleArchitectureProfile(
    val name: String,
    val classes: Int,
    val layerCounts: Map<ComponentType, Int>,
    val incomingRelationships: Int,
    val outgoingRelationships: Int
)

enum class ArchitectureStyle {
    HEXAGONAL,
    LAYERED,
    PARTIAL_LAYERED,
    UNKNOWN
}

data class FrameworkInventory(
    val primary: DetectedFramework?,
    val frameworks: List<DetectedFramework>
)

data class DetectedFramework(
    val name: String,
    val confidence: ClassificationConfidence,
    val evidence: List<String>,
    /** Exactly one literal, explicitly declared version; null if absent, dynamic or conflicting. */
    val version: String? = null,
    /** Distinct declared versions and their manifest paths; a multi-version project is not flattened. */
    val declaredVersions: List<FrameworkVersion> = emptyList(),
    val versionEvidenceTruncated: Boolean = false,
    /** Verified manifest declarations, not a resolved build dependency tree. */
    val dependencyDeclarations: List<FrameworkDependencyDeclaration> = emptyList(),
    val dependencyEvidenceTruncated: Boolean = false
)

data class FrameworkVersion(val value: String, val path: String, val coordinate: String = "")

enum class FrameworkDependencyOrigin { DIRECT, TRANSITIVE, MANAGED, PLUGIN, PARENT, DECLARED }

data class FrameworkDependencyDeclaration(
    val coordinate: String,
    val path: String,
    val origin: FrameworkDependencyOrigin,
    val version: String? = null
)

data class ProjectScanDiagnostic(
    val path: String?,
    val message: String,
    val severity: DiagnosticSeverity
)

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

data class ProjectScanMetrics(
    val durationMillis: Long,
    val filesScanned: Int,
    val classesDiscovered: Int,
    val relationshipsDiscovered: Int,
    val classesPerSecond: Long,
    val estimatedModelBytes: Long,
    val skippedFiles: Int = 0,
    val failedFiles: Int = 0
)
