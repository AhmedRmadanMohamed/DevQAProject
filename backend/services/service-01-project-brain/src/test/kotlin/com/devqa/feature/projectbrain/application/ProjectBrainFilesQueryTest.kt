package com.devqa.feature.projectbrain.application

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.analyzer.filter.ProjectFileDeduplicator
import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileDetectionConfidence
import com.devqa.core.fileintelligence.FileIntelligenceProfile
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.DiagnosticSeverity
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectRelationship
import com.devqa.core.model.ProjectScanDiagnostic
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.RelationshipType
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.classification.ComponentCandidate
import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType
import com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebSnapshotMapper
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBrainFilesQueryTest {
    @Test fun `query without a published model preserves request metadata and returns an empty result`() {
        val response = JsonParser.parseString(ProjectBrainQueryService.query(
            null, """{"kind":"files","requestId":"request-42"}"""
        )).asJsonObject

        assertEquals("files", response.get("kind").asString)
        assertEquals("request-42", response.get("requestId").asString)
        assertEquals("NO_MODEL", response.get("status").asString)
        assertEquals(0, response.get("total").asInt)
        assertEquals(0, response.getAsJsonArray("items").size())
    }

    @Test fun `query rejects oversized or malformed filter payloads before scanning the model`() {
        val result = model(listOf(file("Order.java", ProjectFileType.JAVA_SOURCE, "orders", "JAVA")))
        val requests = listOf(
            """{"kind":"files","search":"${"x".repeat(33_000)}"}""",
            """{"kind":"files","filters":{"module":[${List(257) { "\"orders\"" }.joinToString(",")}]}}""",
            """{"kind":"files","filters":{"module":[true]}}""",
            """{"kind":"files","filters":{"module":["${"x".repeat(257)}"]}}"""
        )
        requests.forEach { request ->
            assertTrue(runCatching { ProjectBrainQueryService.query(result, request) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(1, query(result, """{"kind":"files","filters":{"module":["orders"]}}""")
            .get("total").asInt)
    }

    @Test fun `two modules with the same relative file name remain independently indexed`() {
        val orders = file("config.yml", ProjectFileType.YAML, "orders", "YAML")
        val billing = file("config.yml", ProjectFileType.YAML, "billing", "YAML")
        val unique = ProjectFileDeduplicator().removeDuplicates(listOf(orders, billing, orders))
        assertEquals(2, unique.size)
        assertEquals(setOf(orders.path, billing.path), unique.map { it.path }.toSet())
        val model = model(unique)
        val absolute = query(model, """{"kind":"file-detail","path":"${billing.path}"}""")
        assertEquals("billing", absolute.getAsJsonObject("item").get("module").asString)
        val ambiguousRelative = query(model, """{"kind":"file-detail","path":"${billing.relativePath}"}""")
        assertTrue(ambiguousRelative.get("item").isJsonNull)
    }

    @Test fun `overview accounts for supported unsupported skipped and failed files without inventing successful scans`() {
        val analyzed = file("Order.java", ProjectFileType.JAVA_SOURCE, "orders", "JAVA")
        val unknown = file("unknown.bin", ProjectFileType.OTHER, "orders", "UNKNOWN")
        val unsupported = unknown.copy(intelligence = unknown.intelligence.copy(
            capability = FileAnalysisCapability.UNSUPPORTED))
        val complete = JsonParser.parseString(ProjectBrainWebSnapshotMapper.toJson(
            "sample", model(listOf(analyzed)))).asJsonObject
        assertEquals("ANALYSIS COMPLETE", complete.get("status").asString)
        assertEquals(1, complete.getAsJsonObject("metrics").get("analyzedFiles").asInt)

        val unresolved = cls("MissingRole", analyzed.path)
        val warningsOnly = model(listOf(analyzed), listOf(unresolved))
        val warning = JsonParser.parseString(ProjectBrainWebSnapshotMapper.toJson("sample", warningsOnly)).asJsonObject
        assertEquals("COMPLETED WITH WARNINGS", warning.get("status").asString)
        assertEquals(0, warning.getAsJsonObject("metrics").get("diagnostics").asInt)
        assertEquals(1, warning.getAsJsonObject("metrics").get("unclassified").asInt)

        val initial = model(listOf(analyzed, unsupported), listOf(unresolved))

        val failedPath = "/orders/src/broken.java"
        val partial = initial.copy(scanResult = initial.scanResult.copy(
            diagnostics = listOf(ProjectScanDiagnostic(failedPath,
                "File metadata analysis failed: RuntimeException", DiagnosticSeverity.ERROR)),
            metrics = initial.scanResult.metrics.copy(skippedFiles = 3, failedFiles = 1)
        ))
        val summary = JsonParser.parseString(ProjectBrainWebSnapshotMapper.toJson("sample", partial)).asJsonObject
        val metrics = summary.getAsJsonObject("metrics")
        assertEquals("PARTIALLY COMPLETED", summary.get("status").asString)
        assertEquals(6, metrics.get("discoveredFiles").asInt)
        assertEquals(2, metrics.get("projectFiles").asInt)
        assertEquals(1, metrics.get("analyzedFiles").asInt)
        assertEquals(1, metrics.get("unsupportedFiles").asInt)
        assertEquals(3, metrics.get("skippedFiles").asInt)
        assertEquals(1, metrics.get("failedFiles").asInt)
        assertEquals(1, metrics.get("diagnosticErrors").asInt)

        // A PSI/classification diagnostic on an indexed file is not a file metadata failure.
        val sourceError = initial.copy(scanResult = initial.scanResult.copy(diagnostics = listOf(
            ProjectScanDiagnostic(analyzed.path, "Unresolved symbol", DiagnosticSeverity.ERROR)
        )))
        val sourceMetrics = JsonParser.parseString(ProjectBrainWebSnapshotMapper.toJson(
            "sample", sourceError)).asJsonObject.getAsJsonObject("metrics")
        assertEquals(0, sourceMetrics.get("failedFiles").asInt)
    }

    @Test fun `facet catalogs are stable sorted and bounded even when file discovery is reverse ordered`() {
        val files = (400 downTo 0).map { index ->
            file("Config${index.toString().padStart(3, '0')}.json", ProjectFileType.JSON, "config", "JSON")
        }
        val facets = ProjectBrainQueryService.facets(model(files)).getValue("files")
        assertEquals((0 until 200).map { "Config${it.toString().padStart(3, '0')}.json" },
            facets.getValue("filename"))
        assertEquals(listOf("json"), facets.getValue("extension"))
        assertEquals(listOf("config"), facets.getValue("module"))
        assertEquals(listOf("JSON"), facets.getValue("category"))
    }

    @Test fun `file queries combine six filters and keep pagination on backend`() {
        val files = (0 until 250).map { index ->
            file("Asset$index.json", ProjectFileType.JSON, "resources", "JSON")
        } + file("OrderService.java", ProjectFileType.JAVA_SOURCE, "orders", "JAVA") +
            file("OrderRepo.kt", ProjectFileType.KOTLIN_SOURCE, "orders", "kotlin")
        val result = model(files)
        val page = query(result, """{"kind":"files","offset":100,"limit":40}""")
        assertEquals(252, page.get("total").asInt)
        assertEquals(40, page.getAsJsonArray("items").size())
        assertTrue(page.get("hasMore").asBoolean)
        assertEquals(200, ProjectBrainQueryService.facets(result).getValue("files").getValue("filename").size)

        val filtered = query(result, """{"kind":"files","search":"Order","filters":{
            "filename":["OrderService.java","OrderRepo.kt"],
            "extension":["java","kt"],"language":["JAVA","kotlin"],
            "module":["orders"],"category":["JAVA_SOURCE","KOTLIN_SOURCE"],"contentKind":["TEXT"]
        }}""")
        assertEquals(2, filtered.get("total").asInt)
    }

    @Test fun `file name options can find filenames beyond snapshot facet limit and combine other filters`() {
        val files = (0 until 260).map { file("Data${it.toString().padStart(3, '0')}.json", ProjectFileType.JSON, "resources", "JSON") }
        val result = query(model(files), """{"kind":"file-options","search":"Data259","filters":{
            "extension":["json"],"module":["resources"],"category":["JSON"]
        }}""")
        assertEquals(1, result.get("total").asInt)
        assertEquals("Data259.json", result.getAsJsonArray("items").single().asString)
    }

    @Test fun `file type is precise even when IntelliJ identifies source as plain Text`() {
        val source = file("OrderService.java", ProjectFileType.JAVA_SOURCE, "orders", "TEXT")
        val config = file("application.yml", ProjectFileType.YAML, "orders", "TEXT")
        val build = file("build.gradle.kts", ProjectFileType.GRADLE, "orders", "TEXT")
        val data = file("settings.toml", ProjectFileType.OTHER, "orders", "TEXT")
        val files = query(model(listOf(source, config, build, data)), """{"kind":"files"}""")
            .getAsJsonArray("items").map { it.asJsonObject }.associateBy { it.get("name").asString }
        assertEquals("Java source", files.getValue("OrderService.java").get("contentType").asString)
        assertEquals("Java", files.getValue("OrderService.java").get("language").asString)
        assertEquals("YAML configuration", files.getValue("application.yml").get("contentType").asString)
        assertEquals("Kotlin", files.getValue("build.gradle.kts").get("language").asString)
        assertEquals("TOML configuration", files.getValue("settings.toml").get("contentType").asString)
    }

    @Test fun `file details show qualified classes only from exact path and evidence based dependencies`() {
        val file = file("OrderService.java", ProjectFileType.JAVA_SOURCE, "orders", "JAVA")
        val classInFile = cls("OrderService", file.path)
        val classElsewhere = cls("OtherService", "/elsewhere/OrderService.java")
        val dependencies = listOf(
            ProjectRelationship(classInFile.id, "app.OrderService", "app.OrderRepo", RelationshipType.CALLS,
                file.path, "save"),
            ProjectRelationship(classElsewhere.id, "app.OtherService", "app.NotFromThisFile", RelationshipType.CALLS,
                "/elsewhere/OrderService.java", "save")
        )
        val detail = query(model(listOf(file), listOf(classInFile, classElsewhere), dependencies),
            """{"kind":"file-detail","path":"${file.path}"}""").getAsJsonObject("item")
        assertEquals(listOf("app.OrderService"), detail.getAsJsonArray("classes").map { it.asString })
        assertEquals(listOf("app.OrderRepo"), detail.getAsJsonArray("dependencies").map { it.asString })
        assertEquals(1, detail.get("dependenciesTotal").asInt)
        assertEquals("ANALYZED", detail.get("status").asString)
        val missing = query(model(listOf(file)), """{"kind":"file-detail","path":"/outside/secret"}""")
        assertTrue(missing.get("item").isJsonNull)
    }

    private fun query(model: ProjectAnalysisResult, request: String) =
        JsonParser.parseString(ProjectBrainQueryService.query(model, request)).asJsonObject

    private fun model(files: List<ProjectFile>, classes: List<ProjectClass> = emptyList(),
                      edges: List<ProjectRelationship> = emptyList()): ProjectAnalysisResult =
        ProjectAnalysisResult(ProjectScanResult(7, false, 0, 0, 0, 0, classes, edges,
            ProjectModelAssembler.architecture(classes, edges), FrameworkInventory(null, emptyList()), emptyList(),
            ProjectScanMetrics(1, files.size, classes.size, edges.size, 1, 0)), files)

    private fun file(name: String, type: ProjectFileType, module: String, language: String): ProjectFile =
        ProjectFile(name, "/$module/src/$name", "src/$name", type, ProjectSourceType.MAIN,
            FileIntelligenceProfile("TEXT", language, FileContentKind.TEXT, "text/plain",
                FileAnalysisCapability.PLAIN_TEXT, FileDetectionConfidence.MEDIUM,
                listOf("content-sample:20"), 20, 200), module)

    private fun cls(name: String, sourcePath: String): ProjectClass = ProjectClass(
        name, name, "app.$name", "orders", "app", sourcePath, 0,
        ProjectLanguage.JAVA, ProjectClassOrigin.MAIN,
        emptySet(), emptySet(), emptySet(), emptySet(), emptyList(),
        ComponentClassification(ComponentType.OTHER, ClassificationConfidence.NONE,
            listOf(ComponentCandidate(ComponentType.OTHER, 0, ClassificationConfidence.NONE,
                listOf(ClassificationEvidence(ComponentType.OTHER, ClassificationEvidenceKind.FALLBACK,
                    "unclassified")))), false)
    )
}
