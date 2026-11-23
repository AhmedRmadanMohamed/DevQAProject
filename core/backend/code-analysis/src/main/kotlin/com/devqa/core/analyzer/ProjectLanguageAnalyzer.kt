package com.devqa.core.analyzer

import com.devqa.core.model.DetectedProjectLanguage
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.type.ProjectSourceType

object ProjectLanguageAnalyzer {

    fun primary(files: List<ProjectFile>): DetectedProjectLanguage {
        val scores = linkedMapOf<String, Int>()
        val counts = linkedMapOf<String, Int>()

        files.forEach { file ->
            val language = normalizeLanguage(file.intelligence.languageId, file.name) ?: return@forEach
            counts[language] = counts.getOrDefault(language, 0) + 1
            scores[language] = scores.getOrDefault(language, 0) + when (file.sourceType) {
                ProjectSourceType.MAIN -> 4
                ProjectSourceType.TEST -> 1
                ProjectSourceType.RESOURCE -> 0
                ProjectSourceType.UNKNOWN -> 2
            }
        }

        val primary = scores.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { counts[it.key] ?: 0 }
                .thenByDescending { it.key }
        )?.key

        return if (primary == null) {
            DetectedProjectLanguage("Unknown", 0)
        } else {
            DetectedProjectLanguage(primary, counts.getValue(primary))
        }
    }

    private fun normalizeLanguage(languageId: String?, fileName: String): String? {
        val normalizedId = languageId?.lowercase()
        languageNames[normalizedId]?.let { return it }
        return extensionNames[fileName.substringAfterLast('.', "").lowercase()]
    }

    private val languageNames = mapOf(
        "java" to "Java",
        "kotlin" to "Kotlin",
        "javascript" to "JavaScript",
        "typescript" to "TypeScript",
        "python" to "Python",
        "php" to "PHP",
        "go" to "Go",
        "rust" to "Rust",
        "c#" to "C#",
        "csharp" to "C#",
        "c++" to "C++",
        "objective-c" to "Objective-C",
        "ruby" to "Ruby",
        "dart" to "Dart",
        "swift" to "Swift",
        "scala" to "Scala",
        "groovy" to "Groovy"
    )

    private val extensionNames = mapOf(
        "java" to "Java", "kt" to "Kotlin", "kts" to "Kotlin",
        "js" to "JavaScript", "jsx" to "JavaScript",
        "ts" to "TypeScript", "tsx" to "TypeScript",
        "py" to "Python", "php" to "PHP", "go" to "Go", "rs" to "Rust",
        "cs" to "C#", "cpp" to "C++", "cc" to "C++", "c" to "C",
        "rb" to "Ruby", "dart" to "Dart", "swift" to "Swift",
        "scala" to "Scala", "groovy" to "Groovy"
    )
}
