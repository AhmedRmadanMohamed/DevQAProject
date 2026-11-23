package com.devqa.core.classifier.file.detector

import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.classifier.file.FileTypeDetector
import com.intellij.openapi.vfs.VirtualFile

class YamlDetector : FileTypeDetector {

    override fun detect(
        file: VirtualFile
    ): ProjectFileType? {

        return if (
            file.extension.equals("yml", true) ||
            file.extension.equals("yaml", true)
        ) {
            ProjectFileType.YAML
        } else {
            null
        }
    }
}