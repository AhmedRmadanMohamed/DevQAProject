package com.devqa.core.model

import com.devqa.core.analyzer.ProjectLanguageAnalyzer

data class ProjectAnalysisResult(

    val scanResult: ProjectScanResult,

    val files: List<ProjectFile>
) {
    val primaryLanguage: DetectedProjectLanguage
        get() = ProjectLanguageAnalyzer.primary(files)
}

data class DetectedProjectLanguage(
    val name: String,
    val fileCount: Int
)
