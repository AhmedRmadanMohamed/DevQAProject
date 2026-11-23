package com.devqa.core.classifier.component.detector

import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.type.ComponentType
import com.intellij.psi.PsiClass

/** Spring Batch roles require interface evidence, not class-name or package guesses. */
class BatchComponentDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass, ComponentType.BATCH_READER,
            setOf("org.springframework.batch.item.ItemReader", "org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass, ComponentType.BATCH_PROCESSOR,
            setOf("org.springframework.batch.item.ItemProcessor")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass, ComponentType.BATCH_WRITER,
            setOf("org.springframework.batch.item.ItemWriter")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass, ComponentType.BATCH_JOB,
            setOf("org.springframework.batch.core.Job")
        )
}
