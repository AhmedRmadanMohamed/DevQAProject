package com.devqa.core.scanner

import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileDetectionConfidence
import com.devqa.core.fileintelligence.FileIntelligenceProfile
import com.devqa.core.model.DiagnosticSeverity
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ProjectFileScannerIncrementalTest {
    private val scanner = ProjectFileScanner()

    @Test
    fun `a single failed file does not block valid file updates or deletion`() {
        val failed = file("Broken.java")
        val changed = file("Changed.java")
        val removed = file("Deleted.java")
        val untouched = file("Untouched.java")
        val paths = linkedSetOf(failed.path, changed.path, removed.path)

        val outcome = scanner.reconcileFileChanges(listOf(failed, changed, removed, untouched), paths) { path ->
            when (path) {
                failed.path -> throw IllegalStateException("secret=should-never-appear")
                changed.path -> changed.copy(intelligence = changed.intelligence.copy(sizeBytes = 512))
                removed.path -> null
                else -> error("Unexpected file $path")
            }
        }

        assertEquals(setOf(changed.path, untouched.path), outcome.files.map { it.path }.toSet())
        assertEquals(512L, outcome.files.single { it.path == changed.path }.intelligence.sizeBytes)
        assertEquals(1, outcome.failures.size)
        assertEquals(failed.path, outcome.failures.single().path)
        assertEquals(DiagnosticSeverity.ERROR, outcome.failures.single().severity)
        assertEquals("File metadata analysis failed: IllegalStateException", outcome.failures.single().message)
        assertFalse(outcome.failures.single().message.contains("secret="))
    }

    @Test
    fun `cancellation is propagated and cannot publish a partial file refresh`() {
        val before = file("Before.java")
        val canceled = runCatching {
            scanner.reconcileFileChanges(listOf(before), setOf(before.path)) {
                throw ProcessCanceledException()
            }
        }.exceptionOrNull()
        assertTrue(canceled is ProcessCanceledException)

        val cooperativeCancellation = runCatching {
            scanner.reconcileFileChanges(listOf(before), setOf(before.path)) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(cooperativeCancellation is CancellationException)
    }

    @Test
    fun `no changed files preserves the published file index`() {
        val existing = listOf(file("Known.java"))
        val outcome = scanner.reconcileFileChanges(existing, emptySet()) { error("Must not resolve") }
        assertEquals(existing, outcome.files)
        assertTrue(outcome.failures.isEmpty())
    }

    private fun file(name: String): ProjectFile = ProjectFile(
        name, "/sample/src/$name", "src/$name", ProjectFileType.JAVA_SOURCE,
        ProjectSourceType.MAIN,
        FileIntelligenceProfile("JAVA", "JAVA", FileContentKind.TEXT, "text/x-java-source",
            FileAnalysisCapability.PSI, FileDetectionConfidence.HIGH, emptyList(), 0, 128),
        "sample"
    )
}
