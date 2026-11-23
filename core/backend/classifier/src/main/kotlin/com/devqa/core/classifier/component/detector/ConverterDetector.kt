package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class ConverterDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.CONVERTER,
            setOf(
                "org.springframework.core.convert.converter.Converter",
                "org.springframework.core.convert.converter.ConverterFactory",
                "org.springframework.core.convert.converter.GenericConverter",
                "org.springframework.core.convert.converter.ConditionalGenericConverter",
                "jakarta.persistence.AttributeConverter",
                "javax.persistence.AttributeConverter"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.CONVERTER,
            setOf("Converter", "ConverterFactory")
        )
}
