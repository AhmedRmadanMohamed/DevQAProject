package com.devqa.core.model

import com.devqa.core.model.type.ComponentType

data class ProjectScanResult(
    val modelVersion: Long,
    val springBoot: Boolean,
    val javaFiles: Int,
    val javaClasses: Int,
    val kotlinFiles: Int,
    val kotlinClasses: Int,
    val allClasses: List<ProjectClass>,
    val relationships: List<ProjectRelationship>,
    val architectureProfile: ArchitectureProfile,
    val frameworkInventory: FrameworkInventory,
    val diagnostics: List<ProjectScanDiagnostic>,
    val metrics: ProjectScanMetrics,
    /** Indexed source files include empty files which cannot be inferred from discovered classes. */
    val sourceFilePaths: Map<String, ProjectLanguage> = emptyMap()
) {
    val totalClasses: Int
        get() = allClasses.size

    val controllers: List<String>
        get() = namesOf(ComponentType.CONTROLLER)

    val services: List<String>
        get() = namesOf(ComponentType.SERVICE)

    val repositories: List<String>
        get() = namesOf(ComponentType.REPOSITORY)

    val entities: List<String>
        get() = namesOf(ComponentType.ENTITY)

    val dtos: List<String>
        get() = namesOf(ComponentType.DTO)

    fun classesOf(type: ComponentType): List<ProjectClass> =
        allClasses.filter { it.primaryType == type }

    private fun namesOf(type: ComponentType): List<String> =
        classesOf(type).map { it.name }
}
