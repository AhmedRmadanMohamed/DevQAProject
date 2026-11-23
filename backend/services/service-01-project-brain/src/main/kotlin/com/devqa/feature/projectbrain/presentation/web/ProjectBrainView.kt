package com.devqa.feature.projectbrain.presentation.web

import com.devqa.core.model.ProjectAnalysisResult
import com.intellij.openapi.Disposable
import javax.swing.JComponent

/**
 * JCEF-independent contract used by the editor.
 *
 * Keeping this interface free of JCEF types allows the plugin to load on IDEs
 * where the optional JCEF capability is unavailable.
 */
interface ProjectBrainView : Disposable {
    val component: JComponent

    fun update(result: ProjectAnalysisResult)

    fun reportRefreshFailure(message: String)
}
