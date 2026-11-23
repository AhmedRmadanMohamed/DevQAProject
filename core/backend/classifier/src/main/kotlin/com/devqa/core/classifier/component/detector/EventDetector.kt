package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class EventDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.EVENT,
            setOf("java.util.EventObject", "org.springframework.context.ApplicationEvent")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.EVENT,
            setOf("Event")
        )
}
