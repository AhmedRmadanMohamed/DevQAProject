package com.devqa.feature.projectbrain.application

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.classifier.component.ComponentClassificationPolicy
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBrainGraphResolutionTest {
    @Test
    fun `resolved declaration wins over a same-named class in the source module`() {
        val local = klass("local", "sample.Worker", "app", "/app/Worker.java")
        val remote = klass("remote", "sample.Worker", "library", "/library/Worker.java")
        val caller = klass("caller", "sample.Caller", "app", "/app/Caller.java", listOf(
            call("sample.Worker", "call:execute@20", remote.sourcePath)
        ))

        val model = model(listOf(local, remote, caller))
        val edge = model.scanResult.relationships.single()
        assertEquals("caller", edge.sourceClassId)
        assertEquals("remote", edge.targetClassId)

        val graph = JsonParser.parseString(ProjectBrainQueryService.query(model,
            """{"kind":"diagram","view":"dependency","focus":"sample.Caller","focusId":"caller"}"""
        )).asJsonObject
        val nodes = graph.getAsJsonArray("nodes").map { it.asJsonObject.get("id").asString }.toSet()
        assertEquals(setOf("caller", "remote"), nodes)
        assertEquals("remote", graph.getAsJsonArray("edges").single().asJsonObject.get("target").asString)

        val detail = JsonParser.parseString(ProjectBrainQueryService.query(model,
            """{"kind":"component-detail","id":"caller"}"""
        )).asJsonObject.getAsJsonObject("item")
        assertEquals("remote", detail.getAsJsonArray("outgoing").single().asJsonObject.get("targetId").asString)
    }

    @Test
    fun `ambiguous targets are not guessed and external inheritance remains observable`() {
        val first = klass("first", "sample.Handler", "first", "/first/Handler.java")
        val second = klass("second", "sample.Handler", "second", "/second/Handler.java")
        val caller = klass("caller", "sample.Caller", "third", "/third/Caller.java", listOf(
            call("sample.Handler", "call:ambiguous@4")
        )).copy(superTypes = setOf("external.LibraryBase"))

        val edges = ProjectModelAssembler.relationships(listOf(first, second, caller))
        assertEquals(1, edges.size)
        assertEquals(RelationshipType.EXTENDS, edges.single().type)
        assertNull(edges.single().targetClassId)

        val graph = JsonParser.parseString(ProjectBrainQueryService.query(model(listOf(first, second, caller)),
            """{"kind":"diagram","view":"dependency","focusId":"caller"}"""
        )).asJsonObject
        assertEquals(0, graph.getAsJsonArray("edges").size())
        assertEquals(1, graph.getAsJsonArray("nodes").size())
    }

    @Test
    fun `repeated calls form one evidence-backed edge with an accurate occurrence count`() {
        val receiver = klass("receiver", "sample.Receiver", "app", "/app/Receiver.java")
        val caller = klass("caller", "sample.Caller", "app", "/app/Caller.java", listOf(
            call("sample.Receiver", "call:first@4", receiver.sourcePath),
            call("sample.Receiver", "call:second@8", receiver.sourcePath),
            call("sample.Receiver", "call:second@8", receiver.sourcePath)
        ))
        val edges = ProjectModelAssembler.relationships(listOf(caller, receiver))
        assertEquals(1, edges.size)
        assertEquals(2, edges.single().occurrences)

        val graph = JsonParser.parseString(ProjectBrainQueryService.query(model(listOf(caller, receiver)),
            """{"kind":"diagram","view":"modules"}"""
        )).asJsonObject
        assertFalse(graph.get("truncated").asBoolean)
        assertEquals(0, graph.getAsJsonArray("edges").size()) // Both classes belong to one module.
    }

    @Test
    fun `qualified name collisions require a unique graph focus`() {
        val first = klass("first", "sample.Handler", "first", "/first/Handler.java")
        val second = klass("second", "sample.Handler", "second", "/second/Handler.java")
        val graph = JsonParser.parseString(ProjectBrainQueryService.query(model(listOf(first, second)),
            """{"kind":"diagram","view":"dependency","focus":"sample.Handler"}"""
        )).asJsonObject
        assertEquals(0, graph.getAsJsonArray("nodes").size())
        assertTrue(graph.get("notice").asString.contains("More than one class"))
    }

    @Test
    fun `impact graph traverses resolved reverse edges for exactly two levels`() {
        val storage = klass("storage", "sample.Storage", "data", "/data/Storage.java")
        val service = klass("service", "sample.Service", "business", "/business/Service.java", listOf(
            call("sample.Storage", "call:store@10", storage.sourcePath)
        ))
        val controller = klass("controller", "sample.Controller", "api", "/api/Controller.java", listOf(
            call("sample.Service", "call:run@15", service.sourcePath)
        ))
        val unrelated = klass("other", "sample.Unrelated", "api", "/api/Unrelated.java")
        val graph = JsonParser.parseString(ProjectBrainQueryService.query(model(listOf(storage, service, controller, unrelated)),
            """{"kind":"diagram","view":"impact","focusId":"storage"}"""
        )).asJsonObject

        val selected = graph.getAsJsonArray("nodes").map { it.asJsonObject.get("id").asString }.toSet()
        assertEquals(setOf("storage", "service", "controller"), selected)
        assertEquals(2, graph.getAsJsonArray("edges").size())
        assertEquals(2, graph.get("totalEdges").asInt)
    }

    private fun model(classes: List<ProjectClass>): ProjectAnalysisResult {
        val edges = ProjectModelAssembler.relationships(classes)
        return ProjectAnalysisResult(ProjectScanResult(
            modelVersion = 2, springBoot = false, javaFiles = classes.size,
            javaClasses = classes.size, kotlinFiles = 0, kotlinClasses = 0,
            allClasses = classes, relationships = edges,
            architectureProfile = ProjectModelAssembler.architecture(classes),
            frameworkInventory = FrameworkInventory(null, emptyList()), diagnostics = emptyList(),
            metrics = ProjectScanMetrics(1, classes.size, classes.size, edges.size, 1, 0)
        ), emptyList())
    }

    private fun call(target: String, evidence: String, targetPath: String? = null) =
        ClassDependency(target, RelationshipType.CALLS, evidence, RelationshipConfidence.HIGH, targetPath)

    private fun klass(id: String, name: String, module: String, path: String,
                      dependencies: List<ClassDependency> = emptyList()) = ProjectClass(
        id = id, name = name.substringAfterLast('.'), qualifiedName = name,
        moduleName = module, packageName = "sample", sourcePath = path, sourceOffset = 0,
        language = ProjectLanguage.JAVA, origin = ProjectClassOrigin.MAIN,
        modifiers = emptySet(), annotations = emptySet(), superTypes = emptySet(), interfaces = emptySet(),
        dependencies = dependencies, classification = ComponentClassificationPolicy.classify(emptyList())
    )
}
