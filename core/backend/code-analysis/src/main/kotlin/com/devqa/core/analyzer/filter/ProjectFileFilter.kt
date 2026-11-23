package com.devqa.core.analyzer.filter

import com.intellij.openapi.vfs.VirtualFile

class ProjectFileFilter {

    private val ignoredDirectories = setOf(
        ".idea",
        ".git",
        ".gradle",
        "target",
        "build",
        "out",
        "node_modules",
        "vendor",
        "dist",
        ".next",
        "coverage"
    )

    private val ignoredExtensions = setOf(
        "class",
        "jar",
        "war"
    )

    fun shouldInclude(
        file: VirtualFile
    ): Boolean {

        if (file.isDirectory) {
            return false
        }

        val pathParts =
            file.path
                .replace("\\", "/")
                .split("/")

        if (
            pathParts.any { directory ->
                directory in ignoredDirectories
            }
        ) {
            return false
        }

        if (
            file.extension
                ?.lowercase() in ignoredExtensions
        ) {
            return false
        }

        if (
            file.name
                .lowercase()
                .endsWith(".iml")
        ) {
            return false
        }

        return true
    }
}
