package com.devqa.core.model.classification

import com.devqa.core.model.type.ComponentType

data class ComponentClassification(
    val primaryType: ComponentType,
    val confidence: ClassificationConfidence,
    val candidates: List<ComponentCandidate>,
    val ambiguous: Boolean
) {
    init {
        require(candidates.isNotEmpty()) { "Classification must contain at least one candidate" }
        require(candidates.any { it.type == primaryType }) { "Primary type must exist in candidates" }
        require(candidates.none { it.type == ComponentType.OTHER } || candidates.size == 1) {
            "OTHER cannot be combined with classified component types"
        }
    }
}

data class ComponentCandidate(
    val type: ComponentType,
    val score: Int,
    val confidence: ClassificationConfidence,
    val evidence: List<ClassificationEvidence>
) {
    init {
        require(score in 0..100) { "Classification score must be between 0 and 100" }
        require(evidence.isNotEmpty()) { "A candidate must contain supporting evidence" }
    }
}

data class ClassificationEvidence(
    val type: ComponentType,
    val kind: ClassificationEvidenceKind,
    val matchedSymbol: String,
    val score: Int = kind.score
) {
    init {
        require(score in 0..100) { "Evidence score must be between 0 and 100" }
        require(matchedSymbol.isNotBlank()) { "Evidence must identify the matched symbol" }
    }
}

enum class ClassificationEvidenceKind(val score: Int) {
    DIRECT_ANNOTATION(100),
    META_ANNOTATION(95),
    IMPLEMENTED_INTERFACE(90),
    SUPERCLASS(90),
    INHERITED_ANNOTATION(80),
    PACKAGE(30),
    CLASS_NAME(20),
    FALLBACK(0)
}

enum class ClassificationConfidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun fromScore(score: Int): ClassificationConfidence = when {
            score >= 90 -> HIGH
            score >= 60 -> MEDIUM
            score > 0 -> LOW
            else -> NONE
        }
    }
}
