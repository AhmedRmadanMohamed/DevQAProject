package com.devqa.feature.projectbrain.presentation.web

/**
 * Offline React-only JCEF document. Kotlin bridge is injected first, then the
 * compiled IIFE, which owns the entire UI and the frontend-ready handshake.
 */
object ProjectBrainWebAssets {
    private val css by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resource("style.css") + "\n" + resource("overrides.css")
    }
    private val compiledReact by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resource("app.js")
    }

    fun html(bridgeScript: String): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="dark light">
              <meta http-equiv="Content-Security-Policy"
                content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'none'; worker-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'">
              <title>DevQA · Project Brain</title>
              <style>${escapeStyle(css)}</style>
            </head>
            <body>
              <div id="app-root" aria-live="polite"></div>
              <script>${escapeInline(bridgeScript)}</script>
              <script>${escapeInline(compiledReact)}</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun resource(name: String): String =
        requireNotNull(ProjectBrainWebAssets::class.java
            .getResourceAsStream("/web/project-brain/react/$name")) {
            "Missing compiled React frontend asset: /web/project-brain/react/$name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun escapeInline(source: String): String =
        source.replace("</script", "<\\/script", ignoreCase = true)

    private fun escapeStyle(source: String): String =
        source.replace("</style", "<\\/style", ignoreCase = true)
}
