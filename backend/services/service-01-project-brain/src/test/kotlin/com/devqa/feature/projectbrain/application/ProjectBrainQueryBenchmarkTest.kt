package com.devqa.feature.projectbrain.application

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.classifier.component.ComponentClassificationPolicy
import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileDetectionConfidence
import com.devqa.core.fileintelligence.FileIntelligenceProfile
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.DetectedFramework
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType
import com.devqa.core.scanner.ProjectScanner
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Repeatable in-memory MODEL/FILE-QUERY/GRAPH benchmark, NOT a full IntelliJ project scan.
 * Default: 1k and 10k. Opt in to 50k/100k using DEVQA_BENCHMARK_MAX_CLASSES=100000.
 * Reports actual timings from this JVM and sampled (not exact) peak heap usage.
 * PSI scanning, JCEF rendering, incremental IDE processing and EDT latency remain unmeasured.
 */
class ProjectBrainQueryBenchmarkTest {
    private val json = Gson()

    @Test
    fun benchmarkBoundedQueries() {
        val permittedMax = (System.getenv("DEVQA_BENCHMARK_MAX_CLASSES")?.toIntOrNull() ?: 10_000)
            .coerceIn(1_000, 100_000)
        for (size in listOf(1_000, 10_000, 50_000, 100_000).filter { it <= permittedMax }) {
            val heapPeak = AtomicLong(heapUsed())
            val sampler = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "devqa-benchmark-heap-sampler").apply { isDaemon = true }
            }
            sampler.scheduleAtFixedRate({
                heapPeak.updateAndGet { previous -> maxOf(previous, heapUsed()) }
            }, 0, 5, TimeUnit.MILLISECONDS)
            try {
            lateinit var classes: List<ProjectClass>
            lateinit var files: List<ProjectFile>
            lateinit var relationships: List<com.devqa.core.model.ProjectRelationship>
            lateinit var frameworks: List<DetectedFramework>
            lateinit var result: ProjectAnalysisResult
            val modelBuildMillis = measureTimeMillis {
                classes = makeClasses(size)
                files = makeFiles(size)
                relationships = ProjectModelAssembler.relationships(classes)
                frameworks = (0 until 32).map { index ->
                    DetectedFramework("Synthetic Framework $index", ClassificationConfidence.MEDIUM,
                        listOf("module-${index % 12}/src/Synthetic$index.java:synthetic-evidence"))
                }
                result = ProjectAnalysisResult(
                    scanResult = ProjectScanResult(
                        modelVersion = 1L,
                        springBoot = false,
                        javaFiles = size,
                        javaClasses = size,
                        kotlinFiles = 0,
                        kotlinClasses = 0,
                        allClasses = classes,
                        relationships = relationships,
                        architectureProfile = ProjectModelAssembler.architecture(classes, relationships),
                        frameworkInventory = FrameworkInventory(frameworks.first(), frameworks),
                        diagnostics = emptyList(),
                        metrics = ProjectScanMetrics(0L, size, size, relationships.size, 0L, 0L)
                    ),
                    files = files
                )
            }

            lateinit var page: String
            val classFilterMillis = measureTimeMillis {
                page = ProjectBrainQueryService.query(result,
                    """{"kind":"components","search":"Synthetic","offset":${size - 40},"limit":40}""")
            }
            assertEquals(size, JsonParser.parseString(page).asJsonObject.get("total").asInt)
            assertEquals(40, JsonParser.parseString(page).asJsonObject.getAsJsonArray("items").size())
            lateinit var filesPage: String
            val fileFilterMillis = measureTimeMillis {
                filesPage = ProjectBrainQueryService.query(result,
                    """{"kind":"files","filters":{"module":["module-5"]},"limit":40}""")
            }
            assertEquals((0 until size).count { it % 12 == 5 },
                JsonParser.parseString(filesPage).asJsonObject.get("total").asInt)
            assertEquals(40, JsonParser.parseString(filesPage).asJsonObject.getAsJsonArray("items").size())
            lateinit var frameworkPage: String
            val frameworkFilterMillis = measureTimeMillis {
                frameworkPage = ProjectBrainQueryService.query(result,
                    """{"kind":"frameworks","filters":{"module":["module-5"]},"limit":40}""")
            }
            assertEquals(3, JsonParser.parseString(frameworkPage).asJsonObject.get("total").asInt)
            lateinit var diagram: String
            val graphMillis = measureTimeMillis {
                diagram = ProjectBrainQueryService.query(result, """{"kind":"diagram","view":"modules"}""")
            }
            val nodes = JsonParser.parseString(diagram).asJsonObject.getAsJsonArray("nodes").size()
            assertTrue(nodes <= 80)
            lateinit var changed: List<ProjectClass>
            lateinit var rebuilt: List<com.devqa.core.model.ProjectRelationship>
            val changedClass = classes[size / 2].copy(dependencies = emptyList())
            val incrementalModelMillis = measureTimeMillis {
                changed = ProjectScanner.reconcileClasses(classes,
                    setOf(changedClass.sourcePath), listOf(changedClass))
                rebuilt = ProjectModelAssembler.relationships(changed)
            }
            assertEquals(classes.size, changed.size)
            assertEquals(relationships.size - 1, rebuilt.size)
            heapPeak.updateAndGet { previous -> maxOf(previous, heapUsed()) }
            println(json.toJson(mapOf(
                "kind" to "PROJECT_BRAIN_SYNTHETIC_MODEL_BENCHMARK",
                "files" to size,
                "classes" to size,
                "modelBuildMillis" to modelBuildMillis,
                "incrementalModelRebuildMillis" to incrementalModelMillis,
                "classFilterMillis" to classFilterMillis,
                "fileFilterMillis" to fileFilterMillis,
                "frameworkFilterMillis" to frameworkFilterMillis,
                "graphQueryMillis" to graphMillis,
                "graphNodes" to nodes,
                "sampledPeakHeapMiB" to (heapPeak.get() / 1_048_576.0),
                "actualPsiScanMillis" to "UNMEASURED",
                "actualIdeIncrementalMillis" to "UNMEASURED",
                "intellijEdtLatencyMillis" to "UNMEASURED"
            )))
            } finally {
                sampler.shutdownNow()
            }
        }
    }

    private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun makeFiles(size: Int): List<ProjectFile> {
        val profile = FileIntelligenceProfile("JAVA", "JAVA", FileContentKind.TEXT,
            "text/x-java-source", FileAnalysisCapability.PSI, FileDetectionConfidence.HIGH,
            emptyList(), 0, 128)
        return (0 until size).map { index ->
            val module = "module-${index % 12}"
            val name = "Synthetic$index.java"
            ProjectFile(name, "/benchmark/$module/src/$name", "$module/src/$name",
                ProjectFileType.JAVA_SOURCE, ProjectSourceType.MAIN, profile, module)
        }
    }

    private fun makeClasses(size: Int): List<ProjectClass> {
        val fallback = ComponentClassificationPolicy.classify(emptyList())
        return (0 until size).map { index ->
            ProjectClass(
                id = index.toString(),
                name = "Synthetic$index",
                qualifiedName = "benchmark.Synthetic$index",
                moduleName = "module-${index % 12}",
                packageName = "benchmark",
                sourcePath = "/benchmark/module-${index % 12}/src/Synthetic$index.java",
                sourceOffset = 0,
                language = ProjectLanguage.JAVA,
                origin = ProjectClassOrigin.MAIN,
                modifiers = emptySet(),
                annotations = emptySet(),
                superTypes = emptySet(),
                interfaces = emptySet(),
                dependencies = if (index + 1 == size) emptyList() else listOf(
                    ClassDependency(
                        targetQualifiedName = "benchmark.Synthetic${index + 1}",
                        type = RelationshipType.CALLS,
                        evidence = "synthetic-benchmark",
                        confidence = RelationshipConfidence.HIGH
                    )
                ),
                classification = fallback
            )
        }
    }
}
