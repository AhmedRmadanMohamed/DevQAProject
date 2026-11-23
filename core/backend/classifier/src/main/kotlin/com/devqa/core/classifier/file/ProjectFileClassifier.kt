package com.devqa.core.classifier.file

import com.devqa.core.model.ProjectFile
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType
import com.devqa.core.classifier.file.detector.*
import com.intellij.openapi.vfs.VirtualFile
import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileDetectionConfidence
import com.devqa.core.fileintelligence.FileIntelligenceProfile

class ProjectFileClassifier(
    private val projectBasePath: String,
    private val detectors: List<FileTypeDetector> = listOf(
        JavaFileDetector(),
        KotlinFileDetector(),
        MavenDetector(),
        GradleDetector(),
        XmlFileDetector(),
        YamlDetector(),
        PropertiesDetector(),
        JsonDetector(),
        SqlDetector(),
        DockerDetector(),
        DocumentationDetector(),
        OtherFileDetector()
    )
) {

    fun classify(
        file: VirtualFile
    ): ProjectFile {

        val type =
            detectors
                .asSequence()
                .firstNotNullOfOrNull { detector ->
                    detector.detect(file)
                }
                ?: ProjectFileType.OTHER


        return ProjectFile(
            name = file.name,
            path = file.path,
            relativePath = buildRelativePath(file),
            type = type,
            sourceType = detectSourceType(file),
            intelligence = FileIntelligenceProfile(
                fileTypeId = null,
                languageId = null,
                contentKind = FileContentKind.UNKNOWN,
                mimeType = null,
                capability = FileAnalysisCapability.UNSUPPORTED,
                confidence = FileDetectionConfidence.NONE,
                evidence = emptyList(),
                sampledBytes = 0,
                sizeBytes = file.length
            )
        )
    }


    private fun buildRelativePath(
        file: VirtualFile
    ): String {

        val projectPath =
            projectBasePath
                .replace("\\", "/")

        val filePath =
            file.path
                .replace("\\", "/")


        return filePath
            .removePrefix(projectPath)
            .removePrefix("/")
    }


    private fun detectSourceType(
        file: VirtualFile
    ): ProjectSourceType {

        val path =
            file.path
                .replace("\\", "/")
                .lowercase()


        return when {

            path.contains("/src/main/resources/")
                    ||
                    path.contains("/src/resources/")
                ->
                ProjectSourceType.RESOURCE


            path.contains("/src/test/")
                ->
                ProjectSourceType.TEST


            path.contains("/src/main/")
                ->
                ProjectSourceType.MAIN


            else ->
                ProjectSourceType.UNKNOWN
        }
    }
}
