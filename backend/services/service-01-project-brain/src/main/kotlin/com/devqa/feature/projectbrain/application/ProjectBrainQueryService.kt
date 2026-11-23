package com.devqa.feature.projectbrain.application

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectRelationship
import com.devqa.core.model.DetectedFramework
import com.devqa.core.model.FrameworkDependencyOrigin
import com.devqa.core.model.type.ProjectFileType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.Locale
import java.util.PriorityQueue
import java.util.TreeSet

/** Read-only, bounded access to the published Project Brain model. Never exposes PSI or file contents. */
object ProjectBrainQueryService {
    private val gson = GsonBuilder().serializeNulls().create()
    private const val MAX_PAGE = 100
    private const val MAX_GRAPH_NODES = 80
    private const val MAX_GRAPH_EDGES = 240
    private const val MAX_FACET_VALUES = 200
    private const val MAX_REQUEST_LENGTH = 32_768
    private const val MAX_FILTER_CHOICES = 256
    private const val MAX_FILTER_TEXT_LENGTH = 256
    private const val MAX_ADVANCED_RULES = 12
    private val ADVANCED_FIELDS = setOf("name", "qualifiedName", "package", "module",
        "role", "type", "language", "confidence", "incoming", "outgoing")
    private val ADVANCED_OPERATORS = setOf("EQ", "NE", "LIKE", "NOT_LIKE", "IN", "NOT_IN",
        "GT", "GE", "LT", "LE", "BETWEEN")
    private data class AdvancedRule(
        val field: String, val operator: String, val value: String,
        val join: String, val negate: Boolean
    )
    private data class ClassSearchTerm(
        val negative: Boolean,
        val like: Boolean,
        val needle: String
    )
    private val SUPPORTED_QUERY_KINDS = setOf(
        "components", "component-options", "files", "file-options", "frameworks",
        "framework-detail", "component-detail", "file-detail", "diagram"
    )
    private val SUPPORTED_GRAPH_VIEWS = setOf("modules", "layers", "expanded", "dependency", "impact")

    fun query(result: ProjectAnalysisResult?, requestJson: String): String {
        require(requestJson.isNotBlank()) { "Project Brain query is empty" }
        require(requestJson.length <= MAX_REQUEST_LENGTH) { "Project Brain query exceeds size limit" }
        val parsedRequest = JsonParser.parseString(requestJson)
        require(parsedRequest.isJsonObject) { "Project Brain query must be a JSON object" }
        val request = parsedRequest.asJsonObject

        val kindElement = request.get("kind")
        require(kindElement == null || kindElement.isJsonNull ||
            (kindElement.isJsonPrimitive && kindElement.asJsonPrimitive.isString)) {
            "Invalid Project Brain query kind"
        }
        val queryKind = request.string("kind").ifBlank { "components" }
        require(queryKind in SUPPORTED_QUERY_KINDS) { "Unsupported Project Brain query kind" }

        val requestIdElement = request.get("requestId")
        require(requestIdElement == null || requestIdElement.isJsonNull ||
            (requestIdElement.isJsonPrimitive && requestIdElement.asJsonPrimitive.isString)) {
            "Invalid Project Brain request identifier"
        }
        val correlationId = request.string("requestId").take(100)

        val filtersElement = request.get("filters")
        require(filtersElement == null || filtersElement.isJsonNull || filtersElement.isJsonObject) {
            "Project Brain filters must be an object"
        }
        val filters = filtersElement?.takeIf { it.isJsonObject }?.asJsonObject
        filters?.entrySet()?.forEach { (key, value) ->
            require(key.length <= 64 && value.isJsonArray && value.asJsonArray.size() <= MAX_FILTER_CHOICES &&
                value.asJsonArray.all { choice ->
                    choice.isJsonPrimitive && choice.asJsonPrimitive.isString && choice.asString.length <= MAX_FILTER_TEXT_LENGTH
                }) { "Invalid or oversized Project Brain filter" }
        }

        if (queryKind == "diagram") {
            val viewElement = request.get("view")
            require(viewElement == null || viewElement.isJsonNull ||
                (viewElement.isJsonPrimitive && viewElement.asJsonPrimitive.isString)) {
                "Invalid Project Brain graph view"
            }
            val graphView = request.string("view").ifBlank { "layers" }
            require(graphView in SUPPORTED_GRAPH_VIEWS) { "Unsupported Project Brain graph view" }
        }

        if (result == null) {
            return gson.toJson(mapOf(
                "kind" to queryKind,
                "requestId" to correlationId,
                "status" to "NO_MODEL",
                "items" to emptyList<Any>(),
                "total" to 0
            ))
        }
        val search = request.string("search").trim().take(200).lowercase(Locale.ROOT)
        val offset = request.int("offset").coerceAtLeast(0).coerceAtMost(1_000_000)
        val limit = request.int("limit").takeIf { it > 0 }?.coerceAtMost(MAX_PAGE) ?: 40
        val scan = result.scanResult
        val meta = mapOf("kind" to queryKind, "requestId" to correlationId, "modelVersion" to scan.modelVersion, "status" to "OK")

        return when (queryKind) {
            "components" -> {
                val advanced = advancedRules(request.get("advanced"))
                val classSearch = parseClassSearch(search)
                val sortDirection = request.string("sortNameDirection").ifBlank { "ASC" }.uppercase(Locale.ROOT)
                require(sortDirection in setOf("ASC", "DESC")) { "Unsupported component sort direction" }
                // Numeric evidence is indexed once per query only when relevant to the rule builder.
                val needsRelations = advanced.any { it.field in setOf("incoming", "outgoing") }
                val inbound = if (needsRelations) scan.relationships.filter { it.targetClassId != null }
                    .groupingBy { it.targetClassId!! }.eachCount() else emptyMap()
                val outbound = if (needsRelations) scan.relationships.groupingBy { it.sourceClassId }.eachCount()
                    else emptyMap()
                val comparator = Comparator<ProjectClass> { a, b ->
                    val module = (a.moduleName ?: "Project").compareTo(b.moduleName ?: "Project", ignoreCase = true)
                    if (module != 0) module else {
                        val byName = a.name.compareTo(b.name, ignoreCase = true)
                        val name = if (sortDirection == "DESC") -byName else byName
                        if (name != 0) name else a.id.compareTo(b.id)
                    }
                }
                val filtered = scan.allClasses.asSequence().filter { cls ->
                    matchesClassSearch(classSearch, cls) &&
                        allow(filters, "type", cls.primaryType.name) &&
                        allow(filters, "role", cls.primaryType.name) &&
                        allow(filters, "class", cls.name) &&
                        allow(filters, "module", cls.moduleName ?: "Project") &&
                        allow(filters, "language", cls.language.name) &&
                        allow(filters, "confidence", cls.classification.confidence.name) &&
                        matchesAdvanced(advanced, cls, inbound[cls.id] ?: 0, outbound[cls.id] ?: 0)
                }
                sortedPage(meta, filtered, comparator, offset, limit) {
                    component(it, false, scan.relationships)
                }
            }
            "component-options" -> {
                // The snapshot facet is intentionally bounded. Search this endpoint to
                // select any class in an enterprise project, including those beyond 200 names.
                val filtered = scan.allClasses.asSequence().filter { cls ->
                    matches(search, cls.name, cls.qualifiedName, cls.sourcePath) &&
                        allow(filters, "module", cls.moduleName ?: "Project") &&
                        allow(filters, "type", cls.primaryType.name) &&
                        allow(filters, "role", cls.primaryType.name) &&
                        allow(filters, "language", cls.language.name) &&
                        allow(filters, "confidence", cls.classification.confidence.name)
                }.map { it.name }.distinct()
                page(meta, filtered, offset, limit) { it }
            }
            "files" -> {
                val filtered = result.files.asSequence().filter { file ->
                    matches(search, file.name, file.relativePath) &&
                        allow(filters, "filename", file.name) &&
                        allow(filters, "extension", fileExtension(file.name)) &&
                        allow(filters, "language", fileLanguage(file)) &&
                        allow(filters, "module", file.moduleName ?: "Project") &&
                        allow(filters, "category", file.type.name) &&
                        allow(filters, "contentKind", file.intelligence.contentKind.name)
                }
                page(meta, filtered, offset, limit, ::file)
            }
            "file-options" -> {
                // File-name facets are capped at 200 in the snapshot. Offer a bounded,
                // searchable catalog across ALL files without transferring the file list.
                val filtered = result.files.asSequence().filter { file ->
                    matches(search, file.name, file.relativePath) &&
                        allow(filters, "extension", fileExtension(file.name)) &&
                        allow(filters, "language", fileLanguage(file)) &&
                        allow(filters, "module", file.moduleName ?: "Project") &&
                        allow(filters, "category", file.type.name) &&
                        allow(filters, "contentKind", file.intelligence.contentKind.name)
                }.map { it.name }.distinct()
                page(meta, filtered, offset, limit) { it }
            }
            "frameworks" -> {
                val moduleIndex = frameworkModulesByName(scan.frameworkInventory.frameworks, result.files)
                val filtered = scan.frameworkInventory.frameworks.filter { framework ->
                    matches(search, framework.name) &&
                        allow(filters, "confidence", framework.confidence.name) &&
                        (moduleIndex.getValue(framework.name).any { allow(filters, "module", it) }) &&
                        allow(filters, "category", frameworkCategory(framework.name))
                }
                page(meta, filtered.asSequence(), offset, limit) {
                    framework(it, result.files, scan.allClasses, false, moduleIndex.getValue(it.name))
                }
            }
            "framework-detail" -> {
                val name = request.string("name")
                val match = scan.frameworkInventory.frameworks.firstOrNull { it.name == name }
                gson.toJson(meta + mapOf("item" to match?.let {
                    val modules = frameworkModulesByName(listOf(it), result.files).getValue(it.name)
                    framework(it, result.files, scan.allClasses, true, modules)
                }))
            }
            "component-detail" -> {
                val id = request.string("id")
                val match = scan.allClasses.firstOrNull { it.id == id }
                gson.toJson(meta + mapOf("item" to match?.let { component(it, true, scan.relationships) }))
            }
            "file-detail" -> {
                val path = request.string("path")
                // Absolute source identity wins; a relative name shared by modules is ambiguous.
                val match = result.files.firstOrNull { it.path == path }
                    ?: result.files.filter { it.relativePath == path }.singleOrNull()
                gson.toJson(meta + mapOf("item" to match?.let { selected ->
                    val classes = scan.allClasses.asSequence().filter { it.sourcePath == selected.path }
                        .map { it.qualifiedName ?: it.name }.distinct().toList()
                    val dependencies = scan.relationships.asSequence().filter { it.evidencePath == selected.path }
                        .map { it.targetQualifiedName }.distinct().toList()
                    file(selected) + mapOf(
                        "classes" to classes.take(MAX_PAGE), "classesTotal" to classes.size,
                        "dependencies" to dependencies.take(MAX_PAGE), "dependenciesTotal" to dependencies.size
                    )
                }))
            }
            "diagram" -> graph(meta, result, request, filters, search)
            else -> gson.toJson(meta + mapOf("error" to "Unsupported query type"))
        }
    }

    fun facets(result: ProjectAnalysisResult): Map<String, Map<String, List<String>>> {
        val classes = result.scanResult.allClasses
        val files = result.files
        val frameworks = result.scanResult.frameworkInventory.frameworks
        val frameworkModules = frameworkModulesByName(frameworks, files)
        // Exactly the first 200 distinct sorted values, with O(200) memory even for
        // 100k-file projects. Never materialize every name just to populate a filter.
        fun values(items: Sequence<String>): List<String> {
            val smallest = TreeSet<String>()
            items.forEach { value ->
                if (smallest.size < MAX_FACET_VALUES || value <= smallest.last()) {
                    smallest.add(value)
                    if (smallest.size > MAX_FACET_VALUES) smallest.pollLast()
                }
            }
            return smallest.toList()
        }
        return mapOf(
            "components" to mapOf(
                "type" to values(classes.asSequence().map { it.primaryType.name }),
                "role" to values(classes.asSequence().map { it.primaryType.name }),
                "class" to values(classes.asSequence().map { it.name }),
                "module" to values(classes.asSequence().map { it.moduleName ?: "Project" }),
                "language" to values(classes.asSequence().map { it.language.name }),
                "confidence" to values(classes.asSequence().map { it.classification.confidence.name })
            ),
            "files" to mapOf(
                "filename" to values(files.asSequence().map { it.name }),
                "extension" to values(files.asSequence().map { fileExtension(it.name) }),
                "language" to values(files.asSequence().map(::fileLanguage)),
                "module" to values(files.asSequence().map { it.moduleName ?: "Project" }),
                "category" to values(files.asSequence().map { it.type.name }),
                "contentKind" to values(files.asSequence().map { it.intelligence.contentKind.name })
            ),
            "frameworks" to mapOf(
                "confidence" to values(frameworks.asSequence().map { it.confidence.name }),
                "module" to values(frameworks.asSequence().flatMap { framework ->
                    frameworkModules.getValue(framework.name).asSequence()
                }),
                "category" to values(frameworks.asSequence().map { frameworkCategory(it.name) })
            ),
            "diagram" to mapOf(
                "type" to values(classes.asSequence().map { it.primaryType.name }),
                "module" to values(classes.asSequence().map { it.moduleName ?: "Project" }),
                "confidence" to values(classes.asSequence().map { it.classification.confidence.name }),
                "relationship" to values(result.scanResult.relationships.asSequence().map { it.type.name })
            )
        )
    }

    private fun graph(
        meta: Map<String, Any>, result: ProjectAnalysisResult,
        request: JsonObject, filters: JsonObject?, search: String
    ): String {
        val scan = result.scanResult
        val mode = request.string("view").ifBlank { "layers" }
        require(mode in SUPPORTED_GRAPH_VIEWS) { "Unsupported Project Brain graph view" }
        val requestedFocus = request.string("focus")
        val groupType = request.string("groupType")
        val groupModule = request.string("groupModule")
        val classes = scan.allClasses.filter { cls ->
            matches(search, cls.name, cls.qualifiedName, cls.sourcePath) &&
                allow(filters, "type", cls.primaryType.name) &&
                allow(filters, "module", cls.moduleName ?: "Project") &&
                allow(filters, "confidence", cls.classification.confidence.name) &&
                (mode != "expanded" || (
                    (groupType.isNotBlank() || groupModule.isNotBlank()) &&
                        (groupType.isBlank() || cls.primaryType.name == groupType) &&
                        (groupModule.isBlank() || (cls.moduleName ?: "Project") == groupModule)
                    ))
        }
        // Class IDs are stable per module; qualified names are not necessarily unique in
        // multi-module projects. An ambiguous typed focus must never pick an arbitrary type.
        val focusId = request.string("focusId")
        val matches = when {
            focusId.isNotBlank() -> classes.filter { it.id == focusId }
            requestedFocus.isBlank() -> emptyList()
            else -> classes.filter {
                it.id == requestedFocus || it.qualifiedName.equals(requestedFocus, ignoreCase = true) ||
                    it.name.equals(requestedFocus, ignoreCase = true)
            }
        }
        val focus = matches.singleOrNull()
        val notice = when {
            requestedFocus.isNotBlank() && matches.size > 1 -> "More than one class matches this name. Select a class from Components or narrow the module filter."
            (requestedFocus.isNotBlank() || focusId.isNotBlank()) && focus == null -> "No matching class in the current project filters."
            else -> ""
        }
        val classById = scan.allClasses.associateBy { it.id }
        val filteredIds = classes.mapTo(hashSetOf()) { it.id }
        val edges = scan.relationships.filter { edge ->
            edge.targetClassId != null && edge.sourceClassId in filteredIds && edge.targetClassId in filteredIds &&
                allow(filters, "relationship", edge.type.name)
        }
        val nodes: List<Map<String, Any?>>
        val links: List<Map<String, Any>>
        val totalNodes: Int
        val totalGraphEdges: Int
        when (mode) {
            "modules", "layers" -> {
                val byKey = classes.groupBy { if (mode == "modules") it.moduleName ?: "Project" else it.primaryType.name }
                val keyOf: (ProjectClass) -> String = { if (mode == "modules") it.moduleName ?: "Project" else it.primaryType.name }
                val selected = byKey.entries.sortedBy { it.key }.take(MAX_GRAPH_NODES)
                val selectedKeys = selected.mapTo(hashSetOf()) { it.key }
                nodes = selected.map { (key, members) -> mapOf(
                    "id" to key, "name" to if (mode == "modules") key else key.replace('_', ' '),
                    "type" to if (mode == "modules") "MODULE" else key,
                    "count" to members.size,
                    "module" to if (mode == "modules") key else "All modules",
                    "path" to null
                ) }
                val groupedCounts = linkedMapOf<Triple<String, String, String>, Int>()
                edges.forEach { edge ->
                    val source = classById[edge.sourceClassId] ?: return@forEach
                    val target = classById[edge.targetClassId ?: return@forEach] ?: return@forEach
                    val from = keyOf(source)
                    val to = keyOf(target)
                    if (from != to) {
                        val key = Triple(from, to, edge.type.name)
                        groupedCounts[key] = (groupedCounts[key] ?: 0) + edge.occurrences
                    }
                }
                val groupedLinks = groupedCounts.map { (key, count) ->
                    mapOf<String, Any>("source" to key.first, "target" to key.second,
                        "type" to key.third, "occurrences" to count)
                }
                val selectedLinks = groupedLinks.filter { link ->
                    (link["source"] as String) in selectedKeys && (link["target"] as String) in selectedKeys
                }
                links = selectedLinks.take(MAX_GRAPH_EDGES)
                totalNodes = byKey.size
                totalGraphEdges = groupedLinks.size
            }
            else -> {
                val candidates = when {
                    mode == "expanded" -> classes.sortedBy { it.name }
                    focus == null -> emptyList()
                    mode == "impact" -> {
                        val reverse = edges.groupBy { it.targetClassId }
                        val affected = linkedSetOf(focus.id)
                        var frontier = setOf(focus.id)
                        repeat(2) {
                            val next = frontier.flatMap { target -> reverse[target].orEmpty().map { it.sourceClassId } }
                                .filterNot(affected::contains).toSet()
                            affected += next
                            frontier = next
                        }
                        classes.filter { it.id in affected }.sortedWith(
                            compareBy<ProjectClass> { it.id != focus.id }.thenBy { it.name })
                    }
                    else -> {
                        val connected = edges.filter { it.sourceClassId == focus.id || it.targetClassId == focus.id }
                            .flatMap { listOfNotNull(it.sourceClassId, it.targetClassId) }.toSet() + focus.id
                        classes.filter { it.id in connected }.sortedWith(
                            compareBy<ProjectClass> { it.id != focus.id }.thenBy { it.name })
                    }
                }
                val picked = candidates.take(MAX_GRAPH_NODES)
                val ids = picked.mapTo(hashSetOf()) { it.id }
                val candidateIds = candidates.mapTo(hashSetOf()) { it.id }
                val candidateEdges = edges.filter { it.sourceClassId in candidateIds && it.targetClassId in candidateIds }
                val incoming = candidateEdges.groupingBy { it.targetClassId }.eachCount()
                val outgoing = candidateEdges.groupingBy { it.sourceClassId }.eachCount()
                nodes = picked.map { cls -> mapOf(
                    "id" to cls.id, "name" to cls.name, "qualifiedName" to (cls.qualifiedName ?: cls.name),
                    "type" to cls.primaryType.name, "count" to 1,
                    "module" to (cls.moduleName ?: "Project"), "path" to cls.sourcePath,
                    "sourceOffset" to cls.sourceOffset,
                    "incoming" to (incoming[cls.id] ?: 0), "outgoing" to (outgoing[cls.id] ?: 0)
                ) }
                links = candidateEdges.asSequence().filter { it.sourceClassId in ids && it.targetClassId in ids }
                    .take(MAX_GRAPH_EDGES).map { mapOf<String, Any>(
                        "source" to it.sourceClassId, "target" to requireNotNull(it.targetClassId),
                        "type" to it.type.name, "occurrences" to it.occurrences
                    ) }.toList()
                totalNodes = candidates.size
                totalGraphEdges = candidateEdges.size
            }
        }
        return gson.toJson(meta + mapOf(
            "view" to mode, "nodes" to nodes, "edges" to links,
            "totalNodes" to totalNodes, "truncated" to (totalNodes > nodes.size || totalGraphEdges > links.size),
            "totalEdges" to totalGraphEdges, "notice" to notice
        ))
    }

    private fun component(cls: ProjectClass, detail: Boolean, edges: List<ProjectRelationship>): Map<String, Any?> {
        val qualified = cls.qualifiedName ?: cls.name
        val matched = cls.classification.candidates.firstOrNull { it.type == cls.primaryType }
        val base = mapOf(
            "id" to cls.id, "name" to cls.name, "qualifiedName" to qualified,
            "packageName" to cls.packageName, "type" to cls.primaryType.name,
            "language" to cls.language.name, "module" to (cls.moduleName ?: "Project"),
            "path" to cls.sourcePath, "sourceOffset" to cls.sourceOffset,
            "confidence" to cls.classification.confidence.name,
            "ambiguous" to cls.classification.ambiguous,
            "evidence" to (matched?.evidence?.map { "${it.kind.name}: ${it.matchedSymbol}" } ?: emptyList<String>())
        )
        if (!detail) return base
        val incoming = edges.filter { it.targetClassId == cls.id }
        val outgoing = edges.filter { it.sourceClassId == cls.id }
        return base + mapOf(
            "annotations" to cls.annotations.sorted(),
            "superTypes" to cls.superTypes.sorted(),
            "interfaces" to cls.interfaces.sorted(),
            "modifiers" to cls.modifiers.sorted(),
            "origin" to cls.origin.name,
            "classificationCandidates" to cls.classification.candidates.map { candidate ->
                mapOf("type" to candidate.type.name, "confidence" to candidate.confidence.name,
                    "score" to candidate.score,
                    "evidence" to candidate.evidence.map { "${it.kind.name}: ${it.matchedSymbol}" })
            },
            "incoming" to incoming.take(100).map(::edge),
            "outgoing" to outgoing.take(100).map(::edge),
            "incomingTotal" to incoming.size,
            "outgoingTotal" to outgoing.size
        )
    }

    private fun edge(edge: ProjectRelationship) = mapOf(
        "source" to edge.sourceQualifiedName, "target" to edge.targetQualifiedName,
        "sourceId" to edge.sourceClassId, "targetId" to edge.targetClassId,
        "type" to edge.type.name, "evidence" to edge.evidence,
        "confidence" to edge.confidence.name, "occurrences" to edge.occurrences,
        "resolved" to (edge.targetClassId != null), "evidencePath" to edge.evidencePath
    )

    private fun file(file: ProjectFile) = mapOf(
        "name" to file.name, "path" to file.path, "relativePath" to file.relativePath,
        "extension" to fileExtension(file.name),
        "category" to file.type.name, "module" to (file.moduleName ?: "Project"),
        "language" to fileLanguage(file),
        "fileType" to (file.intelligence.fileTypeId ?: "Unknown"),
        "mimeType" to (file.intelligence.mimeType ?: "Unknown"),
        "contentKind" to file.intelligence.contentKind.name,
        "contentType" to fileContentType(file),
        "capability" to file.intelligence.capability.name,
        "sizeBytes" to file.intelligence.sizeBytes,
        "status" to when {
            file.intelligence.capability.name == "UNSUPPORTED" -> "UNSUPPORTED"
            "content-sample:unavailable" in file.intelligence.evidence -> "PARTIAL"
            else -> "ANALYZED"
        }
    )

    private fun fileExtension(name: String): String =
        name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }
            ?.lowercase(Locale.ROOT) ?: ""

    private fun fileLanguage(file: ProjectFile): String {
        val detected = file.intelligence.languageId?.trim()
        if (!detected.isNullOrEmpty() && detected.uppercase(Locale.ROOT) !in setOf("TEXT", "ANY", "UNKNOWN")) {
            return detected
        }
        return when (file.type) {
            ProjectFileType.JAVA_SOURCE -> "Java"
            ProjectFileType.KOTLIN_SOURCE -> "Kotlin"
            ProjectFileType.XML -> "XML"
            ProjectFileType.YAML -> "YAML"
            ProjectFileType.PROPERTIES -> "Properties"
            ProjectFileType.JSON -> "JSON"
            ProjectFileType.SQL -> "SQL"
            ProjectFileType.GRADLE -> if (file.name.endsWith(".kts")) "Kotlin" else "Groovy"
            else -> "Unknown"
        }
    }

    private fun fileContentType(file: ProjectFile): String = when (file.type) {
        ProjectFileType.JAVA_SOURCE -> "Java source"
        ProjectFileType.KOTLIN_SOURCE -> "Kotlin source"
        ProjectFileType.MAVEN -> "Maven build configuration"
        ProjectFileType.GRADLE -> "Gradle build configuration"
        ProjectFileType.YAML -> "YAML configuration"
        ProjectFileType.PROPERTIES -> "Properties configuration"
        ProjectFileType.SQL -> "SQL script"
        ProjectFileType.XML -> "XML document"
        ProjectFileType.JSON -> "JSON document"
        ProjectFileType.DOCKER -> "Docker configuration"
        ProjectFileType.DOCUMENTATION -> "Documentation"
        ProjectFileType.OTHER -> when (fileExtension(file.name)) {
            "txt", "log", "csv", "tsv" -> "Plain text"
            "toml" -> "TOML configuration"
            "html", "htm" -> "HTML document"
            "css" -> "CSS stylesheet"
            "js", "mjs", "cjs" -> "JavaScript source"
            "ts", "tsx" -> "TypeScript source"
            "py" -> "Python source"
            "sh", "bash", "zsh" -> "Shell script"
            else -> file.intelligence.fileTypeId?.takeUnless {
                it.uppercase(Locale.ROOT) in setOf("TEXT", "UNKNOWN")
            } ?: file.intelligence.contentKind.name
        }
    }

    private fun framework(item: DetectedFramework, files: List<ProjectFile>, classes: List<ProjectClass>,
                          detail: Boolean, modules: List<String>): Map<String, Any> {
        val base = mapOf<String, Any>(
            "name" to item.name, "confidence" to item.confidence.name,
            "category" to frameworkCategory(item.name), "version" to (item.version ?: "Unknown"),
            "modules" to modules,
            "versionStatus" to when {
                item.versionEvidenceTruncated -> "INCOMPLETE_EVIDENCE"
                item.version != null -> "EXACT_DECLARATION"
                item.declaredVersions.isNotEmpty() -> "CONFLICTING_DECLARATIONS"
                else -> "NOT_DECLARED"
            },
            "evidence" to item.evidence.take(2)
        )
        if (!detail) return base
        val evidencePaths = item.evidence.map { it.substringBefore(':') }.toSet() +
            item.dependencyDeclarations.map { it.path }
        val matchedFiles = files.filter { it.relativePath in evidencePaths || it.path in evidencePaths }
        val matchedPaths = matchedFiles.mapTo(hashSetOf()) { it.path }
        // Never use endsWith(): two modules can have the same path suffix/class name.
        val matching = classes.filter { it.sourcePath in matchedPaths }
        val classPaths = matching.mapTo(hashSetOf()) { it.sourcePath }
        val declarations = item.dependencyDeclarations.map { declaration -> mapOf(
            "coordinate" to declaration.coordinate, "path" to declaration.path,
            "origin" to declaration.origin.name,
            "version" to (declaration.version ?: "Unknown")
        ) }
        return base + mapOf(
            "declaredVersions" to item.declaredVersions.map { version ->
                mapOf("version" to version.value, "path" to version.path, "dependency" to version.coordinate)
            },
            "evidence" to item.evidence,
            "dependencies" to declarations,
            "directDependencies" to declarations.filter { it["origin"] == FrameworkDependencyOrigin.DIRECT.name },
            "transitiveDependencies" to declarations.filter { it["origin"] == FrameworkDependencyOrigin.TRANSITIVE.name },
            "otherDeclarations" to declarations.filter { it["origin"] != FrameworkDependencyOrigin.DIRECT.name &&
                it["origin"] != FrameworkDependencyOrigin.TRANSITIVE.name },
            "dependencyEvidenceTruncated" to item.dependencyEvidenceTruncated,
            "dependencyResolution" to "MANIFEST_ONLY",
            "relatedClasses" to matching.map { it.qualifiedName ?: it.name }.distinct().take(80),
            "classSources" to matching.distinctBy { it.id }.take(80).map {
                mapOf("name" to (it.qualifiedName ?: it.name), "path" to it.sourcePath,
                    "offset" to it.sourceOffset)
            },
            "annotations" to matching.flatMap { it.annotations }.distinct().take(50),
            "configurationFiles" to matchedFiles.filter { it.path !in classPaths }
                .map { it.relativePath }.distinct().take(50)
        )
    }

    /** One pass over project files, even when dozens of frameworks share a 100k-file model. */
    private fun frameworkModulesByName(
        frameworks: List<DetectedFramework>, files: List<ProjectFile>
    ): Map<String, List<String>> {
        if (frameworks.isEmpty()) return emptyMap()
        val interested = hashMapOf<String, MutableSet<String>>()
        frameworks.forEach { framework ->
            val paths = framework.evidence.map { it.substringBefore(':') } +
                framework.dependencyDeclarations.map { it.path }
            paths.forEach { path -> interested.getOrPut(path) { linkedSetOf() }.add(framework.name) }
        }
        val found = hashMapOf<String, MutableSet<String>>()
        files.forEach { file ->
            val module = file.moduleName ?: "Project"
            interested[file.relativePath]?.forEach { framework ->
                found.getOrPut(framework) { linkedSetOf() }.add(module)
            }
            if (file.path != file.relativePath) interested[file.path]?.forEach { framework ->
                found.getOrPut(framework) { linkedSetOf() }.add(module)
            }
        }
        return frameworks.associate { framework ->
            framework.name to (found[framework.name]?.sorted() ?: listOf("Unknown"))
        }
    }

    private fun frameworkCategory(name: String): String = when {
        name.startsWith("Spring") -> "Spring ecosystem"
        name.startsWith("Jakarta") -> "Jakarta ecosystem"
        name in setOf("React", "Vue", "Angular", "Next.js", "NestJS", "Express") -> "Web ecosystem"
        name in setOf("Quarkus", "Micronaut", "Ktor", "Apache Camel") -> "JVM ecosystem"
        name in setOf("Hibernate ORM", "Jakarta Persistence") -> "Persistence ecosystem"
        name in setOf("Django", "FastAPI", "Flask") -> "Python ecosystem"
        else -> "Other"
    }

    /** Count matches in one pass while allocating at most one page, even with 100k results. */
    private fun <T> page(meta: Map<String, Any>, source: Sequence<T>, offset: Int, limit: Int, map: (T) -> Any): String {
        var total = 0
        val items = ArrayList<Any>(limit)
        for (item in source) {
            if (total >= offset && items.size < limit) items += map(item)
            total++
        }
        return gson.toJson(meta + mapOf(
            "total" to total, "offset" to offset, "limit" to limit,
            "hasMore" to (offset + limit < total), "items" to items
        ))
    }

    /**
     * Return a deterministic sorted page without sorting/materializing the whole
     * matching project. Normal first-page requests retain only 40 candidates.
     */
    private fun <T> sortedPage(
        meta: Map<String, Any>, source: Sequence<T>, comparator: Comparator<T>,
        offset: Int, limit: Int, map: (T) -> Any
    ): String {
        val retained = (offset.toLong() + limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Do not preallocate an attacker-controlled offset-sized array. The queue
        // grows only as real model matches are observed.
        val initialCapacity = retained.coerceIn(1, 1_024)
        val best = PriorityQueue<T>(initialCapacity, comparator.reversed())
        var total = 0
        source.forEach { item ->
            total++
            if (best.size < retained) {
                best += item
            } else if (retained > 0 && comparator.compare(item, best.peek()) < 0) {
                best.poll()
                best += item
            }
        }
        val ordered = best.toList().sortedWith(comparator)
        val from = offset.coerceAtMost(ordered.size)
        val items = ordered.subList(from, ordered.size.coerceAtMost(from + limit)).map(map)
        return gson.toJson(meta + mapOf(
            "total" to total, "offset" to offset, "limit" to limit,
            "hasMore" to (offset + limit < total), "items" to items
        ))
    }

    /** Bounded name-search grammar: comma/OR separates alternatives, AND combines terms,
     *  NOT excludes a term and LIKE accepts % / _ wildcards. This is not executable SQL.
     *  Advanced field-level comparisons are separately passed as validated JSON rules. */
    private fun parseClassSearch(search: String): List<List<ClassSearchTerm>> {
        if (search.isBlank()) return emptyList()
        val normalized = search.replace(Regex("""\s+NOT\s+""", RegexOption.IGNORE_CASE), " AND NOT ")
        val alternatives = normalized.split(Regex("""\s+OR\s+|,|\n""", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        require(alternatives.size <= 20) { "Too many class search alternatives" }
        return alternatives.map { alternative ->
            val terms = alternative.split(Regex("""\s+AND\s+""", RegexOption.IGNORE_CASE))
                .map { it.trim() }.filter { it.isNotEmpty() }
            require(terms.size <= 20) { "Too many class search terms" }
            terms.map { raw ->
                val negative = raw.startsWith("NOT ", ignoreCase = true)
                val term = (if (negative) raw.substring(4) else raw).trim()
                val isLike = term.startsWith("LIKE ", ignoreCase = true)
                val needle = (if (isLike) term.substring(5) else term).trim()
                    .trim('"', '\'')
                ClassSearchTerm(negative, isLike, needle)
            }
        }
    }

    private fun matchesClassSearch(alternatives: List<List<ClassSearchTerm>>, cls: ProjectClass): Boolean {
        if (alternatives.isEmpty()) return true
        return alternatives.any { terms ->
            terms.all { term ->
                val found = if (term.needle.isBlank()) false else if (term.like) {
                    listOfNotNull(cls.name, cls.qualifiedName, cls.sourcePath)
                        .any { likeMatch(it, term.needle) }
                } else {
                    matches(term.needle.lowercase(Locale.ROOT), cls.name, cls.qualifiedName, cls.sourcePath)
                }
                if (term.negative) !found else found
            }
        }
    }

    /** Structured search rules are bounded and never evaluated as SQL or code. */
    private fun advancedRules(raw: JsonElement?): List<AdvancedRule> {
        if (raw == null || raw.isJsonNull) return emptyList()
        require(raw.isJsonObject) { "Advanced search must be an object" }
        val obj = raw.asJsonObject
        require(obj.keySet().all { it == "rules" }) { "Unsupported advanced search property" }
        val arr = obj.get("rules")
        require(arr != null && arr.isJsonArray && arr.asJsonArray.size() <= MAX_ADVANCED_RULES) {
            "Too many advanced search rules"
        }
        return arr.asJsonArray.mapIndexed { index, item ->
            require(item.isJsonObject) { "Advanced search rule must be an object" }
            val rule = item.asJsonObject
            require(rule.keySet().all { it in setOf("field", "operator", "value", "join", "negate") }) {
                "Unsupported advanced search field"
            }
            for (key in listOf("field", "operator", "value", "join")) {
                val v = rule.get(key)
                require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                    "Invalid advanced search rule"
                }
            }
            val field = rule.get("field").asString
            val op = rule.get("operator").asString
            val value = rule.get("value").asString.trim()
            val join = rule.get("join").asString
            val neg = rule.get("negate")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Invalid NOT flag" }
                it.asBoolean
            } ?: false
            require(field in ADVANCED_FIELDS && op in ADVANCED_OPERATORS &&
                join in setOf("AND", "OR") && value.isNotBlank() && value.length <= 200) {
                "Invalid advanced search rule value or operator"
            }
            if (index == 0) require(join == "AND") { "First advanced rule cannot use OR" }
            val numeric = field in setOf("incoming", "outgoing")
            require(numeric == (op in setOf("GT", "GE", "LT", "LE", "BETWEEN")) ||
                (!numeric && op in setOf("EQ", "NE", "LIKE", "NOT_LIKE", "IN", "NOT_IN"))) {
                "Advanced operator is not valid for this field"
            }
            if (numeric) {
                val parts = if (op == "BETWEEN") value.split(',') else listOf(value)
                require(parts.size == (if (op == "BETWEEN") 2 else 1) &&
                    parts.all { it.trim().toIntOrNull()?.let { v -> v >= 0 } == true }) {
                    "Numeric advanced filter must contain non-negative integers"
                }
            }
            AdvancedRule(field, op, value, join, neg)
        }
    }

    /** Linear wildcard matcher: % matches any run, _ matches one character; never builds regex. */
    private fun likeMatch(text: String, pattern: String): Boolean {
        var i = 0
        var j = 0
        var star = -1
        var backtrack = 0
        while (i < text.length) {
            if (j < pattern.length && (pattern[j] == '_' ||
                    text[i].equals(pattern[j], ignoreCase = true))) {
                i++; j++
            } else if (j < pattern.length && pattern[j] == '%') {
                star = j++; backtrack = i
            } else if (star >= 0) {
                j = star + 1
                i = ++backtrack
            } else return false
        }
        while (j < pattern.length && pattern[j] == '%') j++
        return j == pattern.length
    }

    private fun matchesAdvanced(
        rules: List<AdvancedRule>, cls: ProjectClass, incoming: Int, outgoing: Int
    ): Boolean {
        if (rules.isEmpty()) return true
        fun evaluate(rule: AdvancedRule): Boolean {
            val value = when (rule.field) {
                "name" -> cls.name
                "qualifiedName" -> cls.qualifiedName ?: cls.name
                "package" -> cls.packageName
                "module" -> cls.moduleName ?: "Project"
                "role", "type" -> cls.primaryType.name
                "language" -> cls.language.name
                "confidence" -> cls.classification.confidence.name
                "incoming" -> incoming.toString()
                else -> outgoing.toString()
            }
            val match = when (rule.operator) {
                "EQ" -> value.equals(rule.value, ignoreCase = true)
                "NE" -> !value.equals(rule.value, ignoreCase = true)
                "LIKE" -> likeMatch(value, rule.value)
                "NOT_LIKE" -> !likeMatch(value, rule.value)
                "IN", "NOT_IN" -> {
                    val options = rule.value.split(',').map { it.trim() }
                    val found = options.any { it.equals(value, ignoreCase = true) }
                    if (rule.operator == "IN") found else !found
                }
                else -> {
                    val current = value.toIntOrNull() ?: 0
                    val parts = rule.value.split(',').map { it.trim().toInt() }
                    when (rule.operator) {
                        "GT" -> current > parts[0]
                        "GE" -> current >= parts[0]
                        "LT" -> current < parts[0]
                        "LE" -> current <= parts[0]
                        else -> current in parts[0]..parts[1]
                    }
                }
            }
            return if (rule.negate) !match else match
        }
        // Match standard SQL boolean precedence: AND clauses form a group, OR joins groups.
        var disjunction = false
        var conjunction = evaluate(rules[0])
        for (i in 1 until rules.size) {
            val hit = evaluate(rules[i])
            if (rules[i].join == "OR") {
                disjunction = disjunction || conjunction
                conjunction = hit
            } else {
                conjunction = conjunction && hit
            }
        }
        return disjunction || conjunction
    }

    private fun matches(search: String, vararg values: String?): Boolean =
        search.isBlank() || values.any { it?.contains(search, ignoreCase = true) == true }

    private fun allow(filters: JsonObject?, key: String, value: String): Boolean {
        val choices = filters?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return true
        return choices.size() == 0 || choices.any { it.isJsonPrimitive && it.asString.equals(value, ignoreCase = true) }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
}
