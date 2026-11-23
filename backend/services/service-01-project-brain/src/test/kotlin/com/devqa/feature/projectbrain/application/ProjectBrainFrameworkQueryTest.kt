package com.devqa.feature.projectbrain.application

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.fileintelligence.FileAnalysisCapability
import com.devqa.core.fileintelligence.FileContentKind
import com.devqa.core.fileintelligence.FileDetectionConfidence
import com.devqa.core.fileintelligence.FileIntelligenceProfile
import com.devqa.core.model.DetectedFramework
import com.devqa.core.model.FrameworkDependencyDeclaration
import com.devqa.core.model.FrameworkDependencyOrigin
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectFile
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.classification.ComponentCandidate
import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType
import com.devqa.core.model.type.ProjectFileType
import com.devqa.core.model.type.ProjectSourceType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBrainFrameworkQueryTest {
    private val dependencies = listOf(
        FrameworkDependencyDeclaration("org.springframework.security:spring-security-config", "auth/pom.xml",
            FrameworkDependencyOrigin.DIRECT),
        FrameworkDependencyDeclaration("org.springframework.security:spring-security-bom", "auth/pom.xml",
            FrameworkDependencyOrigin.MANAGED, "6.5.0")
    )
    private val framework = DetectedFramework("Spring Security", ClassificationConfidence.HIGH,
        listOf("auth/src/AuthConfig.java:org.springframework.security", "auth/pom.xml:org.springframework.security"),
        dependencyDeclarations = dependencies)

    @Test fun `framework search and combined module confidence category filters come from real module evidence`() {
        val result = model()
        val filtered = query(result, """{"kind":"frameworks","search":"security","filters":{
            "module":["auth"],"confidence":["HIGH"],"category":["Spring ecosystem"]
        }}""")
        assertEquals(1, filtered.get("total").asInt)
        assertEquals("Unknown", filtered.getAsJsonArray("items").single().asJsonObject.get("version").asString)
        assertEquals(listOf("auth"), filtered.getAsJsonArray("items").single().asJsonObject
            .getAsJsonArray("modules").map { it.asString })
        assertTrue(!filtered.getAsJsonArray("items").single().asJsonObject.has("dependencies"))
        assertEquals(0, query(result, """{"kind":"frameworks","filters":{"module":["billing"]}}""")
            .get("total").asInt)
        assertEquals(listOf("auth"), ProjectBrainQueryService.facets(result).getValue("frameworks").getValue("module"))
    }

    @Test fun `details show only exact evidence paths and never mix identically named module classes`() {
        val detail = query(model(), """{"kind":"framework-detail","name":"Spring Security"}""")
            .getAsJsonObject("item")
        assertEquals(listOf("app.AuthConfig"), detail.getAsJsonArray("relatedClasses").map { it.asString })
        assertEquals(listOf("auth/pom.xml"), detail.getAsJsonArray("configurationFiles").map { it.asString })
        assertEquals(1, detail.getAsJsonArray("directDependencies").size())
        assertEquals(0, detail.getAsJsonArray("transitiveDependencies").size())
        assertEquals("MANAGED", detail.getAsJsonArray("otherDeclarations").single()
            .asJsonObject.get("origin").asString)
        assertEquals("MANIFEST_ONLY", detail.get("dependencyResolution").asString)
        assertEquals("/repo/auth/src/AuthConfig.java", detail.getAsJsonArray("classSources").single()
            .asJsonObject.get("path").asString)
    }

    @Test fun `unknown framework lookup cannot invent a match`() {
        assertTrue(query(model(), """{"kind":"framework-detail","name":"Other"}""").get("item").isJsonNull)
    }

    @Test fun `large multi-framework inventory associates evidence with exact modules`() {
        val baseline = model()
        val extraFiles = (0 until 10_000).map { index ->
            file("pom.xml", "module-$index", ProjectFileType.MAVEN)
        }
        val otherFrameworks = (0 until 32).map { index ->
            DetectedFramework("Framework $index", ClassificationConfidence.MEDIUM,
                listOf("module-$index/pom.xml:declared-coordinate"))
        }
        val result = baseline.copy(
            files = baseline.files + extraFiles,
            scanResult = baseline.scanResult.copy(frameworkInventory =
                FrameworkInventory(framework, listOf(framework) + otherFrameworks))
        )
        val page = query(result, """{"kind":"frameworks","limit":100}""")
        assertEquals(33, page.get("total").asInt)
        val byName = page.getAsJsonArray("items").associateBy { it.asJsonObject.get("name").asString }
        otherFrameworks.forEachIndexed { index, item ->
            assertEquals(listOf("module-$index"), byName.getValue(item.name).asJsonObject
                .getAsJsonArray("modules").map { it.asString })
        }
        val modules = ProjectBrainQueryService.facets(result).getValue("frameworks").getValue("module")
        assertEquals(33, modules.size)
        assertTrue("auth" in modules)
        assertEquals(1, query(result, """{"kind":"frameworks","filters":{
            "module":["module-18"]}}""").get("total").asInt)
    }

    private fun query(model: ProjectAnalysisResult, json: String) =
        JsonParser.parseString(ProjectBrainQueryService.query(model, json)).asJsonObject

    private fun model(): ProjectAnalysisResult {
        val files = listOf(
            file("AuthConfig.java", "auth", ProjectFileType.JAVA_SOURCE),
            file("pom.xml", "auth", ProjectFileType.MAVEN),
            file("AuthConfig.java", "billing", ProjectFileType.JAVA_SOURCE)
        )
        val classes = listOf(cls("auth"), cls("billing"))
        return ProjectAnalysisResult(ProjectScanResult(9, false, 2, 2, 0, 0,
            classes, emptyList(), ProjectModelAssembler.architecture(classes),
            FrameworkInventory(framework, listOf(framework)), emptyList(),
            ProjectScanMetrics(1, files.size, classes.size, 0, 0, 0)), files)
    }

    private fun file(name: String, module: String, type: ProjectFileType): ProjectFile =
        ProjectFile(name, "/repo/$module/src/$name".replace("/src/pom.xml", "/pom.xml"),
            "$module/src/$name".replace("/src/pom.xml", "/pom.xml"), type, ProjectSourceType.MAIN,
            FileIntelligenceProfile("TEXT", "JAVA", FileContentKind.TEXT, "text/plain",
                FileAnalysisCapability.PLAIN_TEXT, FileDetectionConfidence.MEDIUM, emptyList(), 0, 12), module)

    private fun cls(module: String): ProjectClass {
        val evidence = ClassificationEvidence(ComponentType.OTHER, ClassificationEvidenceKind.FALLBACK, "none")
        val classification = ComponentClassification(ComponentType.OTHER, ClassificationConfidence.NONE,
            listOf(ComponentCandidate(ComponentType.OTHER, 0, ClassificationConfidence.NONE, listOf(evidence))), false)
        return ProjectClass("$module:AuthConfig", "AuthConfig", "app.AuthConfig", module, "app",
            "/repo/$module/src/AuthConfig.java", 0, ProjectLanguage.JAVA, ProjectClassOrigin.MAIN,
            emptySet(), emptySet(), emptySet(), emptySet(), emptyList(), classification)
    }
}
