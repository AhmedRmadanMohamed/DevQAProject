package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class SpecificationDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.SPECIFICATION,
            setOf(
                "org.springframework.data.jpa.domain.Specification",
                "org.springframework.data.jpa.domain.PredicateSpecification",
                "org.springframework.data.jpa.domain.UpdateSpecification",
                "org.springframework.data.jpa.domain.DeleteSpecification"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.SPECIFICATION,
            setOf("Specification")
        )
}
