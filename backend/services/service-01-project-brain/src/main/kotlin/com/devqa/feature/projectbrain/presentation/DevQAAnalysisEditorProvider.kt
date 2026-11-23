package com.devqa.feature.projectbrain.presentation

import com.devqa.feature.projectbrain.presentation.editor.DevQAAnalysisEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DevQAAnalysisEditorProvider :
    FileEditorProvider,
    DumbAware {

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file is DevQAAnalysisVirtualFile
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {

        return DevQAAnalysisEditor(
            project,
            file as DevQAAnalysisVirtualFile
        )
    }

    override fun getEditorTypeId(): String {
        return "devqa-analysis-editor"
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }
}
