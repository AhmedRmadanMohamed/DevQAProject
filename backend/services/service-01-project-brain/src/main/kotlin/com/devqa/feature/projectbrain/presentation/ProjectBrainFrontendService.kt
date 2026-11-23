package com.devqa.feature.projectbrain.presentation

import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.feature.projectbrain.application.ProjectAnalysisService
import com.devqa.feature.projectbrain.application.ProjectBrainModelService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class ProjectBrainFrontendService(
    private val project: Project
) {
    private val backend: ProjectAnalysisService
        get() = project.getService(ProjectAnalysisService::class.java)

    fun analyze(
        onCompleted: (ProjectAnalysisResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        backend.analyze(onCompleted, onError)
    }

    fun subscribe(
        disposable: Disposable,
        listener: (ProjectAnalysisResult) -> Unit,
        onIncrementalFailure: (String) -> Unit = {}
    ) {
        val connection = project.messageBus.connect(disposable)
        connection.subscribe(
            ProjectBrainModelService.TOPIC,
            ProjectBrainModelService.Listener { result, _ -> listener(result) }
        )
        connection.subscribe(
            ProjectBrainModelService.REFRESH_FAILURE_TOPIC,
            ProjectBrainModelService.RefreshFailureListener(onIncrementalFailure)
        )
    }

    fun latest(): ProjectAnalysisResult? =
        project.getService(ProjectBrainModelService::class.java).latest()

    fun openDashboard() {
        val initialResult = latest()
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFiles
            .filterIsInstance<DevQAAnalysisVirtualFile>()
            .forEach(editorManager::closeFile)

        OpenFileDescriptor(
            project,
            DevQAAnalysisVirtualFile(
                projectName = project.name,
                result = initialResult
            )
        ).navigate(true)
        ToolWindowManager.getInstance(project).getToolWindow("DevQA")?.hide()

        if (initialResult == null) {
            analyze(
                onCompleted = {},
                onError = {}
            )
        }
    }
}
