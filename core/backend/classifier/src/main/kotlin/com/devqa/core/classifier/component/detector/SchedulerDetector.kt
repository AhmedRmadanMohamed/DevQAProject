package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class SchedulerDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.SCHEDULER,
            setOf("org.springframework.scheduling.annotation.EnableScheduling")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.SCHEDULER,
            setOf("org.springframework.scheduling.annotation.SchedulingConfigurer")
        ) + ComponentEvidenceResolver.methodAnnotations(
            psiClass,
            ComponentType.SCHEDULER,
            setOf(
                "org.springframework.scheduling.annotation.Scheduled",
                "org.springframework.scheduling.annotation.Schedules"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.SCHEDULER,
            setOf("Scheduler")
        )
}
