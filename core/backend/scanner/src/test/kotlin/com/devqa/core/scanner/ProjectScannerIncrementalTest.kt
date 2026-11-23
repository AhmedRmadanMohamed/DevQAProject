package com.devqa.core.scanner

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.classifier.component.ComponentClassificationPolicy
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectScannerIncrementalTest {
    @Test
    fun `changed source files replace only their own classes and preserve the rest`() {
        val unchanged = source("InvoiceService", calls = listOf("sample.InvoiceRepository"))
        val original = source("InvoiceRepository")
        val removed = source("RemovedMapper")
        val changed = source("InvoiceRepository", calls = listOf("sample.NewMapper"))
        val added = source("NewMapper")

        val updated = ProjectScanner.reconcileClasses(
            listOf(unchanged, original, removed),
            setOf(original.sourcePath, removed.sourcePath, added.sourcePath),
            listOf(changed, added)
        )
        val freshlyScanned = listOf(unchanged, changed, added)

        assertEquals(freshlyScanned, updated)
        assertEquals(ProjectModelAssembler.relationships(freshlyScanned), ProjectModelAssembler.relationships(updated))
        assertEquals(ProjectModelAssembler.architecture(freshlyScanned), ProjectModelAssembler.architecture(updated))
        assertEquals(2, ProjectModelAssembler.relationships(updated).size)
    }

    @Test
    fun `renaming a source file drops its old class without losing unrelated project data`() {
        val unrelated = source("OtherService")
        val before = source("OldName")
        val after = source("NewName")
        val updated = ProjectScanner.reconcileClasses(
            listOf(unrelated, before), setOf(before.sourcePath, after.sourcePath), listOf(after)
        )

        assertEquals(listOf(unrelated, after), updated)
        assertFalse(updated.any { it.name == "OldName" })
    }

    @Test
    fun `nonstructural edits do not force full source rescans`() {
        assertFalse(ProjectScanner.requiresFullScan(setOf(
            "/project/src/main/java/sample/InvoiceService.java",
            "/project/src/main/resources/application.yml",
            "/project/README.md"
        )))
        assertTrue(ProjectScanner.requiresFullScan(setOf("/project/pom.xml")))
        assertTrue(ProjectScanner.requiresFullScan(setOf("/project/build.gradle.kts")))
        assertTrue(ProjectScanner.requiresFullScan(setOf("/project/src/main/java/sample")))
    }

    private fun source(name: String, calls: List<String> = emptyList()): ProjectClass = ProjectClass(
        id = name,
        name = name,
        qualifiedName = "sample.$name",
        moduleName = "app",
        packageName = "sample",
        sourcePath = "/project/src/main/java/sample/$name.java",
        sourceOffset = 0,
        language = ProjectLanguage.JAVA,
        origin = ProjectClassOrigin.MAIN,
        modifiers = emptySet(),
        annotations = emptySet(),
        superTypes = emptySet(),
        interfaces = emptySet(),
        dependencies = calls.map {
            ClassDependency(it, RelationshipType.CALLS, "mock-call:$it", RelationshipConfidence.HIGH)
        },
        classification = ComponentClassificationPolicy.classify(emptyList())
    )
}
