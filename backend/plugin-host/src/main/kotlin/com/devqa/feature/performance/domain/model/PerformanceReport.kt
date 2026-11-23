package com.devqa.feature.performance.domain.model

data class PerformanceReport(

    val operationName: String,

    val durationMillis: Long,

    val details: Map<String, Any>

)