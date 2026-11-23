package com.devqa.feature.projectbrain.presentation

import com.devqa.core.model.ProjectAnalysisResult
import com.intellij.testFramework.LightVirtualFile

class DevQAAnalysisVirtualFile(
    projectName: String,
    val result: ProjectAnalysisResult?
) : LightVirtualFile(
    "DevQA Dashboard - $projectName"
)
