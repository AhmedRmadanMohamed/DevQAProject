package com.devqa.core.classifier.component

import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.classification.ComponentCandidate
import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType

object ComponentClassificationPolicy {
    private val typePriority = ComponentType.entries.withIndex().associate { (index, type) -> type to index }

    fun classify(evidence: List<ClassificationEvidence>): ComponentClassification {
        if (evidence.isEmpty()) {
            val fallback = ClassificationEvidence(
                type = ComponentType.OTHER,
                kind = ClassificationEvidenceKind.FALLBACK,
                matchedSymbol = "no-component-evidence"
            )
            return ComponentClassification(
                primaryType = ComponentType.OTHER,
                confidence = ClassificationConfidence.NONE,
                candidates = listOf(
                    ComponentCandidate(
                        type = ComponentType.OTHER,
                        score = 0,
                        confidence = ClassificationConfidence.NONE,
                        evidence = listOf(fallback)
                    )
                ),
                ambiguous = false
            )
        }

        // Names and package layout are hints, not proof of a runtime/component role.
        // For example, a class named *MapperTest is not necessarily an application mapper.
        // Preserve the weak hints in the fallback explanation without claiming a role.
        val structurallySupported = evidence.any { it.kind !in setOf(
            ClassificationEvidenceKind.CLASS_NAME,
            ClassificationEvidenceKind.PACKAGE,
            ClassificationEvidenceKind.FALLBACK
        ) }
        if (!structurallySupported) {
            return ComponentClassification(
                primaryType = ComponentType.OTHER,
                confidence = ClassificationConfidence.NONE,
                candidates = listOf(ComponentCandidate(
                    type = ComponentType.OTHER,
                    score = 0,
                    confidence = ClassificationConfidence.NONE,
                    evidence = listOf(ClassificationEvidence(
                        type = ComponentType.OTHER,
                        kind = ClassificationEvidenceKind.FALLBACK,
                        matchedSymbol = "insufficient-evidence:" + evidence
                            .distinct().joinToString(",") { "${it.type}:${it.matchedSymbol}" }.take(500)
                    ))
                )),
                ambiguous = false
            )
        }

        val candidates = evidence
            .distinct()
            .groupBy { it.type }
            .map { (type, typeEvidence) ->
                val score = typeEvidence.maxOf { it.score }
                ComponentCandidate(
                    type = type,
                    score = score,
                    confidence = ClassificationConfidence.fromScore(score),
                    evidence = typeEvidence.sortedWith(
                        compareByDescending<ClassificationEvidence> { it.score }
                            .thenBy { it.kind.name }
                            .thenBy { it.matchedSymbol }
                    )
                )
            }
            .sortedWith(
                compareByDescending<ComponentCandidate> { it.score }
                    .thenBy { typePriority.getValue(it.type) }
                    .thenBy { it.type.name }
            )

        val primary = candidates.first()
        return ComponentClassification(
            primaryType = primary.type,
            confidence = primary.confidence,
            candidates = candidates,
            ambiguous = candidates.drop(1).any { it.score == primary.score }
        )
    }
}
