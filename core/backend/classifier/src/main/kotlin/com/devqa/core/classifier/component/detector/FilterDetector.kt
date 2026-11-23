package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class FilterDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.FILTER,
            setOf("jakarta.servlet.annotation.WebFilter", "javax.servlet.annotation.WebFilter")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.FILTER,
            setOf(
                "jakarta.servlet.Filter",
                "javax.servlet.Filter",
                "jakarta.servlet.http.HttpFilter",
                "javax.servlet.http.HttpFilter",
                "org.springframework.web.filter.GenericFilterBean",
                "org.springframework.web.filter.OncePerRequestFilter",
                "org.springframework.web.server.WebFilter"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.FILTER,
            setOf("Filter")
        )
}
