package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class AspectDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.ASPECT,
            setOf("org.aspectj.lang.annotation.Aspect")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.ASPECT,
            setOf("Aspect")
        )
}
