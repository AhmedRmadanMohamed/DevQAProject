package com.devqa.core.analyzer

import com.devqa.core.model.DetectedFramework
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.FrameworkVersion
import com.devqa.core.model.FrameworkDependencyDeclaration
import com.devqa.core.model.FrameworkDependencyOrigin
import com.devqa.core.model.classification.ClassificationConfidence
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectFileIndex
import com.devqa.feature.projectbrain.application.ProjectBrainSettings
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CancellationException

/**
 * Detects technologies from declared dependencies first, then strengthens the result with source evidence.
 * Common aliases improve product names but are not an allow-list: unknown frameworks are inferred from
 * their dependency coordinates when those coordinates carry framework/platform/server semantics.
 */
object ProjectTechnologyAnalyzer {
    private data class CachedFileEvidence(
        val modificationStamp: Long,
        val length: Long,
        val matches: List<Candidate>
    )

    // Weak keys ensure project caches are released after project disposal.
    private val evidenceCache = WeakHashMap<Project, MutableMap<String, CachedFileEvidence>>()

    fun isEvidencePath(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return name in manifestNames || name.substringAfterLast('.', "") in sourceExtensions
    }

    fun frameworks(project: Project): FrameworkInventory {
        val candidates = linkedMapOf<String, Candidate>()
        val index = ProjectFileIndex.getInstance(project)
        val settings = project.getService(ProjectBrainSettings::class.java)
        val cache = synchronized(evidenceCache) { evidenceCache.getOrPut(project) { linkedMapOf() } }
        val seen = hashSetOf<String>()

        // Reserve scan capacity for manifests even when a large source tree precedes
        // them in VFS traversal order; source imports alone cannot establish versions.
        val manifests = ArrayList<VirtualFile>()
        val sources = ArrayList<VirtualFile>()
        ProjectFileDiscovery.discover(project).forEach { file ->
            ProgressManager.checkCanceled()
            if (!isEvidenceFile(file) || !settings.allows(project, file, index)) return@forEach
            if (file.name.lowercase(Locale.ROOT) in manifestNames) {
                if (manifests.size < MAX_EVIDENCE_FILES) manifests += file
            } else if (sources.size < MAX_EVIDENCE_FILES) sources += file
        }
        (manifests.take(MAX_EVIDENCE_FILES) + sources.take((MAX_EVIDENCE_FILES - manifests.size).coerceAtLeast(0)))
            .forEach { file ->
                ProgressManager.checkCanceled()
                seen += file.path
                val matches = synchronized(cache) {
                    cache[file.path]?.takeIf {
                        it.modificationStamp == file.modificationStamp && it.length == file.length
                    }?.matches
                }
                    ?: run {
                        val observations = analyzeFileEvidence(project, file)
                        synchronized(cache) {
                            cache[file.path] = CachedFileEvidence(file.modificationStamp, file.length, observations)
                        }
                        observations
                    }
                matches.forEach { match ->
                    candidates.merge(match.name, match, Candidate::mergedWith)
                }
            }

        synchronized(cache) { cache.keys.retainAll(seen) }

        return inventory(candidates)
    }

    /** Reuse all unchanged evidence after a VFS change; avoid rediscovering every source file. */
    fun frameworksAfterChanges(project: Project, paths: Set<String>): FrameworkInventory {
        val cache = synchronized(evidenceCache) { evidenceCache[project] } ?: return frameworks(project)
        val index = ProjectFileIndex.getInstance(project)
        val settings = project.getService(ProjectBrainSettings::class.java)
        for (path in paths) {
            ProgressManager.checkCanceled()
            // Invalidation must happen before observing the replacement, including deleted files.
            synchronized(cache) { cache.remove(path) }
            val file = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            if (!isEvidenceFile(file) || !settings.allows(project, file, index)) continue
            val observations = analyzeFileEvidence(project, file)
            synchronized(cache) {
                cache[path] = CachedFileEvidence(file.modificationStamp, file.length, observations)
            }
        }
        synchronized(cache) {
            val excess = (cache.size - MAX_EVIDENCE_FILES).coerceAtLeast(0)
            cache.keys.take(excess).forEach { cache.remove(it) }
        }
        val candidates = linkedMapOf<String, Candidate>()
        val evidence = synchronized(cache) { cache.values.toList() }
        evidence.forEach { cached ->
            cached.matches.forEach { match -> candidates.merge(match.name, match, Candidate::mergedWith) }
        }
        return inventory(candidates)
    }

    private fun inventory(candidates: Map<String, Candidate>): FrameworkInventory {
        val ranked = candidates.values
            .filter { it.score >= MIN_FRAMEWORK_SCORE }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name })
        val detected = ranked.map { candidate ->
            val distinctVersions = candidate.versions.map { it.value }.distinct()
            DetectedFramework(
                name = candidate.name,
                confidence = when {
                    candidate.score >= HIGH_CONFIDENCE_SCORE -> ClassificationConfidence.HIGH
                    candidate.score >= MIN_FRAMEWORK_SCORE -> ClassificationConfidence.MEDIUM
                    else -> ClassificationConfidence.LOW
                },
                evidence = candidate.evidence.take(MAX_EVIDENCE_PER_FRAMEWORK),
                version = distinctVersions.singleOrNull()?.takeUnless { candidate.versionOverflow },
                declaredVersions = candidate.versions.toList(),
                versionEvidenceTruncated = candidate.versionOverflow,
                dependencyDeclarations = candidate.dependencies.toList(),
                dependencyEvidenceTruncated = candidate.dependencyOverflow
            )
        }

        return FrameworkInventory(
            // Application hosts outrank supporting ecosystems such as Spring Cloud/React.
            primary = detected.firstOrNull { it.name in primaryFrameworks } ?: detected.firstOrNull(),
            frameworks = detected
        )
    }

    private fun analyzeFileEvidence(project: Project, file: VirtualFile): List<Candidate> {
        val content = readSample(file) ?: return emptyList()
        val sourceLabel = file.path.removePrefix(project.basePath.orEmpty()).trimStart('/')
        return analyzeContent(file.name, content, sourceLabel)
    }

    /** Pure extraction used by the scanner and by deterministic manifest regression tests. */
    internal fun detectFromSources(sources: List<Pair<String, String>>): FrameworkInventory {
        val candidates = linkedMapOf<String, Candidate>()
        sources.forEach { (path, content) ->
            analyzeContent(path.substringAfterLast('/'), content, path).forEach { candidate ->
                candidates.merge(candidate.name, candidate, Candidate::mergedWith)
            }
        }
        return inventory(candidates)
    }

    private fun analyzeContent(fileName: String, content: String, sourceLabel: String): List<Candidate> {
        val observations = mutableListOf<Candidate>()
        // A build file containing an example string or commented dependency is not
        // evidence of a configured framework. Manifests must pass the declaration parser.
        if (fileName.lowercase(Locale.ROOT) !in manifestNames) {
            val normalized = content.lowercase(Locale.ROOT)
            discoveryRules.forEach { rule ->
                rule.tokens.firstOrNull(normalized::contains)?.let { token ->
                    observations += Candidate(rule.name, rule.score, linkedSetOf("$sourceLabel:$token"))
                }
            }
        }
        ManifestDependencyParser.parse(fileName, content).forEach { dependency ->
            inferFramework(dependency)?.let { framework ->
                observations += Candidate(
                    framework.name, framework.score, linkedSetOf("$sourceLabel:${dependency.coordinate}"),
                    dependency.version?.let { linkedSetOf(FrameworkVersion(it, sourceLabel, dependency.coordinate)) } ?: linkedSetOf(),
                    dependencies = linkedSetOf(FrameworkDependencyDeclaration(
                        dependency.coordinate, sourceLabel,
                        FrameworkDependencyOrigin.valueOf(dependency.origin.name), dependency.version
                    ))
                )
            }
        }
        return observations
    }

    private fun inferFramework(dependency: ManifestDependencyParser.Declaration): InferredFramework? {
        val coordinate = dependency.coordinate.lowercase()
        exactDependencyNames[dependency.name.lowercase()]?.let { return InferredFramework(it, 112) }
        canonicalDependencyNames.entries.firstOrNull { (token, _) -> token in coordinate }
            ?.let { (token, name) ->
                return InferredFramework(
                    name,
                    canonicalDependencyScores[name] ?: (92 + tokenSpecificity(token))
                )
            }

        inferSpringFamily(coordinate)?.let { return InferredFramework(it, 104) }

        val rawName = dependency.name.substringAfterLast('/').removePrefix("@")
        val normalizedName = rawName
            .replace(Regex("^(spring-boot-starter-|starter-|framework-)"), "")
            .replace(Regex("-(starter|framework|platform|server|core|bundle|sdk)$"), "")
        val groupTail = dependency.group.substringAfterLast('.').substringAfterLast('/')
        val looksLikeFramework = frameworkMarkers.any { marker -> marker in coordinate } ||
            groupTail.isNotBlank() && groupTail.length > 2 &&
            (rawName.startsWith(groupTail) || groupTail in rawName)

        if (!looksLikeFramework || ignoredDependencies.any { it in coordinate }) return null

        val displaySource = normalizedName.takeIf { it.length > 2 } ?: groupTail
        if (displaySource.length <= 2) return null
        return InferredFramework(humanize(displaySource), 66)
    }

    private fun inferSpringFamily(coordinate: String): String? {
        val springToken = springFamily.find(coordinate)?.groupValues?.get(1) ?: return null
        return when (springToken) {
            "core", "context", "beans", "framework" -> "Spring Framework"
            "web", "webmvc" -> "Spring MVC"
            "webflux" -> "Spring WebFlux"
            else -> "Spring ${humanize(springToken)}"
        }
    }

    private fun humanize(value: String): String = value
        .replace(Regex("[-_.]+"), " ")
        .trim()
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            when (word.lowercase()) {
                "js" -> "JS"
                "api" -> "API"
                "mvc" -> "MVC"
                "ai" -> "AI"
                else -> word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString()
                }
            }
        }

    private fun tokenSpecificity(token: String): Int = token.length.coerceAtMost(24) / 4

    private fun isEvidenceFile(file: VirtualFile): Boolean {
        if (file.isDirectory || file.length > MAX_FILE_BYTES) return false
        val name = file.name.lowercase()
        return name in manifestNames || file.extension?.lowercase() in sourceExtensions
    }

    private fun readSample(file: VirtualFile): String? = try {
        file.inputStream.use { input ->
            val limit = if (file.name.lowercase(Locale.ROOT) in manifestNames) MAX_MANIFEST_SAMPLE_BYTES else MAX_SAMPLE_BYTES
            String(input.readNBytes(limit), StandardCharsets.UTF_8)
        }
    } catch (exception: ProcessCanceledException) {
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private data class Candidate(
        val name: String,
        val baseScore: Int,
        val evidence: LinkedHashSet<String>,
        val versions: LinkedHashSet<FrameworkVersion> = linkedSetOf(),
        val versionOverflow: Boolean = false,
        val dependencies: LinkedHashSet<FrameworkDependencyDeclaration> = linkedSetOf(),
        val dependencyOverflow: Boolean = false
    ) {
        val score: Int
            get() = baseScore + evidence.sumOf { source ->
                // Runtime/source usage carries more weight than one incidental declared dependency.
                // Repeated evidence across files is meaningful; a single import never dominates.
                if (source.substringBefore(':').endsWith(".java") ||
                    source.substringBefore(':').endsWith(".kt")) SOURCE_EVIDENCE_BONUS else MANIFEST_EVIDENCE_BONUS
            }.coerceAtMost(MAX_EVIDENCE_TOTAL_BONUS)

        fun mergedWith(other: Candidate): Candidate = Candidate(
            name = name,
            baseScore = maxOf(baseScore, other.baseScore),
            evidence = LinkedHashSet<String>().apply {
                addAll(evidence)
                addAll(other.evidence)
            }.take(MAX_CACHED_EVIDENCE_PER_FRAMEWORK).toCollection(linkedSetOf()),
            versions = (versions + other.versions).take(MAX_VERSION_EVIDENCE).toCollection(linkedSetOf()),
            versionOverflow = versionOverflow || other.versionOverflow ||
                (versions + other.versions).size > MAX_VERSION_EVIDENCE,
            dependencies = (dependencies + other.dependencies).take(MAX_DEPENDENCY_EVIDENCE).toCollection(linkedSetOf()),
            dependencyOverflow = dependencyOverflow || other.dependencyOverflow ||
                (dependencies + other.dependencies).size > MAX_DEPENDENCY_EVIDENCE
        )
    }

    private data class InferredFramework(val name: String, val score: Int)
    private data class DiscoveryRule(val name: String, val tokens: Set<String>, val score: Int)

    private val discoveryRules = listOf(
        // A workload framework is a more useful primary label than its generic
        // Boot/Cloud host when source evidence proves the workload is present.
        DiscoveryRule("Spring Batch", setOf("org.springframework.batch", "spring-batch-"), 140),
        DiscoveryRule("Spring Cloud", setOf("org.springframework.cloud", "spring-cloud-"), 132),
        DiscoveryRule("Spring Integration", setOf("org.springframework.integration", "spring-integration-"), 126),
        DiscoveryRule("Spring AI", setOf("org.springframework.ai", "spring-ai-"), 125),
        DiscoveryRule("Quarkus", setOf("io.quarkus", "quarkus-"), 124),
        DiscoveryRule("Micronaut", setOf("io.micronaut", "micronaut-"), 123),
        DiscoveryRule("Apache Camel", setOf("org.apache.camel", "camel-core"), 122),
        DiscoveryRule("Hibernate ORM", setOf("org.hibernate.orm", "org.hibernate.session", "hibernate-core"), 121),
        DiscoveryRule("Spring Boot", setOf("org.springframework.boot", "spring-boot-starter", "spring-boot-gradle-plugin"), 120),
        DiscoveryRule("Ktor", setOf("io.ktor", "ktor-server"), 118),
        DiscoveryRule("Jakarta EE", setOf("jakarta.platform", "jakarta.jakartaee-api", "jakarta.ws.rs"), 117),
        DiscoveryRule("Android", setOf("com.android.application", "androidx.appcompat", "androidx.compose"), 116),
        DiscoveryRule("Next.js", setOf("\"next\"", "from 'next/", "from \"next/"), 115),
        DiscoveryRule("NestJS", setOf("@nestjs/core", "@nestjs/common"), 114),
        DiscoveryRule("Angular", setOf("@angular/core", "angular.json"), 113),
        DiscoveryRule("React", setOf("\"react\"", "from 'react'", "from \"react\""), 112),
        DiscoveryRule("Vue", setOf("\"vue\"", "from 'vue'", "from \"vue\""), 111),
        DiscoveryRule("Django", setOf("django.", "django==", "django>="), 110),
        DiscoveryRule("FastAPI", setOf("from fastapi", "fastapi==", "fastapi>="), 109),
        DiscoveryRule("Flask", setOf("from flask", "flask==", "flask>="), 108),
        DiscoveryRule("ASP.NET Core", setOf("microsoft.aspnetcore", "webapplication.createbuilder"), 107),
        DiscoveryRule("Laravel", setOf("laravel/framework", "illuminate\\"), 106),
        DiscoveryRule("Ruby on Rails", setOf("rails (", "gem \"rails\"", "gem 'rails'"), 105),
        DiscoveryRule("Flutter", setOf("sdk: flutter", "package:flutter/"), 104),
        DiscoveryRule("Express", setOf("\"express\"", "require('express')", "from 'express'"), 103),
        DiscoveryRule("Spring Framework", setOf("org.springframework.context", "org.springframework.stereotype", "spring-context"), 90),
        DiscoveryRule("Spring MVC", setOf("org.springframework.web.servlet", "spring-webmvc"), 88),
        DiscoveryRule("Spring WebFlux", setOf("org.springframework.web.reactive", "spring-webflux"), 88),
        DiscoveryRule("Spring Data", setOf("org.springframework.data", "spring-data-"), 86),
        DiscoveryRule("Spring Security", setOf("org.springframework.security", "spring-security-"), 85),
        DiscoveryRule("Jakarta Persistence", setOf("jakarta.persistence", "hibernate-core"), 78)
    )

    private val canonicalDependencyNames = linkedMapOf(
        "org.springframework.boot" to "Spring Boot",
        "org.springframework.cloud" to "Spring Cloud",
        "spring-cloud" to "Spring Cloud",
        "spring-batch" to "Spring Batch",
        "spring-integration" to "Spring Integration",
        "spring-ai" to "Spring AI",
        "spring-boot" to "Spring Boot",
        "io.quarkus" to "Quarkus",
        "io.micronaut" to "Micronaut",
        "org.apache.camel" to "Apache Camel",
        "org.hibernate.orm" to "Hibernate ORM",
        "org.hibernate:hibernate-core" to "Hibernate ORM",
        "@nestjs" to "NestJS",
        "@angular" to "Angular",
        ":next" to "Next.js",
        ":react" to "React",
        ":vue" to "Vue",
        ":django" to "Django",
        ":fastapi" to "FastAPI",
        ":flask" to "Flask",
        "microsoft.aspnetcore" to "ASP.NET Core",
        "laravel/framework" to "Laravel",
        ":rails" to "Ruby on Rails",
        ":flutter" to "Flutter",
        ":express" to "Express"
    )

    private val exactDependencyNames = mapOf(
        "next" to "Next.js", "react" to "React", "vue" to "Vue", "express" to "Express",
        "django" to "Django", "fastapi" to "FastAPI", "flask" to "Flask",
        "rails" to "Ruby on Rails", "flutter" to "Flutter"
    )

    private val canonicalDependencyScores = mapOf(
        "Spring Batch" to 136,
        "Spring Cloud" to 132,
        "Spring Integration" to 130,
        "Spring AI" to 128,
        "Spring Boot" to 120
    )

    private val frameworkMarkers = setOf(
        "framework", "starter", "platform", "webapp", "web-app", "server", "mvc"
    )

    private val ignoredDependencies = setOf(
        "junit", "mockito", "assertj", "testcontainers", "slf4j", "logback", "log4j",
        "jackson", "gson", "lombok", "guava", "commons-", "mysql", "postgresql",
        "kotlin-stdlib", "typescript", "eslint", "prettier"
    )

    private val springFamily = Regex("spring(?:framework)?[.:-]([a-z][a-z0-9-]+)")

    private val primaryFrameworks = setOf(
        // Specific application/workload frameworks outrank supporting base libraries.
        "Spring Batch", "Spring Integration", "Spring AI", "Spring Boot", "Spring Cloud",
        "Quarkus", "Micronaut", "Ktor", "Jakarta EE", "Next.js", "NestJS",
        "Angular", "Django", "FastAPI", "Flask", "ASP.NET Core", "Laravel", "Ruby on Rails", "Flutter"
    )

    private val manifestNames = setOf(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "package.json", "pyproject.toml", "requirements.txt", "pipfile", "cargo.toml",
        "go.mod", "composer.json", "gemfile", "pubspec.yaml"
    )

    private val sourceExtensions = setOf(
        "java", "kt", "kts", "groovy", "scala", "js", "jsx", "ts", "tsx", "vue",
        "py", "rb", "php", "cs", "fs", "dart", "go", "rs", "swift"
    )

    private const val MAX_SAMPLE_BYTES = 96 * 1024
    private const val MAX_MANIFEST_SAMPLE_BYTES = 512 * 1024
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    private const val MAX_DEPENDENCY_EVIDENCE = 120
    private const val MAX_EVIDENCE_FILES = 5_000
    private const val MAX_EVIDENCE_PER_FRAMEWORK = 8
    private const val MAX_CACHED_EVIDENCE_PER_FRAMEWORK = 32
    private const val MAX_VERSION_EVIDENCE = 24
    private const val MIN_FRAMEWORK_SCORE = 60
    private const val HIGH_CONFIDENCE_SCORE = 100
    private const val SOURCE_EVIDENCE_BONUS = 12
    private const val MANIFEST_EVIDENCE_BONUS = 4
    private const val MAX_EVIDENCE_TOTAL_BONUS = 180
}
