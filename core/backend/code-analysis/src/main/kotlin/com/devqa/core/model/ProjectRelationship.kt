package com.devqa.core.model

data class ProjectRelationship(
    val sourceClassId: String,
    val sourceQualifiedName: String,
    val targetQualifiedName: String,
    val type: RelationshipType,
    val evidencePath: String,
    val evidence: String,
    val confidence: RelationshipConfidence = RelationshipConfidence.HIGH,
    /** Null for unresolved/external inheritance. Internal edges always have an exact target identity. */
    val targetClassId: String? = null,
    /** Number of unique call/injection sites represented by this edge. */
    val occurrences: Int = 1
)

enum class RelationshipType {
    EXTENDS,
    IMPLEMENTS,
    INJECTS,
    CALLS
}

enum class RelationshipConfidence {
    LOW,
    MEDIUM,
    HIGH
}
