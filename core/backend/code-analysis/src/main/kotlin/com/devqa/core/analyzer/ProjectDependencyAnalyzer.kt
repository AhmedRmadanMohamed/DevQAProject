package com.devqa.core.analyzer

import com.devqa.core.model.ClassDependency
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.concurrent.CancellationException

object ProjectDependencyAnalyzer {
    private val injectionAnnotations = setOf(
        "org.springframework.beans.factory.annotation.Autowired",
        "jakarta.inject.Inject",
        "javax.inject.Inject"
    )

    fun analyze(psiClass: PsiClass): List<ClassDependency> {
        val dependencies = mutableListOf<ClassDependency>()
        dependencies += constructorInjection(psiClass)
        dependencies += fieldInjection(psiClass)
        dependencies += setterInjection(psiClass)
        dependencies += methodCalls(psiClass)
        return dependencies.distinct().sortedWith(
            compareBy<ClassDependency> { it.type.name }
                .thenBy { it.targetQualifiedName }
                .thenBy { it.evidence }
        )
    }

    private fun constructorInjection(psiClass: PsiClass): List<ClassDependency> {
        val constructors = psiClass.constructors
        // A single constructor on an ordinary domain class does not prove dependency injection.
        // Spring-style implicit injection is only supported for bean types; explicit @Inject is unambiguous.
        val springBean = psiClass.annotations.any { annotation ->
            val name = annotation.qualifiedName.orEmpty()
            name in beanAnnotations
        }
        val injectable = constructors.filter { constructor ->
            hasInjectionAnnotation(constructor) || (springBean && constructors.size == 1)
        }
        return injectable.flatMap { constructor ->
            constructor.parameterList.parameters.flatMap { parameter ->
                targetTypes(parameter.type).map { target ->
                    ClassDependency(
                        targetQualifiedName = target.qualifiedName,
                        type = RelationshipType.INJECTS,
                        evidence = "constructor:${constructor.name}:${parameter.name}",
                        confidence = RelationshipConfidence.HIGH,
                        targetSourcePath = target.sourcePath
                    )
                }
            }
        }
    }

    private fun fieldInjection(psiClass: PsiClass): List<ClassDependency> =
        psiClass.fields.flatMap { field ->
            val confidence = when {
                hasInjectionAnnotation(field) -> RelationshipConfidence.HIGH
                else -> return@flatMap emptyList()
            }
            targetTypes(field.type).map { target ->
                ClassDependency(
                    targetQualifiedName = target.qualifiedName,
                    type = RelationshipType.INJECTS,
                    evidence = "field:${field.name}",
                    confidence = confidence,
                    targetSourcePath = target.sourcePath
                )
            }
        }

    private fun setterInjection(psiClass: PsiClass): List<ClassDependency> =
        psiClass.methods.filter(::hasInjectionAnnotation).flatMap { method ->
            method.parameterList.parameters.flatMap { parameter ->
                targetTypes(parameter.type).map { target ->
                    ClassDependency(
                    targetQualifiedName = target.qualifiedName,
                        type = RelationshipType.INJECTS,
                        evidence = "method:${method.name}:${parameter.name}",
                    confidence = RelationshipConfidence.HIGH,
                    targetSourcePath = target.sourcePath
                    )
                }
            }
        }

    private fun methodCalls(psiClass: PsiClass): List<ClassDependency> {
        val sourceName = psiClass.qualifiedName
        val calls = mutableListOf<ClassDependency>()
        return try {
            val rootClass = psiClass.toUElementOfType<UClass>() ?: return emptyList()
            rootClass.accept(
                object : AbstractUastVisitor() {
                    // Nested classes are scanned separately by ProjectScanner. Including their
                    // calls here incorrectly attributes child behavior to the outer class.
                    override fun visitClass(node: UClass): Boolean = node !== rootClass

                    override fun visitCallExpression(node: UCallExpression): Boolean {
                        ProgressManager.checkCanceled()
                        val method = node.resolve() ?: return false
                        val owner = method.containingClass ?: return false
                        val target = owner.qualifiedName ?: return false
                        if (target != sourceName && !isPlatformType(target)) {
                            calls += ClassDependency(
                                targetQualifiedName = target,
                                type = RelationshipType.CALLS,
                                evidence = "call:${method.name}@${node.sourcePsi?.textOffset ?: -1}",
                                confidence = RelationshipConfidence.HIGH,
                                targetSourcePath = owner.containingFile?.virtualFile?.path
                            )
                        }
                        return false
                    }
                }
            )
            calls
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class TargetType(val qualifiedName: String, val sourcePath: String?)

    private fun targetTypes(type: PsiType): Set<TargetType> {
        val targets = linkedSetOf<TargetType>()
        fun collect(current: PsiType) {
            when (current) {
                is PsiArrayType -> collect(current.componentType)
                is PsiWildcardType -> current.bound?.let(::collect)
                is PsiClassType -> {
                    val declaration = current.resolve()
                    declaration?.qualifiedName?.let { qualifiedName ->
                        if (!isPlatformType(qualifiedName)) {
                            targets += TargetType(qualifiedName, declaration.containingFile?.virtualFile?.path)
                        }
                    }
                    current.parameters.forEach(::collect)
                }
            }
        }
        collect(type)
        return targets
    }

    private fun hasInjectionAnnotation(owner: PsiModifierListOwner): Boolean =
        injectionAnnotations.any(owner::hasAnnotation)

    private val beanAnnotations = setOf(
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller",
        "org.springframework.context.annotation.Configuration"
    )

    private fun isPlatformType(qualifiedName: String): Boolean =
        qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jakarta.") ||
            qualifiedName.startsWith("kotlin.")
}
