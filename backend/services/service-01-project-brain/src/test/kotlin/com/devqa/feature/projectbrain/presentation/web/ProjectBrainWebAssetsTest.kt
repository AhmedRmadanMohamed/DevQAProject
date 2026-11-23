package com.devqa.feature.projectbrain.presentation.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBrainWebAssetsTest {
    @Test
    fun react_only_offline_document_preserves_native_bridge() {
        val html = ProjectBrainWebAssets.html("window.__DEVQA_TEST_BRIDGE = true;")
        assertTrue(html.contains("<div id=\"app-root\" aria-live=\"polite\"></div>"))
        assertTrue(html.contains("window.__DEVQA_TEST_BRIDGE = true;"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("object-src 'none'"))
        assertTrue(html.contains("receiveDevQAProjectBrainSnapshot"))
        assertTrue(html.contains("frontendReady"))
        assertFalse(html.contains("id=\"feature-root\""))
        assertFalse(html.contains("workspace.js"))
        assertFalse(html.contains("backend-adapter.js"))
        assertFalse(html.contains("id=\"devqa-react-switch\""))
        assertFalse(html.contains("<script src="))
        // The IIFE contains arbitrary ReactDOM string constants, potentially
        // including HTML fragments. Check the actual document head instead.
        assertFalse(html.substringBefore("</head>").contains("<link rel=\"stylesheet\""))
    }
}
