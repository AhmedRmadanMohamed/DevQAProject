package com.devqa.core.analyzer

import com.devqa.core.model.ArchitectureStyle
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.FrameworkDependencyOrigin
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.classification.ComponentCandidate
import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTechnologyAndArchitectureTest {
    @Test fun `maven property declaration identifies exact Spring Boot version`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf("app/pom.xml" to """
            <project><properties><spring.version>3.4.2</spring.version></properties><dependencies>
            <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>
            <version>${'$'}{spring.version}</version></dependency></dependencies></project>
        """))
        assertEquals("Spring Boot", inventory.primary?.name)
        assertEquals("3.4.2", inventory.primary?.version)
        assertEquals("app/pom.xml", inventory.primary?.declaredVersions?.single()?.path)
    }

    @Test fun `gradle plugin version is evidence and unknown variable is not fabricated`() {
        val fixed = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "build.gradle.kts" to """plugins { id("org.springframework.boot") version "3.5.1" }"""
        ))
        assertEquals("3.5.1", fixed.primary?.version)
        val dynamic = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "build.gradle.kts" to """plugins { id("org.springframework.boot") version "${'$'}{bootVersion}" }"""
        ))
        assertNull(dynamic.primary?.version)
    }

    @Test fun `conflicting module versions do not become one invented version`() {
        fun pom(version: String) = """<project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>$version</version></parent></project>"""
        val detected = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "module-a/pom.xml" to pom("3.3.6"), "module-b/pom.xml" to pom("3.4.2")
        )).primary!!
        assertNull(detected.version)
        assertEquals(setOf("3.3.6", "3.4.2"), detected.declaredVersions.map { it.value }.toSet())
    }

    @Test fun `npm version ranges are not confused with installed versions`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf("web/package.json" to """
            {"dependencies":{"next":"15.3.2","react":"^19.0.0"}}
        """))
        assertEquals("Next.js", inventory.primary?.name)
        assertEquals("15.3.2", inventory.primary?.version)
        assertNull(inventory.frameworks.first { it.name == "React" }.version)
    }

    @Test fun `declared Spring Cloud platform outranks incidental Spring Framework imports`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "app/pom.xml" to """
                <project><dependencies><dependency>
                  <groupId>org.springframework.cloud</groupId>
                  <artifactId>spring-cloud-starter</artifactId>
                </dependency></dependencies></project>
            """.trimIndent(),
            "app/src/main/java/App.java" to
                "import org.springframework.stereotype.Service; class App {}"
        ))

        assertTrue(inventory.frameworks.any { it.name == "Spring Cloud" })
        assertTrue(inventory.frameworks.any { it.name == "Spring Framework" })
        assertEquals("Spring Cloud", inventory.primary?.name)
    }

    @Test fun `Spring Batch workload is primary when Boot and Cloud support it`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "app/pom.xml" to """
                <project><parent><groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-parent</artifactId><version>3.5.1</version></parent>
                  <dependencies>
                    <dependency><groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-task</artifactId></dependency>
                    <dependency><groupId>org.springframework.batch</groupId>
                      <artifactId>spring-batch-core</artifactId></dependency>
                  </dependencies>
                </project>
            """.trimIndent(),
            "app/src/main/java/BatchConfiguration.java" to
                "import org.springframework.batch.core.Job; class BatchConfiguration {}"
        ))

        assertEquals("Spring Batch", inventory.primary?.name)
        assertTrue(inventory.frameworks.any { it.name == "Spring Boot" })
        assertTrue(inventory.frameworks.any { it.name == "Spring Cloud" })
    }

    @Test fun `source imports alone prove usage but not a library version`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf(
            "src/main/java/App.java" to "import org.springframework.boot.SpringApplication;"
        ))
        assertEquals("Spring Boot", inventory.primary?.name)
        assertNull(inventory.primary?.version)
    }

    @Test fun `Maven direct dependencies stay distinct from managed and parent declarations`() {
        val detected = ProjectTechnologyAnalyzer.detectFromSources(listOf("app/pom.xml" to """
            <project><parent><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId><version>3.5.0</version></parent>
            <dependencyManagement><dependencies><dependency>
            <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId>
            <version>3.5.0</version></dependency></dependencies></dependencyManagement>
            <dependencies><dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>
        """)).frameworks.first { it.name == "Spring Boot" }
        assertTrue(detected.dependencyDeclarations.any {
            it.coordinate.endsWith(":spring-boot-starter-web") && it.origin == FrameworkDependencyOrigin.DIRECT
        })
        assertTrue(detected.dependencyDeclarations.any {
            it.coordinate.endsWith(":spring-boot-starter-security") && it.origin == FrameworkDependencyOrigin.MANAGED
        })
        assertTrue(detected.dependencyDeclarations.any {
            it.coordinate.endsWith(":spring-boot-starter-parent") && it.origin == FrameworkDependencyOrigin.PARENT
        })
    }

    @Test fun `Hibernate ORM is discovered by actual declared dependency evidence`() {
        val detected = ProjectTechnologyAnalyzer.detectFromSources(listOf("orm/pom.xml" to """
            <project><dependencies><dependency><groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-core</artifactId><version>6.6.4.Final</version>
            </dependency></dependencies></project>
        """)).frameworks.first { it.name == "Hibernate ORM" }
        assertEquals("6.6.4.Final", detected.version)
        assertEquals(FrameworkDependencyOrigin.DIRECT, detected.dependencyDeclarations.single().origin)
    }

    @Test fun `Gradle declarations only claim direct when the configuration is explicit`() {
        val declared = ManifestDependencyParser.parse("build.gradle.kts", """
            dependencies {
              implementation("org.springframework.boot:spring-boot-starter-web:3.5.1")
              // implementation("org.springframework.boot:fake-comment:1.0.0")
            }
            val note = "org.springframework.boot:spring-boot-starter-data-jpa:3.5.1"
            plugins { id("org.springframework.boot") version "3.5.1" }
        """)
        assertEquals(ManifestDependencyParser.Origin.DIRECT,
            declared.first { it.name == "spring-boot-starter-web" }.origin)
        assertTrue(declared.none { it.name == "spring-boot-starter-data-jpa" })
        assertEquals(ManifestDependencyParser.Origin.PLUGIN,
            declared.first { it.name == "org.springframework.boot" }.origin)
        assertTrue(declared.none { it.name == "fake-comment" })
    }

    @Test fun `commented or arbitrary Gradle strings never create a framework or version`() {
        val inventory = ProjectTechnologyAnalyzer.detectFromSources(listOf("build.gradle.kts" to """
            // implementation("org.springframework.boot:spring-boot-starter-web:3.5.1")
            val example = "org.springframework.boot:spring-boot-starter-security:3.5.1"
        """))
        assertTrue(inventory.frameworks.isEmpty())
    }

    @Test fun `Go indirect marker identifies transitive declarations without inventing dependency versions`() {
        val declared = ManifestDependencyParser.parse("go.mod", """
            module my.example/app
            go 1.23
            require (
              github.com/example/framework v1.2.3
              github.com/example/server v1.2.0 // indirect
            )
        """)
        assertEquals(ManifestDependencyParser.Origin.DIRECT,
            declared.first { it.name == "framework" }.origin)
        assertEquals(ManifestDependencyParser.Origin.TRANSITIVE,
            declared.first { it.name == "server" }.origin)
    }

    @Test fun `malformed or DTD bearing Maven manifests do not trigger XML entity resolution`() {
        val untrusted = """<!DOCTYPE project [<!ENTITY external SYSTEM "file:///etc/passwd">]>
            <project><dependencies><dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId><version>&external;</version>
            </dependency></dependencies></project>"""
        assertTrue(ManifestDependencyParser.parse("pom.xml", untrusted).isEmpty())
        assertTrue(ManifestDependencyParser.parse("pom.xml", "<project><dependencies>").isEmpty())
    }

    @Test fun `three layer names without resolved edges are only partial evidence`() {
        val classes = listOf(cls("api", ComponentType.CONTROLLER), cls("business", ComponentType.SERVICE),
            cls("data", ComponentType.REPOSITORY))
        val profile = ProjectModelAssembler.architecture(classes, emptyList())
        assertEquals(ArchitectureStyle.PARTIAL_LAYERED, profile.style)
    }

    @Test fun `resolved controller to service to repository chain verifies layered architecture`() {
        val controller = cls("api", ComponentType.CONTROLLER, calls = listOf("sample.business"))
        val service = cls("business", ComponentType.SERVICE, calls = listOf("sample.data"))
        val repository = cls("data", ComponentType.REPOSITORY)
        val classes = listOf(controller, service, repository)
        val profile = ProjectModelAssembler.architecture(classes, ProjectModelAssembler.relationships(classes))
        assertEquals(ArchitectureStyle.LAYERED, profile.style)
        assertEquals(2, profile.crossModuleRelationships)
        assertEquals(1, profile.modules.single { it.name == "service" }.incomingRelationships)
        assertEquals(1, profile.modules.single { it.name == "service" }.outgoingRelationships)
    }

    @Test fun `hexagonal style requires resolved adapter port dependency and a domain`() {
        val port = cls("port", ComponentType.OTHER, pkg = "sample.application.port")
        val adapter = cls("adapter", ComponentType.OTHER, pkg = "sample.infrastructure.adapter",
            calls = listOf("sample.port"))
        val domain = cls("model", ComponentType.ENTITY, pkg = "sample.domain")
        val classes = listOf(port, adapter, domain)
        val profile = ProjectModelAssembler.architecture(classes, ProjectModelAssembler.relationships(classes))
        assertEquals(ArchitectureStyle.HEXAGONAL, profile.style)
        assertTrue(profile.evidence.any { it.startsWith("adapter-to-port:") })
    }

    private fun cls(id: String, type: ComponentType, pkg: String = "sample", calls: List<String> = emptyList()): ProjectClass {
        val evidence = ClassificationEvidence(type, ClassificationEvidenceKind.DIRECT_ANNOTATION, "test:$id")
        val classification = ComponentClassification(type, ClassificationConfidence.HIGH,
            listOf(ComponentCandidate(type, 100, ClassificationConfidence.HIGH, listOf(evidence))), false)
        return ProjectClass(id, id, "sample.$id", when (id) {
            "api" -> "api"
            "business" -> "service"
            "data" -> "storage"
            else -> "core"
        }, pkg, "/$id.java", 0, ProjectLanguage.JAVA, ProjectClassOrigin.MAIN,
            emptySet(), emptySet(), emptySet(), emptySet(),
            calls.map { ClassDependency(it, RelationshipType.CALLS, "call:$id", RelationshipConfidence.HIGH) }, classification)
    }
}
