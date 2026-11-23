package com.devqa.core.analyzer

import com.devqa.core.model.ArchitectureProfile
import com.devqa.core.model.ArchitectureStyle
import com.devqa.core.model.DetectedFramework
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.ModuleArchitectureProfile
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectRelationship
import com.devqa.core.model.RelationshipType
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.type.ComponentType

object ProjectModelAssembler {

    fun relationships(classes: List<ProjectClass>): List<ProjectRelationship> {
        // Identical qualified names can exist in test and production modules. Resolving
        // by qualified name alone silently connects unrelated classes across modules.
        val byName = classes.filter { it.qualifiedName != null }.groupBy { it.qualifiedName!! }
        val uniqueEdges = linkedMapOf<EdgeIdentity, ProjectRelationship>()
        // Allocate evidence sets only for edges observed more than once.
        val repeatedEvidence = hashMapOf<EdgeIdentity, MutableSet<String>>()

        fun add(edge: ProjectRelationship) {
            val key = EdgeIdentity(edge.sourceClassId, edge.targetClassId ?: edge.targetQualifiedName, edge.type)
            val existing = uniqueEdges[key]
            if (existing == null) uniqueEdges[key] = edge
            else if (repeatedEvidence.getOrPut(key) { hashSetOf(existing.evidence) }.add(edge.evidence)) {
                uniqueEdges[key] = existing.copy(occurrences = existing.occurrences + 1,
                    confidence = maxOf(existing.confidence, edge.confidence))
            }
        }

        for (source in classes) {
            val sourceName = source.qualifiedName ?: source.name
            for (target in source.superTypes) {
                val resolved = resolveTarget(source, byName[target].orEmpty(), source.resolvedTypePaths[target])
                add(ProjectRelationship(source.id, sourceName, target, RelationshipType.EXTENDS,
                    source.sourcePath, "extends:$target", targetClassId = resolved?.id))
            }
            for (target in source.interfaces) {
                val resolved = resolveTarget(source, byName[target].orEmpty(), source.resolvedTypePaths[target])
                add(ProjectRelationship(source.id, sourceName, target, RelationshipType.IMPLEMENTS,
                    source.sourcePath, "implements:$target", targetClassId = resolved?.id))
            }
            for (dependency in source.dependencies) {
                // Calls and injections outside the project are not represented as project
                // edges. An ambiguous target is intentionally omitted, not guessed.
                val resolved = resolveTarget(source, byName[dependency.targetQualifiedName].orEmpty(),
                    dependency.targetSourcePath) ?: continue
                if (resolved.id == source.id) continue
                add(ProjectRelationship(source.id, sourceName, dependency.targetQualifiedName,
                    dependency.type, source.sourcePath, dependency.evidence, dependency.confidence,
                    targetClassId = resolved.id))
            }
        }
        return uniqueEdges.values.sortedWith(compareBy<ProjectRelationship> { it.sourceQualifiedName }
            .thenBy { it.sourceClassId }.thenBy { it.type.name }
            .thenBy { it.targetQualifiedName }.thenBy { it.targetClassId })
    }

    private data class EdgeIdentity(val sourceId: String, val target: String, val type: RelationshipType)

    private fun resolveTarget(
        source: ProjectClass, candidates: List<ProjectClass>, resolvedPath: String?
    ): ProjectClass? {
        // PSI's resolved declaration is authoritative. If it is outside the scanned
        // project, do not silently connect a same-named type in a different module.
        if (resolvedPath != null) return candidates.singleOrNull { it.sourcePath == resolvedPath }
        if (candidates.size == 1) return candidates.single()
        if (candidates.isEmpty()) return null
        val sameModule = candidates.filter { it.moduleName == source.moduleName }
        if (sameModule.size == 1) return sameModule.single()
        val sameOrigin = sameModule.filter { it.origin == source.origin && it.origin != ProjectClassOrigin.UNKNOWN }
        return sameOrigin.singleOrNull()
    }

    fun architecture(classes: List<ProjectClass>, relationships: List<ProjectRelationship> = emptyList()): ArchitectureProfile {
        val actualCounts = classes.groupingBy { it.primaryType }.eachCount()
        val counts = ComponentType.entries.associateWith { actualCounts[it] ?: 0 }
        val hasController = counts.getValue(ComponentType.CONTROLLER) > 0
        val hasService = counts.getValue(ComponentType.SERVICE) > 0
        val hasRepository = counts.getValue(ComponentType.REPOSITORY) > 0
        val layerCount = listOf(hasController, hasService, hasRepository).count { it }

        val byId = classes.associateBy(ProjectClass::id)
        val internalEdges = relationships.filter { edge ->
            edge.targetClassId?.let(byId::containsKey) == true
        }
        val controllerToService = internalEdges.count { edge ->
            byId[edge.sourceClassId]?.primaryType == ComponentType.CONTROLLER &&
                edge.targetClassId?.let(byId::get)?.primaryType == ComponentType.SERVICE
        }
        val serviceToRepository = internalEdges.count { edge ->
            byId[edge.sourceClassId]?.primaryType == ComponentType.SERVICE &&
                edge.targetClassId?.let(byId::get)?.primaryType == ComponentType.REPOSITORY
        }

        fun ProjectClass.hasPackageSegment(vararg segments: String): Boolean =
            packageName.lowercase().split('.').any { it in segments }

        val ports = classes.filter { it.hasPackageSegment("port", "ports") }.mapTo(hashSetOf(), ProjectClass::id)
        val adapters = classes.filter { it.hasPackageSegment("adapter", "adapters") }.mapTo(hashSetOf(), ProjectClass::id)
        val domainCount = classes.count { it.hasPackageSegment("domain") }
        val adapterPortEdges = internalEdges.count { edge ->
            edge.sourceClassId in adapters && edge.targetClassId?.let(ports::contains) == true
        }
        val hexagonal = ports.isNotEmpty() && adapters.isNotEmpty() && domainCount > 0 && adapterPortEdges > 0

        val style = when {
            hexagonal -> ArchitectureStyle.HEXAGONAL
            layerCount == 3 && controllerToService > 0 && serviceToRepository > 0 -> ArchitectureStyle.LAYERED
            layerCount > 0 -> ArchitectureStyle.PARTIAL_LAYERED
            else -> ArchitectureStyle.UNKNOWN
        }
        val confidence = when (style) {
            ArchitectureStyle.HEXAGONAL -> ClassificationConfidence.HIGH
            ArchitectureStyle.LAYERED -> ClassificationConfidence.HIGH
            ArchitectureStyle.PARTIAL_LAYERED -> if (layerCount > 1) ClassificationConfidence.MEDIUM else ClassificationConfidence.LOW
            ArchitectureStyle.UNKNOWN -> ClassificationConfidence.NONE
        }
        val evidence = buildList {
            if (hasController) add("controller-layer:${counts.getValue(ComponentType.CONTROLLER)}")
            if (hasService) add("service-layer:${counts.getValue(ComponentType.SERVICE)}")
            if (hasRepository) add("repository-layer:${counts.getValue(ComponentType.REPOSITORY)}")
            if (controllerToService > 0) add("controller-to-service:$controllerToService")
            if (serviceToRepository > 0) add("service-to-repository:$serviceToRepository")
            if (hexagonal) add("adapter-to-port:$adapterPortEdges;domain-types:$domainCount")
        }

        val modules = classes.groupBy { it.moduleName ?: "Project" }
        val incoming = hashMapOf<String, Int>()
        val outgoing = hashMapOf<String, Int>()
        var crossModule = 0
        internalEdges.forEach { edge ->
            val from = byId[edge.sourceClassId]?.moduleName ?: "Project"
            val to = edge.targetClassId?.let(byId::get)?.moduleName ?: "Project"
            if (from != to) {
                crossModule++
                outgoing[from] = (outgoing[from] ?: 0) + 1
                incoming[to] = (incoming[to] ?: 0) + 1
            }
        }
        val moduleProfiles = modules.map { (name, members) ->
            ModuleArchitectureProfile(name, members.size, members.groupingBy { it.primaryType }.eachCount(),
                incoming[name] ?: 0, outgoing[name] ?: 0)
        }.sortedBy { it.name }

        return ArchitectureProfile(style, confidence, counts, evidence, moduleProfiles, crossModule)
    }

    fun frameworks(classes: List<ProjectClass>, springBoot: Boolean): FrameworkInventory {
        val evidenceSymbols = classes.flatMap { projectClass ->
            projectClass.classification.candidates.flatMap { candidate ->
                candidate.evidence.map { it.matchedSymbol }
            }
        }
        val frameworks = buildList {
            if (springBoot) {
                add(
                    DetectedFramework(
                        name = "Spring Boot",
                        confidence = ClassificationConfidence.HIGH,
                        evidence = listOf("org.springframework.boot.autoconfigure.SpringBootApplication")
                    )
                )
            }
            addFrameworkIfPresent("Spring Web", "org.springframework.web", evidenceSymbols)
            addFrameworkIfPresent("Spring Data", "org.springframework.data", evidenceSymbols)
            addFrameworkIfPresent("Spring Security", "org.springframework.security", evidenceSymbols)
            addFrameworkIfPresent("Jakarta Persistence", "jakarta.persistence", evidenceSymbols)
            if (classes.any { it.language == ProjectLanguage.KOTLIN }) {
                add(
                    DetectedFramework(
                        name = "Kotlin",
                        confidence = ClassificationConfidence.HIGH,
                        evidence = listOf("kotlin-source")
                    )
                )
            }
        }
        val sortedFrameworks = frameworks.sortedBy { it.name }
        return FrameworkInventory(
            primary = sortedFrameworks.firstOrNull { it.name == "Spring Boot" },
            frameworks = sortedFrameworks
        )
    }

    private fun MutableList<DetectedFramework>.addFrameworkIfPresent(
        name: String,
        symbolPrefix: String,
        evidenceSymbols: List<String>
    ) {
        val evidence = evidenceSymbols.filter { symbolPrefix in it }.distinct().sorted()
        if (evidence.isNotEmpty()) {
            add(DetectedFramework(name, ClassificationConfidence.HIGH, evidence))
        }
    }
}
