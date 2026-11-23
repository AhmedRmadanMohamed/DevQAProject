package com.devqa.feature.projectbrain.application

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectRelationship
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.scanner.ProjectFileScanner
import com.devqa.core.scanner.ProjectScanner
import com.devqa.feature.projectbrain.domain.ProjectDelta
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

@Service(Service.Level.PROJECT)
class ProjectBrainModelService(
    private val project: Project
) : Disposable {
    private val current = AtomicReference<ProjectAnalysisResult?>()
    private val fullScanRunning = AtomicBoolean(false)
    private val incrementalRunning = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val publicationLock = Any()
    private val pendingPaths = linkedSetOf<String>()
    private var pendingStructuralChange = false
    private var changeSequence = 0L
    @Volatile private var lastRefreshError: String? = null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val projectFileScanner = ProjectFileScanner()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (!project.getService(ProjectBrainSettings::class.java).getState().autoRefresh) return
                    val relevantPaths = events.flatMap(::affectedPaths).filter(::isRelevantChange)
                    if (relevantPaths.isNotEmpty()) {
                        val structural = events.any { event ->
                            isDirectoryChange(event) && affectedPaths(event).any(::isRelevantChange)
                        }
                        scheduleRefresh(relevantPaths, structural)
                    }
                }
            }
        )
    }

    fun beginFullScan(): Boolean = !disposed.get() && fullScanRunning.compareAndSet(false, true)

    fun finishFullScan(result: ProjectAnalysisResult): ProjectAnalysisResult {
        val published: ProjectAnalysisResult
        val preceding: ProjectAnalysisResult?
        synchronized(publicationLock) {
            preceding = current.get()
            val version = (preceding?.scanResult?.modelVersion ?: 0L) + 1L
            published = result.copy(scanResult = result.scanResult.copy(modelVersion = version))
            current.set(published)
            lastRefreshError = null
            fullScanRunning.set(false)
        }
        publish(published, preceding, emptySet())
        requestRefreshIfPending()
        return published
    }

    fun failFullScan() {
        fullScanRunning.set(false)
        requestRefreshIfPending()
    }

    /** Kept for existing clients; full analysis should normally call finishFullScan. */
    fun replace(result: ProjectAnalysisResult): ProjectAnalysisResult = synchronized(publicationLock) {
        val version = (current.get()?.scanResult?.modelVersion ?: 0L) + 1L
        result.copy(scanResult = result.scanResult.copy(modelVersion = version)).also(current::set)
    }

    fun latest(): ProjectAnalysisResult? = current.get()

    /** A failed background update never replaces the last successfully published model. */
    fun lastIncrementalRefreshError(): String? = lastRefreshError

    /** Stable read-only API for future DevQA services; no additional repository scan is triggered. */
    fun component(id: String): ProjectClass? = latest()?.scanResult?.allClasses?.firstOrNull { it.id == id }

    fun file(path: String): ProjectFile? = latest()?.files?.let { files ->
        files.firstOrNull { it.path == path } ?: files.filter { it.relativePath == path }.singleOrNull()
    }

    fun frameworks(): FrameworkInventory? = latest()?.scanResult?.frameworkInventory

    fun dependencies(qualifiedName: String, incoming: Boolean = false): List<ProjectRelationship> =
        latest()?.scanResult?.relationships?.filter {
            if (incoming) it.targetQualifiedName == qualifiedName else it.sourceQualifiedName == qualifiedName
        }.orEmpty()

    /** Exact class identity avoids merging similarly named classes in different modules. */
    fun dependenciesForClassId(classId: String, incoming: Boolean = false): List<ProjectRelationship> =
        latest()?.scanResult?.relationships?.filter {
            if (incoming) it.targetClassId == classId else it.sourceClassId == classId
        }.orEmpty()

    /** Reverse dependency traversal, bounded to avoid expensive full-graph exploration. */
    fun impact(qualifiedName: String, depth: Int = 2, limit: Int = 500): Set<String> {
        val scan = latest()?.scanResult ?: return emptySet()
        val classes = scan.allClasses
        val matches = classes.filter { it.id == qualifiedName || it.qualifiedName == qualifiedName }
        val source = matches.singleOrNull() ?: return emptySet()
        val byId = classes.associateBy { it.id }
        return traverseImpact(scan, source.id, depth, limit).mapNotNullTo(linkedSetOf()) { id ->
            byId[id]?.qualifiedName ?: byId[id]?.name
        }
    }

    fun impactClassIds(classId: String, depth: Int = 2, limit: Int = 500): Set<String> {
        val model = latest()?.scanResult ?: return emptySet()
        return traverseImpact(model, classId, depth, limit)
    }

    private fun traverseImpact(model: ProjectScanResult, classId: String, depth: Int, limit: Int): Set<String> {
        if (model.allClasses.none { it.id == classId }) return emptySet()
        val reverse = model.relationships.filter { it.targetClassId != null }.groupBy { it.targetClassId }
        val maximum = limit.coerceIn(1, 1_000)
        val seen = linkedSetOf(classId)
        var frontier = setOf(classId)
        repeat(depth.coerceIn(0, 5)) {
            val next = linkedSetOf<String>()
            for (target in frontier) {
                for (edge in reverse[target].orEmpty()) {
                    if (seen.size + next.size >= maximum) break
                    if (edge.sourceClassId !in seen) next += edge.sourceClassId
                }
                if (seen.size + next.size >= maximum) break
            }
            seen += next
            frontier = next
        }
        return seen
    }

    fun query(requestJson: String): String = ProjectBrainQueryService.query(latest(), requestJson)

    private data class RefreshWork(val paths: Set<String>, val structural: Boolean, val sequence: Long)

    private fun affectedPaths(event: VFileEvent): List<String> = when (event) {
        is VFileMoveEvent -> listOf(event.oldPath, event.path)
        is VFileCopyEvent -> listOf("${event.newParent.path}/${event.newChildName}")
        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME)
            listOf(event.oldPath, event.path) else listOf(event.path)
        else -> listOf(event.path)
    }

    private fun isDirectoryChange(event: VFileEvent): Boolean = when (event) {
        is VFileCreateEvent -> event.isDirectory
        is VFileDeleteEvent, is VFileMoveEvent, is VFileCopyEvent -> event.file?.isDirectory == true
        is VFilePropertyChangeEvent -> event.propertyName == VirtualFile.PROP_NAME && event.file.isDirectory
        else -> false
    }

    private fun scheduleRefresh(paths: List<String>, structural: Boolean = false) {
        if (disposed.get() || project.isDisposed) return
        synchronized(pendingPaths) {
            for (path in paths) {
                if (pendingPaths.size >= MAX_PENDING_PATHS && path !in pendingPaths) {
                    pendingStructuralChange = true
                    break
                }
                pendingPaths += path
            }
            pendingStructuralChange = pendingStructuralChange || structural
            changeSequence++
        }
        requestRefreshIfPending()
    }

    private fun requestRefreshIfPending() {
        if (disposed.get() || project.isDisposed || fullScanRunning.get() || current.get() == null) return
        if (synchronized(pendingPaths) { pendingPaths.isEmpty() }) return
        synchronized(alarm) {
            alarm.cancelAllRequests()
            alarm.addRequest({ refreshPendingPaths() }, REFRESH_DELAY_MS)
        }
    }

    private fun refreshPendingPaths() {
        if (disposed.get() || project.isDisposed || fullScanRunning.get() || current.get() == null) return
        if (!incrementalRunning.compareAndSet(false, true)) return
        val work = synchronized(pendingPaths) {
            RefreshWork(pendingPaths.toSet(), pendingStructuralChange, changeSequence).also {
                pendingPaths.clear()
                pendingStructuralChange = false
            }
        }
        if (work.paths.isEmpty()) {
            incrementalRunning.set(false)
            return
        }
        val previous = current.get() ?: run {
            requeue(work)
            incrementalRunning.set(false)
            return
        }

        val promise = ReadAction.nonBlocking<ProjectAnalysisResult> {
            val complete = work.structural || ProjectScanner.requiresFullScan(work.paths)
            val updatedScan = ProjectScanner.rescan(project, previous.scanResult, work.paths, complete)
            val fileOutcome = if (complete) projectFileScanner.scanWithStats(project) else null
            val incrementalFiles = if (complete) null else
                projectFileScanner.updateWithDiagnostics(project, previous.files, work.paths)
            val updatedFiles = fileOutcome?.files ?: requireNotNull(incrementalFiles).files
            val newDiagnostics = if (complete) {
                updatedScan.diagnostics + fileOutcome?.failures.orEmpty()
            } else {
                updatedScan.diagnostics.filterNot { diagnostic ->
                    diagnostic.path in work.paths && diagnostic.message.startsWith("File metadata analysis failed:")
                } + incrementalFiles?.failures.orEmpty()
            }
            previous.copy(
                scanResult = updatedScan.copy(
                    diagnostics = newDiagnostics.distinct(),
                    metrics = if (fileOutcome == null) updatedScan.metrics.copy(
                        skippedFiles = previous.scanResult.metrics.skippedFiles,
                        failedFiles = newDiagnostics.count { it.message.startsWith("File metadata analysis failed:") }
                    ) else updatedScan.metrics.copy(
                        skippedFiles = fileOutcome.skippedFiles,
                        failedFiles = fileOutcome.failures.size
                    )
                ),
                files = updatedFiles
            )
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { updated ->
                try {
                    val published = synchronized(publicationLock) {
                        synchronized(pendingPaths) {
                            if (disposed.get() || fullScanRunning.get() || current.get() !== previous ||
                                changeSequence != work.sequence) null
                            else updated.copy(
                                scanResult = updated.scanResult.copy(modelVersion = previous.scanResult.modelVersion + 1)
                            ).also(current::set)
                        }
                    }
                    if (published == null) requeue(work)
                    else {
                        lastRefreshError = null
                        publish(published, previous, work.paths)
                    }
                } finally {
                    incrementalRunning.set(false)
                    requestRefreshIfPending()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        promise.onError { failure ->
            if (!disposed.get() && !project.isDisposed) {
                val canceled = failure is ProcessCanceledException || failure is CancellationException
                if (!canceled) {
                    val message = "Incremental analysis failed: ${failure::class.java.simpleName}"
                    lastRefreshError = message
                    // Throwable messages and stack traces can contain source snippets, paths,
                    // or project credentials. Never forward raw exceptions to IDE logs.
                    LOG.warn(message)
                    reportRefreshFailure(message)
                }
                val newerChange = synchronized(pendingPaths) { changeSequence != work.sequence }
                requeue(work)
                incrementalRunning.set(false)
                // Recover from IDE read-action cancellation; avoid infinite retries for hard failures.
                if (canceled || newerChange) requestRefreshIfPending()
            } else incrementalRunning.set(false)
        }
    }

    private fun requeue(work: RefreshWork) {
        synchronized(pendingPaths) {
            for (path in work.paths) {
                if (pendingPaths.size >= MAX_PENDING_PATHS && path !in pendingPaths) {
                    pendingStructuralChange = true
                    break
                }
                pendingPaths += path
            }
            pendingStructuralChange = pendingStructuralChange || work.structural
        }
    }

    private fun reportRefreshFailure(message: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed.get() && !project.isDisposed && lastRefreshError == message) {
                project.messageBus.syncPublisher(REFRESH_FAILURE_TOPIC).refreshFailed(message)
            }
        }, ModalityState.defaultModalityState())
    }

    private fun publish(result: ProjectAnalysisResult, previous: ProjectAnalysisResult?, paths: Set<String>) {
        // Diffing thousands of symbols allocates maps/sets; never do it on the EDT.
        // The identity guard also stops a slow diff from publishing a superseded model.
        val prepare = {
            if (!project.isDisposed && !disposed.get() && current.get() === result) {
                val change = if (previous == null)
                    ProjectDelta(0, result.scanResult.modelVersion, paths, emptySet(), emptySet(), emptySet())
                else delta(previous, result, paths)
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed && !disposed.get() && current.get() === result) {
                        project.messageBus.syncPublisher(TOPIC).modelUpdated(result, change)
                    }
                }, ModalityState.defaultModalityState())
            }
        }
        // Always asynchronous, including when called inside a full-scan completion lock.
        AppExecutorUtil.getAppExecutorService().execute(prepare)
    }

    private fun delta(
        previous: ProjectAnalysisResult,
        updated: ProjectAnalysisResult,
        paths: Set<String>
    ): ProjectDelta {
        val oldClasses = previous.scanResult.allClasses.associateBy { it.id }
        val newClasses = updated.scanResult.allClasses.associateBy { it.id }
        val sharedIds = oldClasses.keys intersect newClasses.keys
        return ProjectDelta(
            fromVersion = previous.scanResult.modelVersion,
            toVersion = updated.scanResult.modelVersion,
            changedPaths = paths,
            addedClassIds = newClasses.keys - oldClasses.keys,
            removedClassIds = oldClasses.keys - newClasses.keys,
            updatedClassIds = sharedIds.filterTo(linkedSetOf()) { oldClasses[it] != newClasses[it] }
        )
    }

    private fun isRelevantChange(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        val root = project.basePath?.replace('\\', '/')?.trimEnd('/')?.lowercase() ?: return false
        if (!normalized.startsWith("$root/")) return false
        val relativePath = normalized.removePrefix("$root/")
        // Project Files should also refresh on changes to documentation, deployment, XML, etc.
        // Ignore repository metadata and build artifacts to avoid noisy refresh storms.
        return relativePath.split('/').none { it in ignoredChangeDirectories }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(alarm) { alarm.cancelAllRequests() }
        synchronized(pendingPaths) {
            pendingPaths.clear()
            pendingStructuralChange = false
        }
        current.set(null)
    }

    fun interface Listener {
        fun modelUpdated(result: ProjectAnalysisResult, delta: ProjectDelta)
    }

    fun interface RefreshFailureListener {
        fun refreshFailed(message: String)
    }

    companion object {
        private const val REFRESH_DELAY_MS = 500
        private const val MAX_PENDING_PATHS = 1_000
        private val LOG = Logger.getInstance(ProjectBrainModelService::class.java)

        private val ignoredChangeDirectories = setOf(
            ".git", ".idea", ".gradle", "build", "target", "out", "node_modules",
            "vendor", "dist", ".next", "coverage"
        )

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("DevQA Project Brain updates", Listener::class.java)

        @JvmField
        val REFRESH_FAILURE_TOPIC: Topic<RefreshFailureListener> =
            Topic.create("DevQA Project Brain refresh failures", RefreshFailureListener::class.java)
    }
}
