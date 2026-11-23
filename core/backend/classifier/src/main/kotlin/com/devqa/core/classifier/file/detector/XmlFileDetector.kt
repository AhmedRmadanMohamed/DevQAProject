package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class XmlFileDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        if (
            file.name.equals(
                "pom.xml",
                ignoreCase = true
            )
        ) {
            return null
        }

        return if (
            file.extension.equals(
                "xml",
                ignoreCase = true
            )
        ) {
            ProjectFileType.XML
        } else {
            null
        }
    }
}