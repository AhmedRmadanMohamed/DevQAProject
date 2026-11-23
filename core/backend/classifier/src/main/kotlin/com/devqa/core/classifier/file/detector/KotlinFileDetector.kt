package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class KotlinFileDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        return if (
            file.extension.equals(
                "kt",
                ignoreCase = true
            )
        ) {
            ProjectFileType.KOTLIN_SOURCE
        } else {
            null
        }
    }
}