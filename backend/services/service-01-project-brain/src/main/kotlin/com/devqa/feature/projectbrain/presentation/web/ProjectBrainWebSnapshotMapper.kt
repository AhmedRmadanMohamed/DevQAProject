package com.devqa.feature.projectbrain.presentation.web

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.DiagnosticSeverity
import com.devqa.core.model.type.ComponentType
import com.devqa.feature.projectbrain.application.ProjectBrainQueryService
import com.google.gson.Gson

/** Small, versioned summary. Large collections are delivered through bounded backend queries. */
object ProjectBrainWebSnapshotMapper {
    private val gson = Gson()

    fun toJson(projectName: String, result: ProjectAnalysisResult): String {
        val scan = result.scanResult
        val classes = scan.allClasses
        val classified = classes.count { it.primaryType != ComponentType.OTHER && it.classification.confidence.name != "NONE" }
        val unresolved = classes.size - classified
        val roleCounts = classes.groupingBy { it.primaryType.name }.eachCount().toSortedMap()
        val classesPerModule = classes.groupingBy { it.moduleName ?: "Project" }.eachCount()
        // Expose small, exact per-module classification totals in the versioned summary.
        // The UI can add/subtract selected services instantly without reading whole class lists.
        val rolesPerModule = HashMap<String, MutableMap<String, Int>>()
        classes.forEach { cls ->
            val module = cls.moduleName ?: "Project"
            val roles = rolesPerModule.getOrPut(module) { linkedMapOf() }
            roles[cls.primaryType.name] = (roles[cls.primaryType.name] ?: 0) + 1
        }
        val modules = (classesPerModule.keys + result.files.map { it.moduleName ?: "Project" })
            .distinct().sorted().map { name ->
                mapOf(
                    "name" to name,
                    "classes" to (classesPerModule[name] ?: 0),
                    "roleCounts" to (rolesPerModule[name]?.toSortedMap() ?: emptyMap<String, Int>())
                )
            }
        val unsupportedFiles = result.files.count { it.intelligence.capability.name == "UNSUPPORTED" }
        // Class/relationship diagnostics may refer to a file whose metadata was successfully
        // indexed. Only metadata scan failures belong in the disjoint file accounting below.
        val failedFilePaths = scan.diagnostics.asSequence()
            .filter { it.severity == DiagnosticSeverity.ERROR &&
                it.message.startsWith("File metadata analysis failed:") && it.path != null }
            .mapNotNull { it.path }.distinct().toSet()
        val failedFiles = maxOf(scan.metrics.failedFiles, failedFilePaths.size)
        val skippedFiles = scan.metrics.skippedFiles
        val analyzedFiles = result.files.size - unsupportedFiles
        val discoveredFiles = result.files.size + failedFiles + skippedFiles
        val diagnosticErrors = scan.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val diagnosticWarnings = scan.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }
        val status = when {
            failedFiles > 0 || skippedFiles > 0 || unsupportedFiles > 0 || diagnosticErrors > 0 -> "PARTIALLY COMPLETED"
            diagnosticWarnings > 0 || unresolved > 0 -> "COMPLETED WITH WARNINGS"
            else -> "ANALYSIS COMPLETE"
        }
        return gson.toJson(linkedMapOf<String, Any>(
            "schemaVersion" to 2,
            "projectName" to projectName,
            "status" to status,
            "modelVersion" to scan.modelVersion,
            "primaryFramework" to mapOf(
                "name" to (scan.frameworkInventory.primary?.name ?: "Unknown"),
                "confidence" to (scan.frameworkInventory.primary?.confidence?.name ?: "NONE")
            ),
            "primaryLanguage" to mapOf("name" to result.primaryLanguage.name, "fileCount" to result.primaryLanguage.fileCount),
            "architecture" to scan.architectureProfile.style.name,
            "architectureProfile" to mapOf(
                "style" to scan.architectureProfile.style.name,
                "confidence" to scan.architectureProfile.confidence.name,
                "evidence" to scan.architectureProfile.evidence,
                "crossModuleRelationships" to scan.architectureProfile.crossModuleRelationships,
                "moduleCount" to scan.architectureProfile.modules.size,
                "modules" to scan.architectureProfile.modules.take(30).map { module ->
                    mapOf("name" to module.name, "classes" to module.classes,
                        "layers" to module.layerCounts.mapKeys { it.key.name },
                        "incoming" to module.incomingRelationships,
                        "outgoing" to module.outgoingRelationships)
                }
            ),
            "frameworks" to scan.frameworkInventory.frameworks.map { framework ->
                mapOf("name" to framework.name, "confidence" to framework.confidence.name,
                    "version" to (framework.version ?: "Unknown"), "evidence" to framework.evidence.take(8))
            },
            "modules" to modules,
            "roleCounts" to roleCounts,
            "facets" to ProjectBrainQueryService.facets(result),
            "facetsLimited" to (
                classes.asSequence().map { it.name }.distinct().take(201).count() > 200 ||
                    result.files.asSequence().map { it.name }.distinct().take(201).count() > 200
                ),
            "metrics" to mapOf(
                "projectFiles" to result.files.size,
                "discoveredFiles" to discoveredFiles,
                "classes" to classes.size,
                "components" to classified,
                "unclassified" to unresolved,
                "classificationCoverage" to if (classes.isEmpty()) 0 else classified * 100 / classes.size,
                "relationships" to scan.relationships.size,
                "diagnostics" to scan.diagnostics.size,
                "diagnosticErrors" to diagnosticErrors,
                "diagnosticWarnings" to diagnosticWarnings,
                "durationMillis" to scan.metrics.durationMillis,
                "analyzedFiles" to analyzedFiles,
                "skippedFiles" to skippedFiles,
                "unsupportedFiles" to unsupportedFiles,
                "failedFiles" to failedFiles
            ),
            "diagnostics" to scan.diagnostics.take(15).map { it.message },
            // These are intentionally empty; no hidden 5,000/20,000 item truncation.
            "components" to emptyList<Any>(),
            "files" to emptyList<Any>(),
            "relationships" to emptyList<Any>()
        ))
    }
}
