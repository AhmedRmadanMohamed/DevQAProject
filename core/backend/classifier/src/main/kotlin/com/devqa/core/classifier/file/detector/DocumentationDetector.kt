package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class DocumentationDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        val fileName =
            file.name.lowercase()

        return if (
            fileName == "readme" ||
            fileName.startsWith("readme.") ||
            file.extension.equals(
                "md",
                ignoreCase = true
            ) ||
            file.extension.equals(
                "txt",
                ignoreCase = true
            )
        ) {
            ProjectFileType.DOCUMENTATION
        } else {
            null
        }
    }
}