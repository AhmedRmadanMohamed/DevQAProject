package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class MavenDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        return if (
            file.name.equals(
                "pom.xml",
                ignoreCase = true
            )
        ) {
            ProjectFileType.MAVEN
        } else {
            null
        }
    }
}