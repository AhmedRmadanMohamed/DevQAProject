package com.devqa.core.classifier.component

import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.type.ComponentType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifierListOwner

object ComponentEvidenceResolver {
    private const val MAX_META_ANNOTATION_DEPTH = 8

    fun annotations(
        psiClass: PsiClass,
        type: ComponentType,
        annotationNames: Set<String>
    ): List<ClassificationEvidence> {
        val evidence = mutableListOf<ClassificationEvidence>()

        evidence += annotationEvidence(
            owner = psiClass,
            type = type,
            annotationNames = annotationNames,
            inheritedFrom = null
        )

        collectSupertypes(psiClass).forEach { supertype ->
            evidence += annotationEvidence(
                owner = supertype,
                type = type,
                annotationNames = annotationNames,
                inheritedFrom = supertype.qualifiedName ?: supertype.name
            )
        }

        return evidence.distinct()
    }

    fun methodAnnotations(
        psiClass: PsiClass,
        type: ComponentType,
        annotationNames: Set<String>
    ): List<ClassificationEvidence> =
        psiClass.methods.flatMap { method ->
            annotationEvidence(
                owner = method,
                type = type,
                annotationNames = annotationNames,
                inheritedFrom = null,
                symbolPrefix = "${method.name}:"
            )
        }.distinct()

    fun supertypes(
        psiClass: PsiClass,
        type: ComponentType,
        qualifiedNames: Set<String>
    ): List<ClassificationEvidence> =
        collectSupertypes(psiClass)
            .mapNotNull { supertype ->
                val qualifiedName = supertype.qualifiedName ?: return@mapNotNull null
                if (qualifiedName !in qualifiedNames) {
                    return@mapNotNull null
                }

                ClassificationEvidence(
                    type = type,
                    kind = if (supertype.isInterface) {
                        ClassificationEvidenceKind.IMPLEMENTED_INTERFACE
                    } else {
                        ClassificationEvidenceKind.SUPERCLASS
                    },
                    matchedSymbol = qualifiedName
                )
            }
            .distinct()

    fun classNameSuffixes(
        psiClass: PsiClass,
        type: ComponentType,
        suffixes: Set<String>
    ): List<ClassificationEvidence> {
        val className = psiClass.name ?: return emptyList()
        val suffix = suffixes.firstOrNull { className.endsWith(it, ignoreCase = true) }
            ?: return emptyList()

        return listOf(
            ClassificationEvidence(
                type = type,
                kind = ClassificationEvidenceKind.CLASS_NAME,
                matchedSymbol = "class-name-suffix:$suffix"
            )
        )
    }

    fun packageSegments(
        psiClass: PsiClass,
        type: ComponentType,
        segments: Set<String>
    ): List<ClassificationEvidence> {
        val qualifiedName = psiClass.qualifiedName ?: return emptyList()
        val packageSegments = qualifiedName.substringBeforeLast('.', "")
            .split('.')
            .filter { it.isNotBlank() }
        val segment = segments.firstOrNull { expected ->
            packageSegments.any { it.equals(expected, ignoreCase = true) }
        } ?: return emptyList()

        return listOf(
            ClassificationEvidence(
                type = type,
                kind = ClassificationEvidenceKind.PACKAGE,
                matchedSymbol = "package-segment:$segment"
            )
        )
    }

    private fun annotationEvidence(
        owner: PsiModifierListOwner,
        type: ComponentType,
        annotationNames: Set<String>,
        inheritedFrom: String?,
        symbolPrefix: String = ""
    ): List<ClassificationEvidence> =
        owner.modifierList?.annotations.orEmpty().mapNotNull { annotation ->
            val directName = annotation.qualifiedName
            val metaPath = findMetaAnnotationPath(
                annotation = annotation,
                annotationNames = annotationNames,
                depth = 0,
                visited = mutableSetOf()
            ) ?: return@mapNotNull null

            val kind = when {
                inheritedFrom != null -> ClassificationEvidenceKind.INHERITED_ANNOTATION
                directName in annotationNames -> ClassificationEvidenceKind.DIRECT_ANNOTATION
                else -> ClassificationEvidenceKind.META_ANNOTATION
            }

            val matchedSymbol = buildString {
                append(symbolPrefix)
                if (inheritedFrom != null) {
                    append(inheritedFrom)
                    append(" -> ")
                }
                append(metaPath.joinToString(" -> "))
            }

            ClassificationEvidence(
                type = type,
                kind = kind,
                matchedSymbol = matchedSymbol
            )
        }

    private fun findMetaAnnotationPath(
        annotation: PsiAnnotation,
        annotationNames: Set<String>,
        depth: Int,
        visited: MutableSet<String>
    ): List<String>? {
        val qualifiedName = annotation.qualifiedName ?: return null
        if (qualifiedName in annotationNames) {
            return listOf(qualifiedName)
        }
        if (depth >= MAX_META_ANNOTATION_DEPTH || !visited.add(qualifiedName)) {
            return null
        }

        val annotationClass = annotation.resolveAnnotationType() ?: return null
        annotationClass.modifierList?.annotations.orEmpty()
            .sortedBy { it.qualifiedName }
            .forEach { metaAnnotation ->
                val path = findMetaAnnotationPath(
                    annotation = metaAnnotation,
                    annotationNames = annotationNames,
                    depth = depth + 1,
                    visited = visited.toMutableSet()
                )
                if (path != null) {
                    return listOf(qualifiedName) + path
                }
            }

        return null
    }

    private fun collectSupertypes(psiClass: PsiClass): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PsiClass>()
        psiClass.supers.forEach(queue::addLast)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val identity = current.qualifiedName ?: current.name ?: continue
            if (!visited.add(identity)) {
                continue
            }
            result += current
            current.supers.forEach(queue::addLast)
        }

        return result
    }
}
