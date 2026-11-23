package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class UtilityDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.UTILITY,
            setOf("lombok.experimental.UtilityClass")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.UTILITY,
            setOf("Util", "Utils", "Utility", "Utilities")
        )
}
