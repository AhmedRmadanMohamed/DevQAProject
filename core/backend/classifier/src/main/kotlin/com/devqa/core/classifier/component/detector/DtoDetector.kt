package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class DtoDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.packageSegments(
            psiClass,
            ComponentType.DTO,
            setOf("dto")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.DTO,
            setOf("Dto")
        )
}
