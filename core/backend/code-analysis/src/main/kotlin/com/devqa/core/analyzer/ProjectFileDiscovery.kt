package com.devqa.core.analyzer

import com.devqa.core.analyzer.filter.ProjectFileFilter
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

object ProjectFileDiscovery {

    private val fileFilter =
        ProjectFileFilter()

    fun discover(
        project: Project
    ): List<VirtualFile> {

        val files =
            linkedMapOf<String, VirtualFile>()

        val fileIndex =
            ProjectFileIndex.getInstance(project)

        fileIndex.iterateContent { virtualFile ->
            ProgressManager.checkCanceled()

            if (
                fileFilter.shouldInclude(
                    virtualFile
                )
            ) {
                files.putIfAbsent(
                    virtualFile.path,
                    virtualFile
                )
            }

            true
        }

        // Deduplicate while traversing so overlapping content roots cannot create
        // duplicate work, without a second full distinctBy allocation/pass.
        return files.values.toList()
    }
}
