package com.devqa.core.scanner

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.analyzer.ProjectDependencyAnalyzer
import com.devqa.core.analyzer.ProjectTechnologyAnalyzer
import com.devqa.core.classifier.component.ComponentClassifier
import com.devqa.core.model.DiagnosticSeverity
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectScanDiagnostic
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.feature.projectbrain.domain.ProjectBrainLimits
import com.devqa.feature.projectbrain.application.ProjectBrainSettings
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException

object ProjectScanner {
    private val componentClassifier = ComponentClassifier()

    fun scan(project: Project): ProjectScanResult {
        val startedAt = System.nanoTime()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val settings = project.getService(ProjectBrainSettings::class.java)
        val classes = mutableListOf<ProjectClass>()
        val diagnostics = mutableListOf<ProjectScanDiagnostic>()
        var javaFiles = 0
        var kotlinFiles = 0
        val sourceFilePaths = linkedMapOf<String, ProjectLanguage>()

        fileIndex.iterateContent { virtualFile ->
            ProgressManager.checkCanceled()
            if (!virtualFile.isDirectory && fileIndex.isInSourceContent(virtualFile) && settings.allows(project, virtualFile, fileIndex)) {
                when (virtualFile.extension?.lowercase()) {
                    "java" -> {
                        javaFiles++
                        sourceFilePaths[virtualFile.path] = ProjectLanguage.JAVA
                        classes += scanFile(
                            project,
                            fileIndex,
                            virtualFile,
                            ProjectLanguage.JAVA,
                            diagnostics
                        )
                    }
                    "kt" -> {
                        kotlinFiles++
                        sourceFilePaths[virtualFile.path] = ProjectLanguage.KOTLIN
                        classes += scanFile(
                            project,
                            fileIndex,
                            virtualFile,
                            ProjectLanguage.KOTLIN,
                            diagnostics
                        )
                    }
                }
            }
            true
        }

        return assemble(
            project,
            1,
            classes,
            javaFiles,
            kotlinFiles,
            diagnostics,
            elapsedMillis(startedAt),
            sourceFilePaths
        )
    }

    fun rescan(
        project: Project,
        previous: ProjectScanResult,
        changedPaths: Set<String>,
        forceFullScan: Boolean = false
    ): ProjectScanResult {
        val startedAt = System.nanoTime()
        if (forceFullScan || requiresFullScan(changedPaths)) {
            return scan(project).copy(modelVersion = previous.modelVersion + 1)
        }

        val sourcePaths = changedPaths.filterTo(linkedSetOf()) { path ->
            path.endsWith(".java", ignoreCase = true) || path.endsWith(".kt", ignoreCase = true)
        }
        if (sourcePaths.isEmpty()) {
            val inventory = if (changedPaths.any(ProjectTechnologyAnalyzer::isEvidencePath)) {
                ProjectTechnologyAnalyzer.frameworksAfterChanges(project, changedPaths)
            } else previous.frameworkInventory
            return previous.copy(
                modelVersion = previous.modelVersion + 1,
                springBoot = inventory.frameworks.any { it.name == "Spring Boot" },
                frameworkInventory = inventory,
                metrics = previous.metrics.copy(durationMillis = elapsedMillis(startedAt), filesScanned = 0,
                    classesPerSecond = 0)
            )
        }

        // Older cached models have no source index: a one-time complete scan is safer than incorrect counts.
        if (previous.sourceFilePaths.isEmpty() && previous.javaFiles + previous.kotlinFiles > 0) {
            return scan(project).copy(modelVersion = previous.modelVersion + 1)
        }

        val fileIndex = ProjectFileIndex.getInstance(project)
        val settings = project.getService(ProjectBrainSettings::class.java)
        val diagnostics = previous.diagnostics
            .filterNot { it.path in sourcePaths || it.message in modelBudgetWarnings }
            .toMutableList()
        val refreshedSourcePaths = previous.sourceFilePaths.toMutableMap().apply {
            sourcePaths.forEach(::remove)
        }
        var analyzedSourceFiles = 0

        // Resolve changed paths directly first. In normal local projects this is O(M), where M
        // is the number of changed files. Some IntelliJ test/virtual file systems expose a
        // VirtualFile path that cannot be reconstructed as a file:// URL, so retain a bounded
        // correctness fallback: walk project content once for only the unresolved paths.
        val unresolvedPaths = sourcePaths.toMutableSet()
        val resolvedFiles = linkedMapOf<String, VirtualFile>()
        sourcePaths.forEach { path ->
            ProgressManager.checkCanceled()
            val candidate = VirtualFileManager.getInstance().findFileByUrl(VfsUtilCore.pathToUrl(path))
                ?: LocalFileSystem.getInstance().findFileByPath(path)
            if (candidate != null && candidate.isValid && fileIndex.isInSourceContent(candidate)) {
                resolvedFiles[path] = candidate
                unresolvedPaths.remove(path)
            }
        }
        if (unresolvedPaths.isNotEmpty()) {
            fileIndex.iterateContent { candidate ->
                if (!candidate.isDirectory && candidate.path in unresolvedPaths) {
                    resolvedFiles[candidate.path] = candidate
                    unresolvedPaths.remove(candidate.path)
                }
                unresolvedPaths.isNotEmpty()
            }
        }

        val refreshedClasses = sourcePaths.flatMap { path ->
            ProgressManager.checkCanceled()
            val virtualFile = resolvedFiles[path] ?: return@flatMap emptyList()
            if (!virtualFile.isValid || !fileIndex.isInSourceContent(virtualFile) ||
                !settings.allows(project, virtualFile, fileIndex)
            ) {
                return@flatMap emptyList()
            }
            val language = when (virtualFile.extension?.lowercase()) {
                "java" -> ProjectLanguage.JAVA
                "kt" -> ProjectLanguage.KOTLIN
                else -> return@flatMap emptyList()
            }
            refreshedSourcePaths[path] = language
            analyzedSourceFiles++
            val diagnosticCount = diagnostics.size
            val scanned = scanFile(project, fileIndex, virtualFile, language, diagnostics)
            if (diagnostics.size > diagnosticCount) {
                // A parser failure must not silently erase previously valid components.
                // The warning is retained until a later successful scan replaces them.
                previous.allClasses.filter { it.sourcePath == path }
            } else scanned
        }
        val fileCounts = Pair(
            refreshedSourcePaths.values.count { it == ProjectLanguage.JAVA },
            refreshedSourcePaths.values.count { it == ProjectLanguage.KOTLIN }
        )

        return assemble(
            project,
            previous.modelVersion + 1,
            reconcileClasses(previous.allClasses, sourcePaths, refreshedClasses),
            fileCounts.first,
            fileCounts.second,
            diagnostics,
            elapsedMillis(startedAt),
            refreshedSourcePaths,
            if (changedPaths.any(ProjectTechnologyAnalyzer::isEvidencePath))
                ProjectTechnologyAnalyzer.frameworksAfterChanges(
                    project, changedPaths.filterTo(linkedSetOf(), ProjectTechnologyAnalyzer::isEvidencePath)
                )
            else previous.frameworkInventory,
            analyzedSourceFiles,
            refreshedClasses.size
        )
    }

    private fun scanFile(
        project: Project,
        fileIndex: ProjectFileIndex,
        virtualFile: VirtualFile,
        language: ProjectLanguage,
        diagnostics: MutableList<ProjectScanDiagnostic>
    ): List<ProjectClass> {
        return try {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return emptyList()
            val rootClasses = when {
                language == ProjectLanguage.JAVA && psiFile is PsiJavaFile -> psiFile.classes.toList()
                language == ProjectLanguage.KOTLIN && psiFile is KtFile ->
                    psiFile.declarations.filterIsInstance<KtClassOrObject>().mapNotNull { declaration ->
                        KotlinLightClassResolver.resolve(project, declaration)
                    }
                else -> emptyList()
            }
            val idDigest = MessageDigest.getInstance("SHA-256")
            collectClasses(rootClasses).mapNotNull { psiClass ->
                ProgressManager.checkCanceled()
                if (!psiClass.isValid || psiClass.name.isNullOrBlank()) {
                    null
                } else {
                    toProjectClass(fileIndex, virtualFile, psiClass, language, idDigest)
                }
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            diagnostics += ProjectScanDiagnostic(
                path = virtualFile.path,
                message = "Source analysis failed: ${exception::class.java.simpleName}",
                severity = DiagnosticSeverity.ERROR
            )
            emptyList()
        }
    }

    private fun toProjectClass(
        fileIndex: ProjectFileIndex,
        virtualFile: VirtualFile,
        psiClass: PsiClass,
        language: ProjectLanguage,
        idDigest: MessageDigest
    ): ProjectClass {
        val name = requireNotNull(psiClass.name)
        val qualifiedName = psiClass.qualifiedName
        val moduleName = fileIndex.getModuleForFile(virtualFile)?.name
        val packageName = qualifiedName?.substringBeforeLast('.', "").orEmpty()
        val sourceOffset = psiClass.textRange?.startOffset ?: 0
        val superClassPsi = psiClass.superClass
        val superClass = superClassPsi?.qualifiedName
            ?.takeUnless { it == "java.lang.Object" }
        val resolvedInterfaces = psiClass.interfaces
        val interfaces = resolvedInterfaces.mapNotNull { it.qualifiedName }.toSortedSet()
        val typePaths = buildMap {
            if (superClass != null) superClassPsi.containingFile?.virtualFile?.path?.let { put(superClass, it) }
            resolvedInterfaces.forEach { target ->
                target.qualifiedName?.let { name ->
                    target.containingFile?.virtualFile?.path?.let { put(name, it) }
                }
            }
        }

        return ProjectClass(
            id = stableId(moduleName, language, qualifiedName, virtualFile.path, name, sourceOffset, idDigest),
            name = name,
            qualifiedName = qualifiedName,
            moduleName = moduleName,
            packageName = packageName,
            sourcePath = virtualFile.path,
            sourceOffset = sourceOffset,
            language = language,
            origin = originOf(fileIndex, virtualFile),
            modifiers = supportedModifiers.filterTo(sortedSetOf()) { psiClass.hasModifierProperty(it) },
            annotations = psiClass.modifierList?.annotations.orEmpty()
                .mapNotNull { it.qualifiedName }
                .toSortedSet(),
            superTypes = listOfNotNull(superClass).toSortedSet(),
            interfaces = interfaces,
            dependencies = ProjectDependencyAnalyzer.analyze(psiClass),
            classification = componentClassifier.classify(psiClass),
            resolvedTypePaths = typePaths
        )
    }

    private fun collectClasses(roots: List<PsiClass>): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        fun visit(psiClass: PsiClass) {
            result += psiClass
            psiClass.innerClasses.forEach(::visit)
        }
        roots.forEach(::visit)
        return result
    }

    private fun assemble(
        project: Project,
        modelVersion: Long,
        classes: List<ProjectClass>,
        javaFiles: Int,
        kotlinFiles: Int,
        diagnostics: List<ProjectScanDiagnostic>,
        durationMillis: Long,
        sourceFilePaths: Map<String, ProjectLanguage>,
        existingFrameworks: com.devqa.core.model.FrameworkInventory? = null,
        analyzedSourceFiles: Int = javaFiles + kotlinFiles,
        analyzedClasses: Int = classes.size
    ): ProjectScanResult {
        val deduplicatedClasses = classes.distinctBy { it.id }.sortedWith(
            compareBy<ProjectClass> { it.qualifiedName ?: it.name }
                .thenBy { it.sourcePath }
                .thenBy { it.sourceOffset }
        )
        val frameworkInventory = existingFrameworks ?: ProjectTechnologyAnalyzer.frameworks(project)
        val springBoot = frameworkInventory.frameworks.any { it.name == "Spring Boot" }
        val relationships = ProjectModelAssembler.relationships(deduplicatedClasses)
        val estimatedModelBytes =
            deduplicatedClasses.size * ProjectBrainLimits.ESTIMATED_BYTES_PER_CLASS +
                relationships.size * ProjectBrainLimits.ESTIMATED_BYTES_PER_RELATIONSHIP
        val hardenedDiagnostics = diagnostics.toMutableList().apply {
            if (deduplicatedClasses.size > ProjectBrainLimits.MAX_RECOMMENDED_CLASSES) {
                add(
                    ProjectScanDiagnostic(
                        path = null,
                        message = "Class count exceeds recommended model budget",
                        severity = DiagnosticSeverity.WARNING
                    )
                )
            }
            if (relationships.size > ProjectBrainLimits.MAX_RECOMMENDED_RELATIONSHIPS) {
                add(
                    ProjectScanDiagnostic(
                        path = null,
                        message = "Relationship count exceeds recommended model budget",
                        severity = DiagnosticSeverity.WARNING
                    )
                )
            }
            if (estimatedModelBytes > ProjectBrainLimits.MAX_RECOMMENDED_MODEL_BYTES) {
                add(
                    ProjectScanDiagnostic(
                        path = null,
                        message = "Estimated model memory exceeds 256 MB",
                        severity = DiagnosticSeverity.WARNING
                    )
                )
            }
        }

        return ProjectScanResult(
            modelVersion = modelVersion,
            springBoot = springBoot,
            javaFiles = javaFiles,
            javaClasses = deduplicatedClasses.count { it.language == ProjectLanguage.JAVA },
            kotlinFiles = kotlinFiles,
            kotlinClasses = deduplicatedClasses.count { it.language == ProjectLanguage.KOTLIN },
            allClasses = deduplicatedClasses,
            relationships = relationships,
            architectureProfile = ProjectModelAssembler.architecture(deduplicatedClasses, relationships),
            frameworkInventory = frameworkInventory,
            diagnostics = hardenedDiagnostics.distinct().sortedWith(
                compareBy<ProjectScanDiagnostic> { it.severity.name }
                    .thenBy { it.path }
                    .thenBy { it.message }
            ),
            metrics = ProjectScanMetrics(
                durationMillis = durationMillis,
                filesScanned = analyzedSourceFiles,
                classesDiscovered = deduplicatedClasses.size,
                relationshipsDiscovered = relationships.size,
                classesPerSecond = analyzedClasses * 1_000L / durationMillis.coerceAtLeast(1),
                estimatedModelBytes = estimatedModelBytes
            ),
            sourceFilePaths = sourceFilePaths
        )
    }

    private fun originOf(fileIndex: ProjectFileIndex, file: VirtualFile): ProjectClassOrigin = when {
        fileIndex.isInGeneratedSources(file) -> ProjectClassOrigin.GENERATED
        fileIndex.isInTestSourceContent(file) -> ProjectClassOrigin.TEST
        fileIndex.isInSourceContent(file) -> ProjectClassOrigin.MAIN
        else -> ProjectClassOrigin.UNKNOWN
    }

    private fun stableId(
        moduleName: String?,
        language: ProjectLanguage,
        qualifiedName: String?,
        sourcePath: String,
        name: String,
        sourceOffset: Int,
        digest: MessageDigest
    ): String {
        val identity = listOf(
            moduleName.orEmpty(),
            language.name,
            qualifiedName ?: name,
            sourcePath,
            if (qualifiedName == null) sourceOffset.toString() else ""
        ).joinToString("|")
        val bytes = digest.digest(identity.toByteArray(Charsets.UTF_8))
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = HEX_DIGITS[value ushr 4]
            result[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return result.concatToString()
    }

    fun requiresFullScan(changedPaths: Set<String>): Boolean = changedPaths.any(::requiresFullScan)

    /** Deterministic source replacement; unchanged classes remain available to every project view. */
    internal fun reconcileClasses(
        previous: List<ProjectClass>, changedPaths: Set<String>, replacements: List<ProjectClass>
    ): List<ProjectClass> = previous.filterNot { it.sourcePath in changedPaths } + replacements

    private fun requiresFullScan(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        val name = normalized.substringAfterLast('/')
        // A configuration/YAML/SQL edit is not a reason to reparse every Java/Kotlin class.
        // Directory/root changes still need a complete source-root inventory.
        val sourceDirectoryChange = "/src/" in normalized && '.' !in name
        return sourceDirectoryChange || name in setOf(
            "build.gradle",
            "build.gradle.kts",
            "pom.xml",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties"
        )
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private val supportedModifiers = listOf(
        PsiModifier.PUBLIC,
        PsiModifier.PROTECTED,
        PsiModifier.PRIVATE,
        PsiModifier.ABSTRACT,
        PsiModifier.FINAL,
        PsiModifier.STATIC,
        PsiModifier.SEALED
    )

    private val modelBudgetWarnings = setOf(
        "Class count exceeds recommended model budget",
        "Relationship count exceeds recommended model budget",
        "Estimated model memory exceeds 256 MB"
    )

    private const val HEX_DIGITS = "0123456789abcdef"
}
