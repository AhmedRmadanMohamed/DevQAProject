# DevQAProject structure

The four source domains are `backend/`, `frontend/`, `core/`, and
`contracts/`. Service-specific Kotlin and React code lives in matching
service directories. Reusable scanning, analysis, UI components and the JCEF
bridge live in `core/`. Versioned request/response types live in
`contracts/`.

The Gradle root compiles the plugin-host, Service 01, and shared core Kotlin
source sets into one IntelliJ plugin. The npm workspace compiles the Service 01
React application. Kotlin JCEF embeds that compiled frontend, and the
only communication boundary is the typed native bridge.

Service 02 through Service 30 contain only `.gitkeep` placeholders.
Older Classic/legacy UI files were already removed on the source feature branch.
