package com.devqa.core.fileintelligence

import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.ByteArrayInputStream
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException

class FileIntelligenceEngine {

    fun analyze(project: Project, file: VirtualFile): FileIntelligenceProfile {
        ProgressManager.checkCanceled()
        val fileType = file.fileType
        val knownFileType = fileType !is UnknownFileType
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val languageId = psiFile?.language?.id
        val evidence = linkedSetOf<String>()

        if (knownFileType) {
            evidence += "intellij-file-type:${fileType.name}"
        }
        if (languageId != null) {
            evidence += "intellij-language:$languageId"
        }

        val sample = readSample(file, evidence)
        val signature = detectSignature(sample)
        if (signature != null) {
            evidence += "content-signature:${signature.label}"
        }

        val contentKind = when {
            signature?.archive == true -> FileContentKind.ARCHIVE
            signature != null -> FileContentKind.BINARY
            knownFileType && fileType.isBinary -> FileContentKind.BINARY
            knownFileType && !fileType.isBinary -> FileContentKind.TEXT
            looksLikeText(sample, evidence) -> FileContentKind.TEXT
            sample.isNotEmpty() -> FileContentKind.BINARY
            else -> FileContentKind.UNKNOWN
        }

        if (file.length > FileIntelligenceLimits.MAX_CONTENT_ANALYSIS_BYTES) {
            evidence += "size-limit:metadata-only"
        }

        val mimeType = detectMimeType(file.name, sample, signature)
        if (mimeType != null) {
            evidence += "mime:$mimeType"
        }

        val capability = determineCapability(
            languageId = languageId,
            hasPsi = psiFile != null,
            contentKind = contentKind,
            overSizeLimit = file.length > FileIntelligenceLimits.MAX_CONTENT_ANALYSIS_BYTES
        )
        val confidence = when {
            psiFile != null || signature != null -> FileDetectionConfidence.HIGH
            knownFileType || mimeType != null -> FileDetectionConfidence.MEDIUM
            contentKind != FileContentKind.UNKNOWN -> FileDetectionConfidence.LOW
            else -> FileDetectionConfidence.NONE
        }

        return FileIntelligenceProfile(
            fileTypeId = fileType.name.takeIf { knownFileType },
            languageId = languageId,
            contentKind = contentKind,
            mimeType = mimeType,
            capability = capability,
            confidence = confidence,
            evidence = evidence.toList().sorted(),
            sampledBytes = sample.size,
            sizeBytes = file.length
        )
    }

    private fun readSample(file: VirtualFile, evidence: MutableSet<String>): ByteArray {
        return try {
            file.inputStream.use { input ->
                input.readNBytes(FileIntelligenceLimits.MAX_SAMPLE_BYTES).also { sample ->
                    evidence += "content-sample:${sample.size}"
                }
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            evidence += "content-sample:unavailable"
            byteArrayOf()
        }
    }

    private fun determineCapability(
        languageId: String?,
        hasPsi: Boolean,
        contentKind: FileContentKind,
        overSizeLimit: Boolean
    ): FileAnalysisCapability {
        if (overSizeLimit) {
            return if (contentKind == FileContentKind.TEXT) {
                FileAnalysisCapability.PLAIN_TEXT
            } else {
                FileAnalysisCapability.BINARY_METADATA
            }
        }
        if (languageId in uastLanguages) {
            return FileAnalysisCapability.UAST
        }
        if (languageId in structuredLanguages) {
            return FileAnalysisCapability.STRUCTURED_TEXT
        }
        if (hasPsi) {
            return FileAnalysisCapability.PSI
        }
        return when (contentKind) {
            FileContentKind.TEXT -> FileAnalysisCapability.PLAIN_TEXT
            FileContentKind.BINARY, FileContentKind.ARCHIVE -> FileAnalysisCapability.BINARY_METADATA
            FileContentKind.UNKNOWN -> FileAnalysisCapability.UNSUPPORTED
        }
    }

    private fun looksLikeText(sample: ByteArray, evidence: MutableSet<String>): Boolean {
        if (sample.isEmpty()) {
            return false
        }
        if (hasTextBom(sample)) {
            evidence += "text-encoding:bom"
            return true
        }
        if (looksLikeUtf16(sample)) {
            evidence += "text-encoding:utf-16-pattern"
            return true
        }
        val validUtf8 = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample))
            true
        } catch (_: CharacterCodingException) {
            false
        }
        val controlRatio = sample.count { byte ->
            val value = byte.toInt() and 0xff
            value < 0x20 && value !in allowedControls
        }.toDouble() / sample.size
        if (validUtf8 && controlRatio <= MAX_CONTROL_RATIO) {
            evidence += "text-encoding:utf-8"
            return true
        }
        return false
    }

    private fun detectMimeType(
        fileName: String,
        sample: ByteArray,
        signature: FileSignature?
    ): String? =
        signature?.mimeType
            ?: try {
                ByteArrayInputStream(sample).use(URLConnection::guessContentTypeFromStream)
            } catch (_: Exception) {
                null
            }
            ?: URLConnection.guessContentTypeFromName(fileName)

    private fun detectSignature(sample: ByteArray): FileSignature? =
        signatures.firstOrNull { signature ->
            sample.size >= signature.bytes.size &&
                signature.bytes.indices.all { index -> sample[index] == signature.bytes[index] }
        }

    private fun hasTextBom(sample: ByteArray): Boolean =
        textBoms.any { bom ->
            sample.size >= bom.size && bom.indices.all { index -> sample[index] == bom[index] }
        }

    private fun looksLikeUtf16(sample: ByteArray): Boolean {
        if (sample.size < 4) return false
        val pairs = sample.size / 2
        val evenZeros = (0 until sample.size step 2).count { sample[it] == 0.toByte() }
        val oddZeros = (1 until sample.size step 2).count { sample[it] == 0.toByte() }
        return (evenZeros > pairs / 3 && oddZeros < pairs / 10) ||
            (oddZeros > pairs / 3 && evenZeros < pairs / 10)
    }

    private data class FileSignature(
        val label: String,
        val mimeType: String,
        val bytes: ByteArray,
        val archive: Boolean = false
    )

    companion object {
        private const val MAX_CONTROL_RATIO = 0.02
        private val allowedControls = setOf(0x09, 0x0a, 0x0c, 0x0d)
        private val uastLanguages = setOf("JAVA", "kotlin")
        private val structuredLanguages = setOf(
            "JSON",
            "YAML",
            "XML",
            "Properties",
            "SQL",
            "TOML"
        )
        private val textBoms = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        )
        private val signatures = listOf(
            FileSignature("PDF", "application/pdf", "%PDF".toByteArray()),
            FileSignature(
                "PNG",
                "image/png",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ),
            FileSignature("JPEG", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            FileSignature("GIF87a", "image/gif", "GIF87a".toByteArray()),
            FileSignature("GIF89a", "image/gif", "GIF89a".toByteArray()),
            FileSignature("ZIP", "application/zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04), archive = true),
            FileSignature("GZIP", "application/gzip", byteArrayOf(0x1F, 0x8B.toByte()), archive = true),
            FileSignature("7Z", "application/x-7z-compressed", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C), archive = true),
            FileSignature("ELF", "application/x-elf", byteArrayOf(0x7F, 0x45, 0x4C, 0x46)),
            FileSignature("JAVA_CLASS", "application/java-vm", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        )
    }
}
