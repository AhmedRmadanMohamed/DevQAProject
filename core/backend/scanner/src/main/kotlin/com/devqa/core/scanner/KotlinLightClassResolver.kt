package com.devqa.core.scanner

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.psi.KtClassOrObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CancellationException

/**
 * Bridges the Kotlin light-class API versions used by the supported IDE range.
 *
 * IntelliJ 2025.1 exposes getLightClass(KtClassOrObject), while newer Kotlin
 * plugins expose the scope-aware overload. Prefer that overload when present.
 * Reflection keeps the plugin bytecode independent of the removed/deprecated
 * convenience method while preserving the 2025.1 compile/runtime baseline.
 */
internal object KotlinLightClassResolver {
    private const val GLOBAL_SEARCH_SCOPE = "com.intellij.psi.search.GlobalSearchScope"

    fun resolve(project: Project, declaration: KtClassOrObject): PsiClass? {
        val support = KotlinAsJavaSupport.getInstance(project)
        val methods = KotlinAsJavaSupport::class.java.methods

        val scopedMethod = methods.firstOrNull { method ->
            method.name == "getLightClass" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == KtClassOrObject::class.java &&
                method.parameterTypes[1].name == GLOBAL_SEARCH_SCOPE
        }
        val legacyMethod = methods.firstOrNull { method ->
            method.name == "getLightClass" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == KtClassOrObject::class.java
        }
        val method = scopedMethod ?: legacyMethod
            ?: throw IllegalStateException("The installed Kotlin plugin does not expose a compatible light-class API")

        val arguments = if (method.parameterCount == 2) {
            arrayOf(declaration, null)
        } else {
            arrayOf(declaration)
        }
        return invoke(method, support, arguments) as? PsiClass
    }

    private fun invoke(method: Method, receiver: Any, arguments: Array<out Any?>): Any? =
        try {
            method.invoke(receiver, *arguments)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            when (cause) {
                is ProcessCanceledException -> throw cause
                is CancellationException -> throw cause
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Kotlin class-to-PSI conversion failed", cause)
            }
        } catch (failure: IllegalAccessException) {
            throw IllegalStateException("Kotlin class-to-PSI conversion is inaccessible", failure)
        }
}
