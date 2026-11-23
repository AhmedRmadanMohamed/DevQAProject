package com.devqa.feature.projectbrain.presentation.editor

import com.devqa.feature.projectbrain.presentation.DevQAAnalysisVirtualFile
import com.devqa.feature.projectbrain.presentation.ProjectBrainFrontendService
import com.devqa.feature.projectbrain.presentation.web.FrontendUnavailablePanel
import com.devqa.feature.projectbrain.presentation.web.JcefUnavailableException
import com.devqa.feature.projectbrain.presentation.web.ProjectBrainView
import com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebViewFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class DevQAAnalysisEditor(
    private val project: Project,
    private val analysisFile: DevQAAnalysisVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val projectName = analysisFile.name.removePrefix("DevQA Dashboard - ")
    private val content = JPanel(BorderLayout())
    private val browserStartupAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var latestResult = analysisFile.result
    private var webView: ProjectBrainView? = null
    private var webFrontendReady = false
    private var closed = false

    init {
        startFrontend()
        project.getService(ProjectBrainFrontendService::class.java).subscribe(this, ::render) { message ->
            webView?.reportRefreshFailure(message)
        }
    }

    private fun render(result: com.devqa.core.model.ProjectAnalysisResult) {
        if (closed || project.isDisposed) return
        latestResult = result
        webView?.update(result)
    }

    private fun startFrontend() {
        if (closed || project.isDisposed) return
        browserStartupAlarm.cancelAllRequests()
        webView?.dispose()
        webView = null
        webFrontendReady = false
        content.removeAll()

        // Resolve JCEF lazily. Before 2025.3.1 it is part of the IDE runtime;
        // from 2026.2 it is supplied by the optional Web Browser (JCEF) plugin.
        // Keeping all JCEF class loading behind the factory lets the plugin itself
        // load and show a useful fallback when that optional capability is absent.
        val candidate = try {
            ProjectBrainWebViewFactory.create(
                project = project,
                projectName = projectName,
                initialResult = latestResult,
                onFrontendReady = ::markWebFrontendReady,
                onFrontendError = ::showFrontendUnavailable
            )
        } catch (error: Exception) {
            val message = when (error) {
                is JcefUnavailableException, is ClassNotFoundException ->
                    "JCEF is not available in this IntelliJ runtime."
                else ->
                    "The DevQA frontend could not start (${error::class.java.simpleName}). Retry opening the dashboard."
            }
            showFrontendUnavailable(message)
            return
        } catch (_: LinkageError) {
            showFrontendUnavailable("JCEF is not available in this IntelliJ runtime.")
            return
        }

        webView = candidate
        content.add(candidate.component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        browserStartupAlarm.addRequest(
            { if (!webFrontendReady) showFrontendUnavailable("The DevQA frontend did not finish loading.") },
            WEB_FRONTEND_STARTUP_TIMEOUT_MS
        )
    }

    private fun markWebFrontendReady() {
        if (closed || project.isDisposed) return
        webFrontendReady = true
        browserStartupAlarm.cancelAllRequests()
    }

    private fun showFrontendUnavailable(message: String) {
        if (closed || project.isDisposed) return
        browserStartupAlarm.cancelAllRequests()
        webView?.dispose()
        webView = null
        content.removeAll()
        content.add(FrontendUnavailablePanel(message, ::startFrontend), BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    override fun getComponent(): JComponent = content
    override fun getPreferredFocusedComponent(): JComponent = content
    override fun getName(): String = "DevQA Dashboard"
    override fun getFile(): DevQAAnalysisVirtualFile = analysisFile
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = analysisFile.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        if (closed) return
        closed = true
        browserStartupAlarm.cancelAllRequests()
        webView?.dispose()
        webView = null
    }

    companion object {
        private const val WEB_FRONTEND_STARTUP_TIMEOUT_MS = 12_000
    }
}
