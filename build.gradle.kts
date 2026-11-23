import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    // One IntelliJ plugin artifact. Source code stays inside the four agreed domains.
    sourceSets.named("main") {
        kotlin.srcDir("backend/plugin-host/src/main/kotlin")
        kotlin.srcDir("backend/services/service-01-project-brain/src/main/kotlin")
        kotlin.srcDir("core/backend/code-analysis/src/main/kotlin")
        kotlin.srcDir("core/backend/classifier/src/main/kotlin")
        kotlin.srcDir("core/backend/scanner/src/main/kotlin")
    }
    sourceSets.named("test") {
        kotlin.srcDir("backend/services/service-01-project-brain/src/test/kotlin")
        kotlin.srcDir("core/backend/code-analysis/src/test/kotlin")
        kotlin.srcDir("core/backend/classifier/src/test/kotlin")
        kotlin.srcDir("core/backend/scanner/src/test/kotlin")
    }
    // 2025.1 bundles Kotlin stdlib 2.1.10. Avoid generating references to
    // newer stdlib APIs that would fail when the plugin runs on that IDE.
    jvmToolchain(21)
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        // ToolWindowFactory is a Kotlin interface with default methods. Emit JVM
        // defaults instead of synthetic forwarding overrides in implementations;
        // those bridges are incorrectly reported as internal/experimental API use.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

// IntelliJ's plugin.xml and bundled icons now live with plugin-host.
sourceSets.named("main") {
    resources.srcDir("backend/plugin-host/src/main/resources")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Build against the oldest supported IntelliJ release, not the latest.
        intellijIdeaCommunity("2025.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // JCEF is part of the 2025.1 platform compile target. Its module alias is
        // available from 2025.3.1 and becomes a separate bundled plugin in 2026.2.
        // Keep it out of bundledPlugin resolution for the 2025.1 baseline; the
        // optional plugin.xml dependency attaches the runtime classloader when available.
    }
}

intellijPlatform {
    autoReload = true
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251" // IntelliJ IDEA 2025.1
            untilBuild = provider { null } // Newer versions must still pass verification.
        }
    }

    pluginVerification {
        ides {
            // Verify the oldest supported release on both pre-unification product lines.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")

            // Verify the latest unified IntelliJ IDEA release in the current matrix.
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks.runIde {
    autoReload = true
    jvmArgs(
        "-XX:+UnlockDiagnosticVMOptions",
        "-Dide.browser.jcef.sandbox.enable=false"
    )
}

// Service 01 is React-only; React is built for every IntelliJ plugin package.
val npmExecutable = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "npm.cmd" else "npm"
val reactPackageFiles = files("package.json", "frontend/app/package.json")
val reactSources = fileTree(".") {
    include(
        "frontend/app/src/**",
        "frontend/app/tests/**",
        "frontend/app/tests-installed/**",
        "frontend/app/tsconfig.json",
        "frontend/app/vite.config.ts",
        "frontend/services/service-01-project-brain/**",
        "contracts/**",
        "core/frontend/**"
    )
    exclude("**/node_modules/**", "**/dist/**")
}

val installDevqaReact = tasks.register<Exec>("installDevqaReact") {
    group = "devqa frontend"
    description = "Install the monorepo npm workspaces (no committed lockfile yet)."
    workingDir = file(".")
    commandLine(npmExecutable, "install", "--no-audit", "--no-fund")
    inputs.files(reactPackageFiles)
    outputs.file(layout.projectDirectory.file("node_modules/.package-lock.json"))
}
val testDevqaReact = tasks.register<Exec>("testDevqaReact") {
    group = "verification"
    dependsOn(installDevqaReact)
    workingDir = file(".")
    commandLine(npmExecutable, "run", "frontend:test")
    inputs.files(reactPackageFiles, reactSources)
}
val buildDevqaReact = tasks.register<Exec>("buildDevqaReact") {
    group = "build"
    dependsOn(installDevqaReact)
    workingDir = file(".")
    commandLine(npmExecutable, "run", "frontend:build")
    inputs.files(reactPackageFiles, reactSources)
    outputs.file(layout.projectDirectory.file("frontend/app/dist/app.js"))
}
tasks.named("check") {
    dependsOn(testDevqaReact)
}
tasks.processResources {
    dependsOn(buildDevqaReact)
    from(layout.projectDirectory.dir("frontend/app/dist")) {
        into("web/project-brain/react")
        include("app.js")
    }
    from(layout.projectDirectory.dir("frontend/services/service-01-project-brain/styles")) {
        into("web/project-brain/react")
        include("style.css", "overrides.css")
    }
}
