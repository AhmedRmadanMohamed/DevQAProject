package com.devqa.feature.projectbrain.presentation.web

import com.devqa.core.model.ProjectAnalysisResult
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationTargetException

/**
 * Loads the JCEF implementation only after checking that the running IDE provides JCEF.
 *
 * JCEF was part of the IDE runtime on older supported IDE builds. Starting with 2026.2,
 * it is supplied by the separate bundled Web Browser (JCEF) plugin. Reflection keeps
 * the editor's normal class-loading path independent of that optional plugin.
 */
object ProjectBrainWebViewFactory {
    private const val JCEF_APP_CLASS = "com.intellij.ui.jcef.JBCefApp"
    private const val WEB_VIEW_CLASS =
        "com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebView"

    fun create(
        project: Project,
        projectName: String,
        initialResult: ProjectAnalysisResult?,
        onFrontendReady: () -> Unit,
        onFrontendError: (String) -> Unit
    ): ProjectBrainView {
        val classLoader = ProjectBrainWebViewFactory::class.java.classLoader
        val jcefAppClass = try {
            Class.forName(JCEF_APP_CLASS, false, classLoader)
        } catch (_: ClassNotFoundException) {
            throw JcefUnavailableException()
        }

        val isSupported = try {
            jcefAppClass.getMethod("isSupported")
        } catch (_: NoSuchMethodException) {
            throw JcefUnavailableException()
        }

        val supported = try {
            isSupported.invoke(null) as? Boolean ?: false
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        if (!supported) throw JcefUnavailableException()

        val implementation = Class.forName(WEB_VIEW_CLASS, true, classLoader)
        val constructor = implementation.constructors.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            parameters.size == 5 &&
                parameters[0] == Project::class.java &&
                parameters[1] == String::class.java &&
                parameters[2] == ProjectAnalysisResult::class.java
        } ?: throw IllegalStateException("Project Brain JCEF view constructor is unavailable")

        return try {
            constructor.newInstance(
                project,
                projectName,
                initialResult,
                onFrontendReady,
                onFrontendError
            ) as? ProjectBrainView
                ?: throw IllegalStateException("Project Brain JCEF view does not implement its view contract")
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}

class JcefUnavailableException : RuntimeException()
