package com.devqa.feature.performance.application

import com.devqa.feature.performance.domain.model.PerformanceReport

class PerformanceTimer {


    fun measure(
        operationName: String,
        operation: () -> Map<String, Any>
    ): PerformanceReport {


        val start =
            System.nanoTime()


        val details =
            operation()


        val duration =
            (System.nanoTime() - start) / 1_000_000L


        return PerformanceReport(
            operationName = operationName,
            durationMillis = duration,
            details = details
        )
    }
}
