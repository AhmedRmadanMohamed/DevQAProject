package com.devqa.feature.projectbrain.application

import com.devqa.core.analyzer.ProjectModelAssembler
import com.devqa.core.model.ClassDependency
import com.devqa.core.model.FrameworkInventory
import com.devqa.core.model.ProjectAnalysisResult
import com.devqa.core.model.ProjectClass
import com.devqa.core.model.ProjectClassOrigin
import com.devqa.core.model.ProjectLanguage
import com.devqa.core.model.ProjectScanMetrics
import com.devqa.core.model.ProjectScanResult
import com.devqa.core.model.RelationshipConfidence
import com.devqa.core.model.RelationshipType
import com.devqa.core.model.classification.ClassificationConfidence
import com.devqa.core.model.classification.ClassificationEvidence
import com.devqa.core.model.classification.ClassificationEvidenceKind
import com.devqa.core.model.classification.ComponentCandidate
import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.model.type.ComponentType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBrainComponentsQueryTest {
    @Test fun `snapshot exposes exact role classification counts separately for each module`() {
        val project = model(listOf(
            cls("FirstController", "fetcher", ComponentType.CONTROLLER),
            cls("FirstService", "fetcher", ComponentType.SERVICE),
            cls("SecondService", "bpm-svc", ComponentType.SERVICE),
            cls("AnotherService", "bpm-svc", ComponentType.SERVICE),
            cls("OtherType", "hidden", ComponentType.OTHER)
        ))
        val summary = JsonParser.parseString(
            com.devqa.feature.projectbrain.presentation.web.ProjectBrainWebSnapshotMapper
                .toJson("enterprise", project)
        ).asJsonObject
        val modules = summary.getAsJsonArray("modules").associate { entry ->
            val obj = entry.asJsonObject
            obj.get("name").asString to obj
        }
        assertEquals(3, modules.size)
        assertEquals(2, modules.getValue("fetcher").get("classes").asInt)
        assertEquals(1, modules.getValue("fetcher").getAsJsonObject("roleCounts").get("CONTROLLER").asInt)
        assertEquals(1, modules.getValue("fetcher").getAsJsonObject("roleCounts").get("SERVICE").asInt)
        assertEquals(2, modules.getValue("bpm-svc").getAsJsonObject("roleCounts").get("SERVICE").asInt)
        assertEquals(1, modules.getValue("hidden").getAsJsonObject("roleCounts").get("OTHER").asInt)
        assertEquals(5, summary.get("metrics").asJsonObject.get("classes").asInt)
    }

    @Test fun `combined role module language confidence filters and filename search return actual classes`() {
        val matches = listOf(
            cls("OrderService", "orders", ComponentType.SERVICE),
            cls("OrderRepo", "orders", ComponentType.REPOSITORY),
            cls("BillingService", "billing", ComponentType.SERVICE),
            cls("OrderServiceTest", "orders", ComponentType.SERVICE, ProjectLanguage.KOTLIN)
        )
        val result = query(model(matches), """{"kind":"components","search":"Order","filters":{
            "role":["SERVICE","REPOSITORY"],"module":["orders"],"language":["JAVA"],"confidence":["HIGH"]
        }}""")
        assertEquals(2, result.get("total").asInt)
        val names = result.getAsJsonArray("items").map { it.asJsonObject.get("name").asString }.toSet()
        assertEquals(setOf("OrderService", "OrderRepo"), names)
        val fileSearch = query(model(matches), """{"kind":"components","search":"OrderRepo.java"}""")
        assertEquals("OrderRepo", fileSearch.getAsJsonArray("items").single().asJsonObject.get("name").asString)
    }

    @Test fun `component page is bounded but total remains exact`() {
        val classes = (0 until 200).map { cls("Class$it", "app", ComponentType.SERVICE) }
        val result = query(model(classes), """{"kind":"components","offset":80,"limit":20}""")
        assertEquals(200, result.get("total").asInt)
        assertEquals(20, result.getAsJsonArray("items").size())
        assertTrue(result.get("hasMore").asBoolean)
        assertEquals(80, result.get("offset").asInt)
    }

    @Test fun `class options search discovers classes beyond the bounded facet catalog`() {
        val classes = (0 until 260).map { cls("Class${it.toString().padStart(3, '0')}", "app", ComponentType.SERVICE) }
        val result = query(model(classes), """{"kind":"component-options","search":"Class259"}""")
        assertEquals(1, result.get("total").asInt)
        assertEquals("Class259", result.getAsJsonArray("items").single().asString)
        assertEquals(200, ProjectBrainQueryService.facets(model(classes)).getValue("components").getValue("class").size)
    }


    @Test fun `server groups modules alphabetically then classes consistently across pages`() {
        val classes = listOf(
            cls("ZooService", "orders", ComponentType.SERVICE),
            cls("PaymentService", "billing", ComponentType.SERVICE),
            cls("AlphaService", "orders", ComponentType.SERVICE),
            cls("AccountService", "billing", ComponentType.SERVICE)
        )
        val result = model(classes)
        val first = query(result, """{"kind":"components","limit":2,
            "filters":{"module":["orders","billing"]}}""")
        val second = query(result, """{"kind":"components","limit":2,"offset":2,
            "filters":{"module":["orders","billing"]}}""")
        val names = (first.getAsJsonArray("items") + second.getAsJsonArray("items")).map {
            val item = it.asJsonObject
            item.get("module").asString + "/" + item.get("name").asString
        }
        assertEquals(listOf("billing/AccountService","billing/PaymentService",
            "orders/AlphaService","orders/ZooService"), names)
        assertEquals(4, first.get("total").asInt)
        assertEquals(4, second.get("total").asInt)
        val desc = query(result, """{"kind":"components","sortNameDirection":"DESC"}""")
        assertEquals(listOf("PaymentService","AccountService","ZooService","AlphaService"),
            desc.getAsJsonArray("items").map { it.asJsonObject.get("name").asString })
    }

    @Test fun `comma separated multi class search never escapes explicit module filter`() {
        val result = query(model(listOf(
            cls("BillingService", "billing", ComponentType.SERVICE),
            cls("OrderService", "orders", ComponentType.SERVICE),
            cls("OrdersController", "orders", ComponentType.CONTROLLER)
        )), """{"kind":"components","search":"OrderService,BillingService",
            "filters":{"module":["orders"]}}""")
        assertEquals(1, result.get("total").asInt)
        assertEquals("OrderService", result.getAsJsonArray("items").single().asJsonObject.get("name").asString)
    }

    @Test fun `name search supports AND OR NOT and LIKE wildcard tokens inside selected modules`() {
        val result = model(listOf(
            cls("OrderService", "orders", ComponentType.SERVICE),
            cls("OrderServiceTest", "orders", ComponentType.SERVICE),
            cls("OrderController", "orders", ComponentType.CONTROLLER),
            cls("PaymentService", "payments", ComponentType.SERVICE)
        ))
        val match = query(result, """{"kind":"components","search":"Order AND Service NOT Test",
          "filters":{"module":["orders"]}}""")
        assertEquals(1, match.get("total").asInt)
        assertEquals("OrderService", match.getAsJsonArray("items").single().asJsonObject.get("name").asString)
        val or = query(result, """{"kind":"components","search":"OrderController OR PaymentService",
          "filters":{"module":["orders"]}}""")
        assertEquals("OrderController", or.getAsJsonArray("items").single().asJsonObject.get("name").asString)
        val like = query(result, """{"kind":"components","search":"LIKE %Controller",
          "filters":{"module":["orders"]}}""")
        assertEquals("OrderController", like.getAsJsonArray("items").single().asJsonObject.get("name").asString)
    }

    @Test fun `advanced LIKE IN OR AND NOT rules are bounded structured filters`() {
        val model = model(listOf(
            cls("OrderService", "orders", ComponentType.SERVICE),
            cls("OrderRepository", "orders", ComponentType.REPOSITORY),
            cls("PaymentService", "billing", ComponentType.SERVICE),
            cls("OrderServiceTest", "orders", ComponentType.SERVICE)
        ))
        val response = query(model, """{"kind":"components","filters":{"module":["orders"]},
          "advanced":{"rules":[
            {"field":"name","operator":"LIKE","value":"Order%","join":"AND","negate":false},
            {"field":"role","operator":"IN","value":"SERVICE,REPOSITORY","join":"AND","negate":false},
            {"field":"name","operator":"EQ","value":"OrderServiceTest","join":"AND","negate":true}
          ]}}""")
        assertEquals(listOf("OrderRepository","OrderService"),
            response.getAsJsonArray("items").map { it.asJsonObject.get("name").asString })
        val or = query(model, """{"kind":"components","filters":{"module":["orders"]},
          "advanced":{"rules":[
            {"field":"name","operator":"EQ","value":"OrderService","join":"AND"},
            {"field":"name","operator":"EQ","value":"OrderRepository","join":"OR"}
          ]}}""")
        assertEquals(2, or.get("total").asInt)
    }

    @Test fun `advanced numeric comparisons use real relationship counts and reject invalid operators`() {
        val result = model(listOf(
            cls("NoLinks", "orders", ComponentType.SERVICE),
            cls("Other", "orders", ComponentType.SERVICE)
        ))
        val numeric = query(result, """{"kind":"components",
          "advanced":{"rules":[{"field":"incoming","operator":"BETWEEN",
            "value":"0,0","join":"AND"}]}}""")
        assertEquals(2, numeric.get("total").asInt)
        val invalid = listOf(
            """{"kind":"components","advanced":{"rules":[{"field":"incoming","operator":"LIKE","value":"%","join":"AND"}]}}""",
            """{"kind":"components","advanced":{"rules":[{"field":"name","operator":"LIKE","value":"%","join":"XOR"}]}}""",
            """{"kind":"components","advanced":{"rules":[{"field":"name","operator":"IN","value":"","join":"AND"}]}}"""
        )
        invalid.forEach { request ->
            try {
                query(result, request)
                throw AssertionError("Invalid advanced search was accepted")
            } catch (_: IllegalArgumentException) {
                // Structured query validation rejects invalid input before evaluation.
            }
        }
    }

    @Test fun `component detail exposes real annotations inheritance and exact dependency IDs`() {
        val repo = cls("Repo", "data", ComponentType.REPOSITORY)
        val service = cls("OrderService", "orders", ComponentType.SERVICE).copy(
            annotations = setOf("org.springframework.stereotype.Service"),
            superTypes = setOf("sample.BaseService"),
            interfaces = setOf("sample.OrderPort"),
            dependencies = listOf(ClassDependency("sample.Repo", RelationshipType.INJECTS,
                "constructor:repo", RelationshipConfidence.HIGH, repo.sourcePath))
        )
        val detail = query(model(listOf(service, repo)), """{"kind":"component-detail","id":"OrderService"}""")
            .getAsJsonObject("item")
        assertEquals("org.springframework.stereotype.Service", detail.getAsJsonArray("annotations").single().asString)
        assertEquals("sample.BaseService", detail.getAsJsonArray("superTypes").single().asString)
        assertEquals("sample.OrderPort", detail.getAsJsonArray("interfaces").single().asString)
        assertEquals("Repo", detail.getAsJsonArray("outgoing").first { it.asJsonObject.get("type").asString == "INJECTS" }
            .asJsonObject.get("targetId").asString)
    }

    private fun query(model: ProjectAnalysisResult, request: String) =
        JsonParser.parseString(ProjectBrainQueryService.query(model, request)).asJsonObject

    private fun model(classes: List<ProjectClass>): ProjectAnalysisResult {
        val relationships = ProjectModelAssembler.relationships(classes)
        return ProjectAnalysisResult(ProjectScanResult(1, false, classes.size, classes.size, 0, 0,
            classes, relationships, ProjectModelAssembler.architecture(classes, relationships),
            FrameworkInventory(null, emptyList()), emptyList(),
            ProjectScanMetrics(1, classes.size, classes.size, relationships.size, 1, 0)), emptyList())
    }

    private fun cls(name: String, module: String, role: ComponentType,
                    language: ProjectLanguage = ProjectLanguage.JAVA): ProjectClass {
        val symbol = ClassificationEvidence(role, ClassificationEvidenceKind.DIRECT_ANNOTATION, "annotation:$role")
        val classification = ComponentClassification(role, ClassificationConfidence.HIGH,
            listOf(ComponentCandidate(role, 100, ClassificationConfidence.HIGH, listOf(symbol))), false)
        val extension = if (language == ProjectLanguage.JAVA) "java" else "kt"
        return ProjectClass(name, name, "sample.$name", module, "sample",
            "/$module/src/$name.$extension", 0, language, ProjectClassOrigin.MAIN,
            emptySet(), emptySet(), emptySet(), emptySet(), emptyList(), classification)
    }
}
