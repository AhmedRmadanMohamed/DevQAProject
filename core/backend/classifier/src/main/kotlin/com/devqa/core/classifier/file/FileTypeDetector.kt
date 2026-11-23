package com.devqa.core.classifier.file

import com.devqa.core.model.type.ProjectFileType
import com.intellij.openapi.vfs.VirtualFile

interface FileTypeDetector {

    fun detect(
        file: VirtualFile
    ): ProjectFileType?
}