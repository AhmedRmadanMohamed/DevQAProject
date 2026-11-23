package com.devqa.core.model

import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType

data class ProjectClass(
    val id: String,
    val name: String,
    val qualifiedName: String?,
    val moduleName: String?,
    val packageName: String,
    val sourcePath: String,
    val sourceOffset: Int,
    val language: ProjectLanguage,
    val origin: ProjectClassOrigin,
    val modifiers: Set<String>,
    val annotations: Set<String>,
    val superTypes: Set<String>,
    val interfaces: Set<String>,
    val dependencies: List<ClassDependency>,
    val classification: ComponentClassification,
    /** PSI-resolved source paths for extends/implements; disambiguates same-named module types. */
    val resolvedTypePaths: Map<String, String> = emptyMap()
) {
    val primaryType: ComponentType
        get() = classification.primaryType

    val types: Set<ComponentType>
        get() = classification.candidates.mapTo(linkedSetOf()) { it.type }
}

data class ClassDependency(
    val targetQualifiedName: String,
    val type: RelationshipType,
    val evidence: String,
    val confidence: RelationshipConfidence,
    /** Exact declaration file from PSI/UAST, when it is available. */
    val targetSourcePath: String? = null
)

enum class ProjectLanguage {
    JAVA,
    KOTLIN
}

enum class ProjectClassOrigin {
    MAIN,
    TEST,
    GENERATED,
    UNKNOWN
}
