package com.devqa.core.scanner

import com.devqa.feature.performance.application.PerformanceTimer
import com.devqa.feature.performance.infrastructure.PerformanceReporter
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectScanDiagnostic
import com.devqa.core.model.DiagnosticSeverity
import com.devqa.core.classifier.file.ProjectFileClassifier
import com.devqa.core.analyzer.ProjectFileDiscovery
import com.devqa.core.analyzer.filter.ProjectFileDeduplicator
import com.devqa.core.fileintelligence.FileIntelligenceEngine
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.devqa.feature.projectbrain.application.ProjectBrainSettings
import com.devqa.core.analyzer.filter.ProjectFileFilter
import java.util.concurrent.CancellationException

data class ProjectFileScanOutcome(
    val files: List<ProjectFile>,
    val skippedFiles: Int,
    val failures: List<ProjectScanDiagnostic>
)

data class ProjectFileUpdateOutcome(
    val files: List<ProjectFile>,
    val failures: List<ProjectScanDiagnostic>
)

class ProjectFileScanner(
    private val discovery: ProjectFileDiscovery = ProjectFileDiscovery,
    private val deduplicator: ProjectFileDeduplicator = ProjectFileDeduplicator(),
    private val fileIntelligence: FileIntelligenceEngine = FileIntelligenceEngine(),
    private val performanceTimer: PerformanceTimer = PerformanceTimer(),
    private val performanceReporter: PerformanceReporter = PerformanceReporter()
) {

    private val classifierFactory =
        { project: Project ->
            ProjectFileClassifier(
                project.basePath ?: ""
            )
        }


    fun scan(project: Project): List<ProjectFile> = scanWithStats(project).files

    fun scanWithStats(project: Project): ProjectFileScanOutcome {
        lateinit var outcome: ProjectFileScanOutcome
        val report = performanceTimer.measure("Project File Scan") {
            val classifier = classifierFactory(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            val settings = project.getService(ProjectBrainSettings::class.java)
            val candidates = discovery.discover(project)
            val failures = mutableListOf<ProjectScanDiagnostic>()
            val analyzed = ArrayList<ProjectFile>(candidates.size)
            var skippedFiles = 0
            candidates.forEach { virtualFile ->
                ProgressManager.checkCanceled()
                if (!settings.allows(project, virtualFile, fileIndex)) {
                    skippedFiles++
                    return@forEach
                }
                try {
                    analyzed += classifier.classify(virtualFile).copy(
                        intelligence = fileIntelligence.analyze(project, virtualFile),
                        moduleName = fileIndex.getModuleForFile(virtualFile)?.name
                    )
                } catch (canceled: ProcessCanceledException) {
                    throw canceled
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (exception: Exception) {
                    failures += ProjectScanDiagnostic(
                        path = virtualFile.path,
                        message = "File metadata analysis failed: ${exception::class.java.simpleName}",
                        severity = DiagnosticSeverity.ERROR
                    )
                }
            }
            outcome = ProjectFileScanOutcome(
                files = deduplicator.removeDuplicates(analyzed),
                skippedFiles = skippedFiles,
                failures = failures
            )
            mapOf("project" to project.name, "files" to outcome.files.size,
                "skipped" to outcome.skippedFiles, "failed" to outcome.failures.size)
        }
        performanceReporter.report(report)
        return outcome
    }

    /** Refreshes only affected files. Directory and build-root changes are handled by a full scan upstream. */
    fun update(project: Project, previous: List<ProjectFile>, changedPaths: Set<String>): List<ProjectFile> =
        updateWithDiagnostics(project, previous, changedPaths).files

    /** An isolated corrupt/unreadable file is recorded as failed without stopping other changed files. */
    fun updateWithDiagnostics(
        project: Project, previous: List<ProjectFile>, changedPaths: Set<String>
    ): ProjectFileUpdateOutcome {
        if (changedPaths.isEmpty()) return ProjectFileUpdateOutcome(previous, emptyList())
        val fileIndex = ProjectFileIndex.getInstance(project)
        val settings = project.getService(ProjectBrainSettings::class.java)
        val filter = ProjectFileFilter()
        val classifier = classifierFactory(project)
        return reconcileFileChanges(previous, changedPaths) { path ->
            val file = LocalFileSystem.getInstance().findFileByPath(path)
                ?: return@reconcileFileChanges null
            if (!file.isValid || file.isDirectory || !fileIndex.isInContent(file) || fileIndex.isExcluded(file) ||
                !filter.shouldInclude(file) || !settings.allows(project, file, fileIndex)) {
                return@reconcileFileChanges null
            }
            classifier.classify(file).copy(
                intelligence = fileIntelligence.analyze(project, file),
                moduleName = fileIndex.getModuleForFile(file)?.name
            )
        }
    }

    /** Pure reconciliation boundary: test failure isolation, deletion and replacement deterministically. */
    internal fun reconcileFileChanges(
        previous: List<ProjectFile>, changedPaths: Set<String>, resolve: (String) -> ProjectFile?
    ): ProjectFileUpdateOutcome {
        val byPath = previous.associateByTo(LinkedHashMap()) { it.path }
        val failures = mutableListOf<ProjectScanDiagnostic>()
        for (path in changedPaths) {
            ProgressManager.checkCanceled()
            byPath.remove(path)
            try {
                resolve(path)?.let { byPath[it.path] = it }
            } catch (canceled: ProcessCanceledException) {
                throw canceled
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (exception: Exception) {
                failures += ProjectScanDiagnostic(
                    path = path,
                    message = "File metadata analysis failed: ${exception::class.java.simpleName}",
                    severity = DiagnosticSeverity.ERROR
                )
            }
        }
        return ProjectFileUpdateOutcome(byPath.values.sortedBy { it.relativePath }, failures)
    }
}
