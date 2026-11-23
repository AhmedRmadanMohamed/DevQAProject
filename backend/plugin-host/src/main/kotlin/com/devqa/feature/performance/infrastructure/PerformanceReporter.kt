package com.devqa.feature.performance.infrastructure

import com.devqa.feature.performance.domain.model.PerformanceReport
import com.intellij.openapi.diagnostic.Logger

class PerformanceReporter {


    fun report(
        report: PerformanceReport
    ) {

        if (LOG.isDebugEnabled) {
            LOG.debug(
                "DevQA performance: operation=${report.operationName}, " +
                    "durationMs=${report.durationMillis}, details=${report.details}"
            )
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PerformanceReporter::class.java)
    }
}
