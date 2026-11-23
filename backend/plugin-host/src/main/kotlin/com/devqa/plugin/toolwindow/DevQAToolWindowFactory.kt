package com.devqa.plugin.toolwindow

import com.devqa.feature.projectbrain.presentation.ProjectBrainFrontendService
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout

class DevQAToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val launcher = DashboardLauncher(project, toolWindow)
        val placeholder = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel("Opening DevQA Dashboard…").apply {
                horizontalAlignment = JBLabel.CENTER
            }, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(placeholder, null, false)
        content.setDisposer(launcher)
        toolWindow.contentManager.addContent(content)
        launcher.openDashboard()
    }

    private class DashboardLauncher(
        private val project: Project,
        private val toolWindow: ToolWindow
    ) : Disposable {
        private var opening = false

        init {
            project.messageBus.connect(this).subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowShown(shownToolWindow: ToolWindow) {
                        if (shownToolWindow === toolWindow) openDashboard()
                    }
                }
            )
        }

        fun openDashboard() {
            if (opening || project.isDisposed) return
            opening = true
            ToolWindowManager.getInstance(project).invokeLater {
                try {
                    if (!project.isDisposed) {
                        project.getService(ProjectBrainFrontendService::class.java).openDashboard()
                    }
                } finally {
                    opening = false
                }
            }
        }

        override fun dispose() = Unit
    }
}
