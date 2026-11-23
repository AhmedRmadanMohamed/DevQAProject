package com.devqa.core.model

import com.devqa.core.fileintelligence.FileIntelligenceProfile
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType

data class ProjectFile(

    val name: String,

    val path: String,

    val relativePath: String,

    val type: ProjectFileType,

    val sourceType: ProjectSourceType,

    val intelligence: FileIntelligenceProfile,

    val moduleName: String? = null
)
