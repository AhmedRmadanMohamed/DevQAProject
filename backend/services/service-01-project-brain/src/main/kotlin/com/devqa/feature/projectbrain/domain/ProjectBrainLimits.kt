package com.devqa.feature.projectbrain.domain

object ProjectBrainLimits {
    const val TARGET_SCAN_MILLIS_10K = 30_000L
    const val MAX_RECOMMENDED_CLASSES = 100_000
    const val MAX_RECOMMENDED_RELATIONSHIPS = 500_000
    const val MAX_RECOMMENDED_MODEL_BYTES = 256L * 1024L * 1024L
    const val ESTIMATED_BYTES_PER_CLASS = 2_048L
    const val ESTIMATED_BYTES_PER_RELATIONSHIP = 512L
}
