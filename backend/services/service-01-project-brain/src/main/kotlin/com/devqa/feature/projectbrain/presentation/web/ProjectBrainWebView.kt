package com.devqa.feature.projectbrain.presentation.web

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.feature.projectbrain.presentation.ProjectBrainFrontendService
import com.devqa.feature.projectbrain.application.ProjectBrainQueryService
import com.devqa.feature.projectbrain.application.ProjectAnalysisService
import com.devqa.feature.projectbrain.application.ProjectBrainSettings
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefLoadHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

class ProjectBrainWebView(
    private val project: Project,
    private val projectName: String,
    initialResult: ProjectAnalysisResult?,
    private val onFrontendReady: () -> Unit = {},
    private val onFrontendError: (String) -> Unit = {}
) : ProjectBrainView {
    private val browser = JBCefBrowser()
    private val analyzeQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val disposed = AtomicBoolean(false)
    private val frontendReady = AtomicBoolean(false)
    private val requestedVersion = AtomicLong(initialResult?.scanResult?.modelVersion ?: Long.MIN_VALUE)
    private val gson = Gson()
    private var pageReady = false
    private var pendingJson: String? = null
    private var pendingError: String? = null

    private data class SourceNavigationTarget(val file: VirtualFile, val offset: Int)

    override val component: JComponent
        get() = browser.component

    init {
        analyzeQuery.addHandler { request ->
            try {
                when {
                disposed.get() || project.isDisposed ->
                    JBCefJSQuery.Response("", 410, "Project Brain view is closed")
                request.length > MAX_BRIDGE_REQUEST_LENGTH ->
                    JBCefJSQuery.Response("", 413, "Project Brain request exceeds size limit")
                request.startsWith(ANALYZE_REQUEST) -> {
                    val requestId = request.removePrefix(ANALYZE_REQUEST).take(100)
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed) analyzeProject(requestId)
                    }
                    JBCefJSQuery.Response("{\"type\":\"ack\",\"accepted\":true}")
                }
                request == CANCEL_REQUEST -> {
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed) {
                            try {
                                project.getService(ProjectAnalysisService::class.java).cancel()
                            } catch (error: RuntimeException) {
                                executeJavaScript(
                                    "window.receiveDevQAProjectBrainError(${jsonString("Analysis cancellation failed (${error::class.java.simpleName}).")});"
                                )
                            } catch (_: LinkageError) {
                                executeJavaScript(
                                    "window.receiveDevQAProjectBrainError(${jsonString("Analysis cancellation is unavailable in this IDE runtime.")});"
                                )
                            }
                        }
                    }
                    JBCefJSQuery.Response("{\"type\":\"ack\",\"accepted\":true}")
                }
                request == GET_SETTINGS_REQUEST -> {
                    val settings = project.getService(ProjectBrainSettings::class.java)
                    JBCefJSQuery.Response(gson.toJson(settings.getState()))
                }
                request.startsWith(SET_SETTINGS_REQUEST) -> {
                    try {
                        val options = gson.fromJson(
                            request.removePrefix(SET_SETTINGS_REQUEST), ProjectBrainSettings.Options::class.java
                        )
                        requireNotNull(options) { "Missing scan configuration" }
                        project.getService(ProjectBrainSettings::class.java).loadState(options)
                        JBCefJSQuery.Response("{\"accepted\":true}")
                    } catch (_: RuntimeException) {
                        // Do not send arbitrary deserialization/validation exception text to JCEF.
                        JBCefJSQuery.Response("", 400, "Invalid scan configuration")
                    }
                }
                request.startsWith(QUERY_REQUEST) -> {
                    try {
                        JBCefJSQuery.Response(ProjectBrainQueryService.query(
                            project.getService(com.devqa.feature.projectbrain.application.ProjectBrainModelService::class.java).latest(),
                            request.removePrefix(QUERY_REQUEST)
                        ))
                    } catch (_: RuntimeException) {
                        JBCefJSQuery.Response("", 400, "Invalid or unsupported Project Brain query")
                    }
                }
                request == FRONTEND_READY_REQUEST -> {
                    if (frontendReady.compareAndSet(false, true)) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed.get() && !project.isDisposed) {
                                try {
                                    onFrontendReady()
                                } catch (error: RuntimeException) {
                                    reportFrontendError(
                                        "DevQA frontend initialization failed (${error::class.java.simpleName}). Reopen the dashboard to retry."
                                    )
                                } catch (_: LinkageError) {
                                    reportFrontendError("DevQA frontend initialization is unavailable in this IDE runtime.")
                                }
                            }
                        }
                    }
                    JBCefJSQuery.Response("accepted")
                }
                request.startsWith(OPEN_SOURCE_REQUEST) -> {
                    val parts = request.removePrefix(OPEN_SOURCE_REQUEST).split('\n', limit = 2)
                    val path = parts.firstOrNull().orEmpty()
                    val offsetText = parts.getOrNull(1)
                    val offset = offsetText?.takeIf(String::isNotEmpty)?.toIntOrNull()
                    if (path.isBlank() || path.length > MAX_SOURCE_PATH_LENGTH || '\u0000' in path ||
                        (offsetText != null && offsetText.isNotEmpty() && offset == null)) {
                        JBCefJSQuery.Response("", 400, "Invalid source navigation request")
                    } else {
                        openSource(path, offset ?: 0)
                        JBCefJSQuery.Response("{\"type\":\"ack\",\"accepted\":true}")
                    }
                }
                else -> JBCefJSQuery.Response("", 400, "Unsupported Project Brain action")
                }
            } catch (error: RuntimeException) {
                JBCefJSQuery.Response("", 500, "Project Brain request failed (${error::class.java.simpleName})")
            } catch (_: LinkageError) {
                JBCefJSQuery.Response("", 503, "Project Brain runtime is unavailable")
            }
        }

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain || disposed.get() || project.isDisposed) return
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed) {
                            pageReady = true
                            flushSnapshot()
                        }
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String
                ) {
                    if (!frame.isMain || disposed.get() || project.isDisposed) return
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed) {
                            // Loader text can contain URLs or local paths; use a safe diagnostic.
                            reportFrontendError("DevQA frontend failed to load ($errorCode). Reopen the dashboard to retry.")
                        }
                    }
                }
            },
            browser.cefBrowser
        )
        browser.setOpenLinksInExternalBrowser(true)
        browser.loadHTML(
            ProjectBrainWebAssets.html(createBridgeScript()),
            PAGE_URL
        )
        initialResult?.let(::update)
    }

    override fun update(result: ProjectAnalysisResult) {
        if (disposed.get() || project.isDisposed) return
        val version = result.scanResult.modelVersion
        requestedVersion.updateAndGet { current -> maxOf(current, version) }
        try {
            AppExecutorUtil.getAppExecutorService().execute {
                // Incremental updates may arrive faster than a large summary can serialize.
                // Skip superseded requests before doing any expensive facet/model work.
                if (disposed.get() || project.isDisposed || version < requestedVersion.get()) return@execute
                val json = try {
                    ProjectBrainWebSnapshotMapper.toJson(projectName, result)
                } catch (error: RuntimeException) {
                    val message = "Failed to prepare Project Brain data (${error::class.java.simpleName}). The last valid model is retained."
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed && version >= requestedVersion.get()) {
                            pendingError = message
                            flushSnapshot()
                        }
                    }
                    return@execute
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed.get() && !project.isDisposed && version >= requestedVersion.get()) {
                        pendingJson = json
                        pendingError = null
                        flushSnapshot()
                    }
                }
            }
        } catch (error: RuntimeException) {
            reportRefreshFailure(
                "Project Brain update could not be scheduled (${error::class.java.simpleName}). The last valid model is retained."
            )
        }
    }

    /** Surface incremental analysis failures while keeping the last valid snapshot on screen. */
    override fun reportRefreshFailure(message: String) {
        if (disposed.get() || project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (!disposed.get() && !project.isDisposed) {
                pendingError = message
                flushSnapshot()
            }
        })
    }

    private fun analyzeProject(requestId: String) {
        if (project.isDisposed || disposed.get()) return
        try {
            executeJavaScript(
                "window.receiveDevQAProjectBrainProgress({requestId:${jsonString(requestId)},status:'RUNNING'});"
            )
            project.getService(ProjectBrainFrontendService::class.java).analyze(
                onCompleted = ::update,
                onError = callback@{ error ->
                    if (disposed.get() || project.isDisposed) return@callback
                    if (error is ProcessCanceledException || error is java.util.concurrent.CancellationException) {
                        executeJavaScript("window.receiveDevQAProjectBrainProgress({status:'CANCELED'});")
                    } else {
                        // Never echo arbitrary exception text, which may contain secrets from a project file.
                        val message = "Analysis failed (${error::class.java.simpleName}). The last valid model is retained."
                        executeJavaScript("window.receiveDevQAProjectBrainError(${jsonString(message)});")
                    }
                }
            )
        } catch (error: RuntimeException) {
            executeJavaScript(
                "window.receiveDevQAProjectBrainError(${jsonString("Analysis could not start (${error::class.java.simpleName}).")});"
            )
        } catch (_: LinkageError) {
            executeJavaScript(
                "window.receiveDevQAProjectBrainError(${jsonString("Analysis runtime is unavailable. Reopen the dashboard to retry.")});"
            )
        }
    }

    /**
     * Source navigation is deliberately split into three phases:
     * 1. The JCEF bridge only validates the request and schedules background work.
     * 2. VFS and ProjectFileIndex access run in a cancellable smart-mode read action.
     * 3. Only OpenFileDescriptor navigation runs on the EDT.
     *
     * This prevents workspace-model refreshes from triggering SlowOperations on EDT.
     */
    private fun openSource(path: String, offset: Int) {
        if (project.isDisposed || disposed.get()) return
        if (path.isBlank() || path.length > MAX_SOURCE_PATH_LENGTH || '\u0000' in path) {
            notifySourceNavigationError("The selected source path is invalid.")
            return
        }

        try {
            // Even scheduling the non-blocking read action happens off the JCEF callback thread.
            AppExecutorUtil.getAppExecutorService().execute {
                if (project.isDisposed || disposed.get()) return@execute
                try {
                    ReadAction.nonBlocking<SourceNavigationTarget?> {
                        if (project.isDisposed || disposed.get()) return@nonBlocking null
                        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return@nonBlocking null
                        val index = ProjectFileIndex.getInstance(project)
                        if (!file.isValid || file.isDirectory || !index.isInContent(file) || index.isExcluded(file)) {
                            return@nonBlocking null
                        }
                        val maxOffset = file.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        SourceNavigationTarget(file, offset.coerceAtLeast(0).coerceAtMost(maxOffset))
                    }
                        .inSmartMode(project)
                        .expireWith(this)
                        .coalesceBy(this)
                        .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                            if (disposed.get() || project.isDisposed) return@finishOnUiThread
                            if (target == null) {
                                notifySourceNavigationError(
                                    "The selected source file is not available in the current project or is excluded."
                                )
                                return@finishOnUiThread
                            }
                            try {
                                val descriptor = OpenFileDescriptor(project, target.file, target.offset)
                                if (!descriptor.canNavigate()) {
                                    notifySourceNavigationError("IntelliJ could not open the selected source file.")
                                } else {
                                    descriptor.navigate(true)
                                }
                            } catch (error: RuntimeException) {
                                notifySourceNavigationError(
                                    "Source navigation failed (${error::class.java.simpleName})."
                                )
                            } catch (_: LinkageError) {
                                notifySourceNavigationError("IntelliJ source navigation is unavailable.")
                            }
                        }
                        .submit(AppExecutorUtil.getAppExecutorService())
                        .onError { error ->
                            if (error !is ProcessCanceledException &&
                                error !is java.util.concurrent.CancellationException &&
                                !disposed.get() && !project.isDisposed) {
                                notifySourceNavigationError(
                                    "Source navigation failed (${error::class.java.simpleName})."
                                )
                            }
                        }
                } catch (error: RuntimeException) {
                    if (!disposed.get() && !project.isDisposed) {
                        notifySourceNavigationError("Source navigation failed (${error::class.java.simpleName}).")
                    }
                } catch (_: LinkageError) {
                    if (!disposed.get() && !project.isDisposed) {
                        notifySourceNavigationError("IntelliJ source navigation is unavailable.")
                    }
                }
            }
        } catch (error: RuntimeException) {
            notifySourceNavigationError("Source navigation could not be scheduled (${error::class.java.simpleName}).")
        }
    }

    private fun notifySourceNavigationError(message: String) {
        if (disposed.get() || project.isDisposed) return
        executeJavaScript("window.receiveDevQASourceNavigationError(${jsonString(message)});")
    }

    private fun reportFrontendError(message: String) {
        if (disposed.get() || project.isDisposed) return
        try {
            onFrontendError(message)
        } catch (_: RuntimeException) {
            // Reporting must never destabilize the IDE event thread.
        } catch (_: LinkageError) {
            // Optional IDE integrations can be unavailable during shutdown or in reduced runtimes.
        }
    }

    private fun flushSnapshot() {
        if (!pageReady || disposed.get()) return
        val json = pendingJson
        pendingJson = null
        if (json != null) {
            executeJavaScript("window.receiveDevQAProjectBrainSnapshot($json);")
        }
        // On a subsequent serialization failure, show the last valid model first
        // and then its stale/error indication; the error must remain visible.
        pendingError?.let { message ->
            pendingError = null
            executeJavaScript("window.receiveDevQAProjectBrainError(${jsonString(message)});")
        }
    }

    /**
     * Execute browser-side updates on the IDE event thread and ignore callbacks
     * that arrive after the project or embedded browser has been disposed.
     */
    private fun executeJavaScript(script: String) {
        if (disposed.get() || project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            try {
                browser.cefBrowser.executeJavaScript(script, PAGE_URL, 0)
            } catch (error: RuntimeException) {
                reportFrontendError(
                    "DevQA frontend update failed (${error::class.java.simpleName}). Reopen the dashboard to retry."
                )
            } catch (_: LinkageError) {
                reportFrontendError("DevQA frontend runtime is unavailable. Reopen the dashboard to retry.")
            }
        }
    }

    private fun createBridgeScript(): String {
        val analyzeRequest = jsonString(ANALYZE_REQUEST)
        val queryRequest = jsonString(QUERY_REQUEST)
        val cancelRequest = jsonString(CANCEL_REQUEST)
        val getSettingsRequest = jsonString(GET_SETTINGS_REQUEST)
        val setSettingsRequest = jsonString(SET_SETTINGS_REQUEST)
        val frontendReadyRequest = jsonString(FRONTEND_READY_REQUEST)
        val openSourceRequest = jsonString(OPEN_SOURCE_REQUEST)
        val lineSeparator = jsonString("\n")
        val injectedQuery = analyzeQuery.inject(
            "request",
            "function(response) { resolve(response); }",
            "function(code, message) { reject(new Error(message || ('Bridge error ' + code))); }"
        )
        return """
            window.DevQAIntellijBridge = Object.freeze({
              request: function(request) {
                return new Promise(function(resolve, reject) {
                  $injectedQuery
                });
              },
              analyzeProject: function(requestId) {
                return this.request($analyzeRequest + String(requestId || ''));
              },
              cancelAnalysis: function() {
                return this.request($cancelRequest);
              },
              readSettings: function() {
                return this.request($getSettingsRequest);
              },
              writeSettings: function(options) {
                return this.request($setSettingsRequest + JSON.stringify(options));
              },
              query: function(payload) {
                return this.request($queryRequest + JSON.stringify(payload));
              },
              frontendReady: function() {
                return this.request($frontendReadyRequest);
              },
              openSource: function(path, offset) {
                return this.request($openSourceRequest + String(path || '') + $lineSeparator + String(offset || 0));
              }
            });
        """.trimIndent()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        pageReady = false
        pendingJson = null
        pendingError = null
        analyzeQuery.dispose()
        browser.dispose()
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    companion object {
        private const val PAGE_URL = "https://devqa.local/project-brain"
        private const val MAX_BRIDGE_REQUEST_LENGTH = 32_768
        private const val MAX_SOURCE_PATH_LENGTH = 4_096
        private const val ANALYZE_REQUEST = "analyze-project\n"
        private const val QUERY_REQUEST = "query\n"
        private const val CANCEL_REQUEST = "cancel-analysis"
        private const val GET_SETTINGS_REQUEST = "get-scan-settings"
        private const val SET_SETTINGS_REQUEST = "set-scan-settings\n"
        private const val FRONTEND_READY_REQUEST = "frontend-ready"
        private const val OPEN_SOURCE_REQUEST = "open-source\n"
    }
}
