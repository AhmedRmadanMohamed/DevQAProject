package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class GradleDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        return if (
            file.name == "build.gradle" ||
            file.name == "build.gradle.kts" ||
            file.name == "settings.gradle" ||
            file.name == "settings.gradle.kts"
        ) {
            ProjectFileType.GRADLE
        } else {
            null
        }
    }
}