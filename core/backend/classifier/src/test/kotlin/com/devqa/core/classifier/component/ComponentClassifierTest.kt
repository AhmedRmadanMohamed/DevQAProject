package com.devqa.core.classifier.component

import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.model.type.ComponentType
import com.devqa.core.scanner.ProjectScanner
import com.devqa.core.scanner.ProjectFileScanner
import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileIntelligenceLimits
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.feature.projectbrain.domain.ProjectBrainLimits
import com.devqa.feature.projectbrain.application.ProjectAnalysisService
import com.devqa.feature.projectbrain.presentation.ProjectBrainFrontendService
import com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebAssets
import com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebSnapshotMapper
import com.devqa.feature.projectbrain.application.ProjectBrainQueryService
import com.google.gson.JsonParser
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.system.measureTimeMillis

class ComponentClassifierTest : BasePlatformTestCase() {

    fun testClassificationContractEndToEnd() {
        val pluginDescriptor = requireNotNull(
            PluginManagerCore.findPlugin(PluginId.getId("com.devqa.DevQA"))
        )
        assertTrue(DynamicPlugins.allowLoadUnloadWithoutRestart(pluginDescriptor))
        assertNotNull(project.getService(ProjectAnalysisService::class.java))
        assertNotNull(project.getService(ProjectBrainFrontendService::class.java))
        addJavaClass(
            "org/springframework/stereotype/Service.java",
            "package org.springframework.stereotype; public @interface Service {}"
        )
        addJavaClass(
            "org/springframework/web/bind/annotation/RestController.java",
            "package org.springframework.web.bind.annotation; public @interface RestController {}"
        )
        addJavaClass(
            "org/springframework/data/repository/Repository.java",
            "package org.springframework.data.repository; " +
                "public interface Repository<T, ID> {}"
        )

        addJavaClass(
            "sample/ApiController.java",
            "package sample; " +
                "@org.springframework.web.bind.annotation.RestController " +
                "public @interface ApiController {}"
        )

        val directService = addJavaClass(
            "sample/DirectService.java",
            "package sample; " +
                "@org.springframework.stereotype.Service public class DirectService {}"
        )
        val metaController = addJavaClass(
            "sample/MetaEndpoint.java",
            "package sample; @ApiController public class MetaEndpoint {}"
        )
        val repository = addJavaClass(
            "sample/UserRepository.java",
            "package sample; " +
                "public interface UserRepository " +
                "extends org.springframework.data.repository.Repository<String, Long> {}"
        )
        addJavaClass(
            "sample/BaseLayer.java",
            "package sample; " +
                "@org.springframework.stereotype.Service public class BaseLayer {}"
        )
        val inheritedService = addJavaClass(
            "sample/ChildLayer.java",
            "package sample; public class ChildLayer extends BaseLayer {}"
        )
        val weakAlternative = addJavaClass(
            "sample/OrderDto.java",
            "package sample; " +
                "@org.springframework.stereotype.Service public class OrderDto {}"
        )
        val ambiguous = addJavaClass(
            "sample/DualRole.java",
            "package sample; " +
                "@org.springframework.web.bind.annotation.RestController " +
                "@org.springframework.stereotype.Service public class DualRole {}"
        )
        val plainClass = addJavaClass(
            "sample/PlainClass.java",
            "package sample; public class PlainClass {}"
        )
        myFixture.addFileToProject(
            "sample/KotlinService.kt",
            "package sample\n" +
                "@org.springframework.stereotype.Service class KotlinService"
        )
        myFixture.addFileToProject(
            "notes/project-context.strange_extension",
            "DevQA should read this text file without a registered extension."
        )
        myFixture.addFileToProject(
            "pom.xml",
            "<project><dependencies>" +
                "<dependency><groupId>org.springframework.cloud</groupId>" +
                "<artifactId>spring-cloud-starter</artifactId></dependency>" +
                "<dependency><groupId>org.acme</groupId>" +
                "<artifactId>acme-framework</artifactId></dependency>" +
                "</dependencies></project>"
        )
        addJavaClass(
            "sample/Collaborator.java",
            "package sample; public class Collaborator { public void work() {} }"
        )
        addJavaClass(
            "sample/Consumer.java",
            "package sample; public class Consumer { " +
                "private final Collaborator collaborator; " +
                "public Consumer(Collaborator collaborator) { this.collaborator = collaborator; } " +
                "public void run() { collaborator.work(); } }"
        )

        val classifier = ComponentClassifier()
        val directResult = classifier.classify(directService)
        val metaResult = classifier.classify(metaController)
        val repositoryResult = classifier.classify(repository)
        val inheritedResult = classifier.classify(inheritedService)
        val weakAlternativeResult = classifier.classify(weakAlternative)
        val ambiguousResult = classifier.classify(ambiguous)
        val fallbackResult = classifier.classify(plainClass)
        val namingOnly = addJavaClass(
            "sample/UnannotatedMapper.java",
            "package sample; public class UnannotatedMapper {}"
        )
        val namingOnlyResult = classifier.classify(namingOnly)

        assertEquals(ComponentType.SERVICE, directResult.primaryType)
        assertTrue(
            directResult.candidates.single { it.type == ComponentType.SERVICE }
                .evidence.any { it.kind == ClassificationEvidenceKind.DIRECT_ANNOTATION }
        )

        assertEquals(ComponentType.CONTROLLER, metaResult.primaryType)
        assertTrue(
            metaResult.candidates.single { it.type == ComponentType.CONTROLLER }
                .evidence.any { it.kind == ClassificationEvidenceKind.META_ANNOTATION }
        )

        assertEquals(ComponentType.REPOSITORY, repositoryResult.primaryType)
        assertTrue(
            repositoryResult.candidates.single { it.type == ComponentType.REPOSITORY }
                .evidence.any { it.kind == ClassificationEvidenceKind.IMPLEMENTED_INTERFACE }
        )

        assertEquals(ComponentType.SERVICE, inheritedResult.primaryType)
        assertTrue(
            inheritedResult.candidates.single { it.type == ComponentType.SERVICE }
                .evidence.any { it.kind == ClassificationEvidenceKind.INHERITED_ANNOTATION }
        )

        assertEquals(ComponentType.SERVICE, weakAlternativeResult.primaryType)
        assertFalse(weakAlternativeResult.ambiguous)
        assertTrue(weakAlternativeResult.candidates.any { it.type == ComponentType.DTO })

        assertEquals(ComponentType.CONTROLLER, ambiguousResult.primaryType)
        assertTrue(ambiguousResult.ambiguous)
        assertEquals(
            ambiguousResult.primaryType,
            ComponentClassificationPolicy.classify(
                ambiguousResult.candidates.flatMap { it.evidence }.reversed()
            ).primaryType
        )

        assertEquals(ComponentType.OTHER, fallbackResult.primaryType)
        assertEquals(ComponentType.OTHER, namingOnlyResult.primaryType)
        assertEquals(com.devqa.core.model.classification.ClassificationConfidence.NONE, namingOnlyResult.confidence)
        assertTrue(namingOnlyResult.candidates.single().evidence.single().matchedSymbol.contains("insufficient-evidence"))
        assertFalse(fallbackResult.ambiguous)
        assertEquals(
            ClassificationEvidenceKind.FALLBACK,
            fallbackResult.candidates.single().evidence.single().kind
        )

        val scanResult = ProjectScanner.scan(project)
        assertEquals("Spring Cloud", scanResult.frameworkInventory.primary?.name)
        assertTrue(
            scanResult.frameworkInventory.frameworks.joinToString { it.name },
            scanResult.frameworkInventory.frameworks.any { it.name == "Acme" }
        )
        assertFalse(scanResult.springBoot)
        assertTrue("DirectService" in scanResult.services)
        assertTrue("MetaEndpoint" in scanResult.controllers)
        assertTrue("UserRepository" in scanResult.repositories)
        assertTrue(
            scanResult.allClasses.any {
                it.name == "KotlinService" && it.language == ProjectLanguage.KOTLIN
            }
        )
        assertTrue(scanResult.allClasses.all { it.id.length == 64 })
        assertTrue(
            scanResult.relationships.any {
                it.sourceQualifiedName == "sample.ChildLayer" &&
                    it.targetQualifiedName == "sample.BaseLayer"
            }
        )
        assertFalse(
            scanResult.relationships.any {
                it.sourceQualifiedName == "sample.Consumer" &&
                    it.targetQualifiedName == "sample.Collaborator" &&
                    it.type == RelationshipType.INJECTS
            }
        )
        assertTrue(
            scanResult.relationships.any {
                it.sourceQualifiedName == "sample.Consumer" &&
                    it.targetQualifiedName == "sample.Collaborator" &&
                    it.type == RelationshipType.CALLS
            }
        )
        assertEquals(
            scanResult.classesOf(ComponentType.SERVICE).map { it.name },
            scanResult.services
        )

        val scannedFiles = ProjectFileScanner().scan(project)
        val unknownTextFile = scannedFiles.single {
            it.relativePath.endsWith("notes/project-context.strange_extension")
        }
        assertEquals(FileContentKind.TEXT, unknownTextFile.intelligence.contentKind)
        assertTrue(
            unknownTextFile.intelligence.capability in setOf(
                FileAnalysisCapability.PLAIN_TEXT,
                FileAnalysisCapability.PSI
            )
        )
        assertTrue(
            unknownTextFile.intelligence.sampledBytes <= FileIntelligenceLimits.MAX_SAMPLE_BYTES
        )
        assertEquals("Java", ProjectAnalysisResult(scanResult, scannedFiles).primaryLanguage.name)

        val analysisResult = ProjectAnalysisResult(scanResult, scannedFiles)
        val webSnapshot = ProjectBrainWebSnapshotMapper.toJson("fixture-project", analysisResult)
        assertTrue(webSnapshot.contains("\"primaryFramework\":{\"name\":\"Spring Cloud\""))
        assertTrue(webSnapshot.contains("\"primaryLanguage\":{\"name\":\"Java\""))
        assertTrue(webSnapshot.contains("\"schemaVersion\":2"))
        assertTrue(webSnapshot.contains("\"components\":[]"))
        val paged = JsonParser.parseString(ProjectBrainQueryService.query(
            analysisResult,
            """{"kind":"components","limit":2,"offset":0,"filters":{"role":["SERVICE"]}}"""
        )).asJsonObject
        assertEquals(2, paged.getAsJsonArray("items").size())
        assertTrue(paged.get("total").asInt >= 2)
        assertTrue(paged.getAsJsonArray("items").all { it.asJsonObject.get("type").asString == "SERVICE" })
        val multiSelected = JsonParser.parseString(ProjectBrainQueryService.query(
            analysisResult,
            """{"kind":"components","filters":{"role":["SERVICE","REPOSITORY"]},"limit":100}"""
        )).asJsonObject
        assertTrue(multiSelected.get("total").asInt >= paged.get("total").asInt)
        assertTrue(multiSelected.getAsJsonArray("items").all {
            it.asJsonObject.get("type").asString in setOf("SERVICE", "REPOSITORY")
        })
        val filesQuery = JsonParser.parseString(ProjectBrainQueryService.query(
            analysisResult,
            """{"kind":"files","search":"strange_extension","limit":10}"""
        )).asJsonObject
        assertEquals(1, filesQuery.get("total").asInt)
        val graph = JsonParser.parseString(ProjectBrainQueryService.query(
            analysisResult,
            """{"kind":"diagram","view":"layers"}"""
        )).asJsonObject
        assertTrue(graph.getAsJsonArray("nodes").size() < scanResult.allClasses.size)
        val productionFrontend = ProjectBrainWebAssets.html("window.DevQAIntellijBridge = {};")
        assertTrue(productionFrontend.contains("Engineering Intelligence"))
        assertTrue(productionFrontend.contains("Workspace Overview"))
        // JCEF now embeds React for every Service 01 route. Legacy workspace.js
        // globals no longer exist and must never be required for bootstrapping.
        assertTrue(productionFrontend.contains("<div id=\"app-root\" aria-live=\"polite\"></div>"))
        assertTrue(productionFrontend.contains("data-react-view"))
        assertTrue(productionFrontend.contains("Project Files"))
        assertTrue(productionFrontend.contains("Framework Inventory"))
        assertTrue(productionFrontend.contains("Architecture Explorer"))
        assertTrue(productionFrontend.contains("Project Scan Settings"))
        assertFalse(productionFrontend.contains("let activeView = 'dashboard'"))
        assertFalse(productionFrontend.contains("renderProjectBrain"))
        assertTrue(productionFrontend.contains("receiveDevQAProjectBrainSnapshot"))
        assertTrue(productionFrontend.contains("frontendReady"))

        val liveAddedClass = addJavaClass(
            "sample/LiveAddedService.java",
            "package sample; public class LiveAddedService extends DirectService {}"
        )
        val liveResult = ProjectScanner.rescan(
            project,
            scanResult,
            setOf(liveAddedClass.containingFile.virtualFile.path)
        )
        assertEquals(scanResult.modelVersion + 1, liveResult.modelVersion)
        assertEquals(scanResult.javaFiles + 1, liveResult.javaFiles)
        assertTrue(liveAddedClass.containingFile.virtualFile.path in liveResult.sourceFilePaths)
        assertTrue(liveResult.allClasses.any { it.name == "LiveAddedService" })
        assertTrue(
            liveResult.relationships.any {
                it.sourceQualifiedName == "sample.LiveAddedService" &&
                    it.targetQualifiedName == "sample.DirectService"
            }
        )

        val fallbackClassification = classifier.classify(plainClass)
        val benchmarkClasses = (0 until 10_000).map { index ->
            ProjectClass(
                id = index.toString().padStart(64, '0'),
                name = "Class$index",
                qualifiedName = "benchmark.Class$index",
                moduleName = "benchmark",
                packageName = "benchmark",
                sourcePath = "/benchmark/Class$index.java",
                sourceOffset = 0,
                language = ProjectLanguage.JAVA,
                origin = ProjectClassOrigin.MAIN,
                modifiers = emptySet(),
                annotations = emptySet(),
                superTypes = emptySet(),
                interfaces = emptySet(),
                dependencies = if (index == 9_999) {
                    emptyList()
                } else {
                    listOf(
                        ClassDependency(
                            targetQualifiedName = "benchmark.Class${index + 1}",
                            type = RelationshipType.CALLS,
                            evidence = "benchmark-call",
                            confidence = RelationshipConfidence.HIGH
                        )
                    )
                },
                classification = fallbackClassification
            )
        }
        lateinit var benchmarkRelationships: List<com.devqa.core.model.ProjectRelationship>
        val benchmarkMillis = measureTimeMillis {
            benchmarkRelationships = ProjectModelAssembler.relationships(benchmarkClasses)
        }
        assertEquals(9_999, benchmarkRelationships.size)
        assertTrue(
            "10k relationship benchmark exceeded ${ProjectBrainLimits.TARGET_SCAN_MILLIS_10K}ms",
            benchmarkMillis < ProjectBrainLimits.TARGET_SCAN_MILLIS_10K
        )
        val benchmarkResult = analysisResult.copy(scanResult = scanResult.copy(
            allClasses = benchmarkClasses, relationships = benchmarkRelationships
        ))
        val queryMillis = measureTimeMillis {
            val page = JsonParser.parseString(ProjectBrainQueryService.query(
                benchmarkResult, """{"kind":"components","offset":9950,"limit":40}"""
            )).asJsonObject
            assertEquals(10_000, page.get("total").asInt)
            assertEquals(40, page.getAsJsonArray("items").size())
        }
        assertTrue("10k-component query took ${queryMillis}ms", queryMillis < ProjectBrainLimits.TARGET_SCAN_MILLIS_10K)
    }

    private fun addJavaClass(path: String, source: String): PsiClass =
        (myFixture.addFileToProject(path, source) as PsiJavaFile).classes.single()
}
