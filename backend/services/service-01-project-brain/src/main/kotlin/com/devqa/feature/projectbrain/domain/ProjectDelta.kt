package com.devqa.feature.projectbrain.domain

data class ProjectDelta(
    val fromVersion: Long,
    val toVersion: Long,
    val changedPaths: Set<String>,
    val addedClassIds: Set<String>,
    val removedClassIds: Set<String>,
    val updatedClassIds: Set<String>
)
