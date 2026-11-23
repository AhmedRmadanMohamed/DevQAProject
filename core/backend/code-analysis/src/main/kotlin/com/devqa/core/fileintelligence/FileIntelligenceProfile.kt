package com.devqa.core.fileintelligence

data class FileIntelligenceProfile(
    val fileTypeId: String?,
    val languageId: String?,
    val contentKind: FileContentKind,
    val mimeType: String?,
    val capability: FileAnalysisCapability,
    val confidence: FileDetectionConfidence,
    val evidence: List<String>,
    val sampledBytes: Int,
    val sizeBytes: Long
)

enum class FileContentKind {
    TEXT,
    BINARY,
    ARCHIVE,
    UNKNOWN
}

enum class FileAnalysisCapability {
    PSI,
    UAST,
    STRUCTURED_TEXT,
    PLAIN_TEXT,
    BINARY_METADATA,
    UNSUPPORTED
}

enum class FileDetectionConfidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

object FileIntelligenceLimits {
    const val MAX_SAMPLE_BYTES = 64 * 1024
    const val MAX_CONTENT_ANALYSIS_BYTES = 128L * 1024L * 1024L
}
