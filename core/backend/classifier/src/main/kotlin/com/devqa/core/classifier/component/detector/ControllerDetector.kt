package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class ControllerDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.CONTROLLER,
            setOf(
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.stereotype.Controller"
            )
        ) + ComponentEvidenceResolver.packageSegments(
            psiClass,
            ComponentType.CONTROLLER,
            setOf("controller")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.CONTROLLER,
            setOf("Controller")
        )
}
