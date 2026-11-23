package com.devqa.feature.projectbrain.application

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProjectBrainQueryServiceTest {

    @Test
    fun `valid query without a published model returns a versioned no model envelope`() {
        val response = JsonParser.parseString(
            ProjectBrainQueryService.query(
                null,
                """{"kind":"diagram","view":"layers","requestId":"diagram-smoke"}"""
            )
        ).asJsonObject

        assertEquals("diagram", response.get("kind").asString)
        assertEquals("diagram-smoke", response.get("requestId").asString)
        assertEquals("NO_MODEL", response.get("status").asString)
        assertEquals(0, response.get("total").asInt)
        assertEquals(0, response.getAsJsonArray("items").size())
    }

    @Test
    fun `unsupported query kinds are rejected before model lookup`() {
        assertInvalid("""{"kind":"unknown-query"}""")
    }

    @Test
    fun `non object request payloads are rejected`() {
        assertInvalid("""[]""")
    }

    @Test
    fun `filters must be an object containing bounded string arrays`() {
        assertInvalid("""{"kind":"components","filters":[]}""")
        assertInvalid("""{"kind":"components","filters":{"module":["orders",7]}}""")
        assertInvalid("""{"kind":"components","filters":{"module":["${"x".repeat(257)}"]}}""")
    }

    @Test
    fun `unsupported graph views are rejected before model lookup`() {
        assertInvalid("""{"kind":"diagram","view":"unbounded"}""")
    }

    @Test
    fun `request identifiers must be strings`() {
        assertInvalid("""{"kind":"components","requestId":42}""")
    }

    @Test
    fun `oversized and blank requests are rejected`() {
        assertInvalid(" ".repeat(32_769))
        assertInvalid("""{"kind":"components"}""" + " ".repeat(32_768))
    }

    private fun assertInvalid(request: String) {
        try {
            ProjectBrainQueryService.query(null, request)
            fail("Expected invalid Project Brain request to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid requests must fail closed before model access.
        }
    }
}
