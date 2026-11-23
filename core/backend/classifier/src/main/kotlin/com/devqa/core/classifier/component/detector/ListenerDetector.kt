package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class ListenerDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.LISTENER,
            setOf("org.springframework.context.ApplicationListener")
        ) + ComponentEvidenceResolver.methodAnnotations(
            psiClass,
            ComponentType.LISTENER,
            setOf(
                "org.springframework.context.event.EventListener",
                "org.springframework.transaction.event.TransactionalEventListener"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.LISTENER,
            setOf("Listener")
        )
}
