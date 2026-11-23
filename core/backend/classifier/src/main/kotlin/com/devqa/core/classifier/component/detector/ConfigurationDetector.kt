package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class ConfigurationDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.CONFIGURATION,
            setOf(
                "org.springframework.context.annotation.Configuration",
                "org.springframework.boot.SpringBootConfiguration",
                "org.springframework.boot.autoconfigure.SpringBootApplication"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.CONFIGURATION,
            setOf("Configuration", "Config")
        )
}
