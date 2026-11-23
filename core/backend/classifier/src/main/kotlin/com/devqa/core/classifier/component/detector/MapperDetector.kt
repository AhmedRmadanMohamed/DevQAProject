package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class MapperDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.MAPPER,
            setOf("org.mapstruct.Mapper", "org.apache.ibatis.annotations.Mapper")
        ) + ComponentEvidenceResolver.packageSegments(
            psiClass,
            ComponentType.MAPPER,
            setOf("mapper")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.MAPPER,
            setOf("Mapper")
        )
}
