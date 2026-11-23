package com.devqa.core.analyzer.filter

import com.devqa.core.model.ProjectFile

class ProjectFileDeduplicator {


    fun removeDuplicates(
        files: List<ProjectFile>
    ): List<ProjectFile> {

        return files.distinctBy { it.path }

    }
}
