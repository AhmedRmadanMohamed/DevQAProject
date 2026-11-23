package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class ServiceDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.SERVICE,
            setOf("org.springframework.stereotype.Service")
        ) + ComponentEvidenceResolver.packageSegments(
            psiClass,
            ComponentType.SERVICE,
            setOf("service")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.SERVICE,
            setOf("Service")
        )
}
