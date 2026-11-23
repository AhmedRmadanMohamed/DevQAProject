package com.devqa.feature.projectbrain.application

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.scanner.ProjectFileScanner
import com.devqa.core.scanner.ProjectScanner
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ProjectAnalysisService(
    private val project: Project
) : Disposable {
    private val projectFileScanner = ProjectFileScanner()
    private val disposed = AtomicBoolean(false)
    private val cancellationRequested = AtomicBoolean(false)
    private val runningIndicator = AtomicReference<ProgressIndicator?>()
    private val callbacks = mutableListOf<Pair<(ProjectAnalysisResult) -> Unit, (Throwable) -> Unit>>()


    fun analyze(
        onCompleted: (ProjectAnalysisResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (disposed.get()) return
        val modelService = project.getService(ProjectBrainModelService::class.java)
        synchronized(callbacks) {
            if (disposed.get() || project.isDisposed) return
            callbacks += onCompleted to onError
            // Multiple Analyze requests join the same running scan.
            if (!modelService.beginFullScan()) return
            cancellationRequested.set(false)
        }

        object : Task.Backgroundable(
            project,
            "DevQA Project Analysis",
            true
        ) {


            override fun run(
                indicator: ProgressIndicator
            ) {
                if (disposed.get()) return
                runningIndicator.set(indicator)
                if (cancellationRequested.get()) indicator.cancel()

                try {
                    indicator.checkCanceled()
                    val analysisStarted = System.nanoTime()

                    indicator.text =
                        "Scanning project structure..."


                    val result =
                        ReadAction.nonBlocking<ProjectAnalysisResult> {


                            indicator.text =
                                "Detecting project technologies..."


                            val scanResult =
                                ProjectScanner.scan(
                                    project
                                )


                            indicator.text =
                                "Analyzing project files..."


                            val fileOutcome = projectFileScanner.scanWithStats(project)


                            val elapsed = ((System.nanoTime() - analysisStarted) / 1_000_000L).coerceAtLeast(1L)
                            ProjectAnalysisResult(
                                scanResult = scanResult.copy(
                                    diagnostics = (scanResult.diagnostics + fileOutcome.failures).distinct(),
                                    metrics = scanResult.metrics.copy(
                                        durationMillis = elapsed,
                                        classesPerSecond = scanResult.totalClasses * 1_000L / elapsed,
                                        skippedFiles = fileOutcome.skippedFiles,
                                        failedFiles = fileOutcome.failures.size
                                    )
                                ),
                                files = fileOutcome.files
                            )
                        }
                            .inSmartMode(project)
                            .withDocumentsCommitted(project)
                            .wrapProgress(indicator)
                            .executeSynchronously()

                    if (disposed.get() || project.isDisposed) return
                    // Cancellation may arrive after the non-blocking read action finishes,
                    // but before its result is committed to the shared model.
                    indicator.checkCanceled()
                    if (cancellationRequested.get()) throw ProcessCanceledException()

                    val (storedResult, listeners) = synchronized(callbacks) {
                        indicator.checkCanceled()
                        if (cancellationRequested.get()) throw ProcessCanceledException()
                        val stored = modelService.finishFullScan(result)
                        stored to drainCallbacks()
                    }

                    if (disposed.get() || project.isDisposed) return

                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (!disposed.get() && !project.isDisposed) {
                                listeners.forEach { (done, _) -> done(storedResult) }
                            }
                        },
                        ModalityState.defaultModalityState()
                    )

                } catch (canceled: ProcessCanceledException) {
                    val listeners = synchronized(callbacks) {
                        modelService.failFullScan()
                        drainCallbacks()
                    }
                    notifyFailure(canceled, listeners)
                } catch (exception: Exception) {
                    val listeners = synchronized(callbacks) {
                        modelService.failFullScan()
                        drainCallbacks()
                    }
                    if (disposed.get() || project.isDisposed) return
                    notifyFailure(exception, listeners)
                } catch (error: LinkageError) {
                    // Optional IntelliJ/Kotlin integrations may be unavailable on a
                    // supported IDE build. Report that failure without swallowing
                    // fatal VM errors such as OutOfMemoryError or ThreadDeath.
                    val listeners = synchronized(callbacks) {
                        modelService.failFullScan()
                        drainCallbacks()
                    }
                    if (disposed.get() || project.isDisposed) return
                    notifyFailure(error, listeners)
                } finally {
                    runningIndicator.compareAndSet(indicator, null)
                }
            }
        }.queue()
    }

    fun cancel() {
        synchronized(callbacks) {
            cancellationRequested.set(true)
            runningIndicator.get()?.cancel()
        }
    }

    private fun drainCallbacks(): List<Pair<(ProjectAnalysisResult) -> Unit, (Throwable) -> Unit>> =
        callbacks.toList().also { callbacks.clear() }

    private fun notifyFailure(
        exception: Throwable,
        listeners: List<Pair<(ProjectAnalysisResult) -> Unit, (Throwable) -> Unit>>
    ) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed.get() && !project.isDisposed) {
                listeners.forEach { (_, failed) -> failed(exception) }
            }
        }, ModalityState.defaultModalityState())
    }

    override fun dispose() {
        disposed.set(true)
        runningIndicator.getAndSet(null)?.cancel()
        synchronized(callbacks) { callbacks.clear() }
    }
}
