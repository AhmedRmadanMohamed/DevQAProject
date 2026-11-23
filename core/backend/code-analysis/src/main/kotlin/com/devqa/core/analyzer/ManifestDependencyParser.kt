package com.devqa.core.analyzer

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object ManifestDependencyParser {
    enum class Origin { DIRECT, TRANSITIVE, MANAGED, PLUGIN, PARENT, DECLARED }

    data class Declaration(val group: String, val name: String, val version: String? = null,
                           val origin: Origin = Origin.DECLARED) {
        val coordinate: String get() = listOf(group, name).filter(String::isNotBlank).joinToString(":")
    }

    fun parse(fileName: String, content: String): List<Declaration> = when (fileName.lowercase()) {
        "pom.xml" -> maven(content)
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" -> gradle(content)
        "package.json", "composer.json" -> json(content)
        "requirements.txt", "pipfile", "pyproject.toml" -> python(fileName.lowercase(), content)
        "gemfile" -> gem(content)
        "cargo.toml" -> cargo(content)
        "go.mod" -> go(content)
        "pubspec.yaml" -> pub(content)
        else -> emptyList()
    }

    private fun maven(content: String): List<Declaration> {
        // Build descriptors are bounded at the caller; disable DTD and external entities.
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(content)))
        } catch (_: Exception) {
            return emptyList()
        }
        val properties = mutableMapOf<String, String>()
        val project = doc.documentElement
        val projectVersion = child(project, "version")
        val parent = directChildren(project, "parent").firstOrNull()
        (projectVersion ?: parent?.let { child(it, "version") })?.let {
            properties["project.version"] = it
            properties["pom.version"] = it
        }
        directChildren(project, "properties").forEach { element ->
            element.childNodes.let { nodes ->
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    if (node is Element) properties[node.tagName] = node.textContent.trim()
                }
            }
        }
        val declarations = mutableListOf<Declaration>()
        for (tag in listOf("dependency", "plugin", "parent")) {
            val nodes = doc.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as? Element ?: continue
                val group = child(node, "groupId") ?: continue
                val name = child(node, "artifactId") ?: continue
                val version = child(node, "version")?.let { resolveProperty(it, properties) }
                val origin = when (tag) {
                    "plugin" -> Origin.PLUGIN
                    "parent" -> Origin.PARENT
                    else -> when {
                        hasAncestor(node, "dependencyManagement") -> Origin.MANAGED
                        hasAncestor(node, "plugin") -> Origin.PLUGIN
                        hasAncestor(node, "profile") -> Origin.DECLARED
                        else -> Origin.DIRECT
                    }
                }
                declarations += Declaration(group, name, exactVersion(version), origin)
            }
        }
        return declarations
    }

    private fun directChildren(parent: Element, tag: String): List<Element> = buildList {
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index)
            if (child is Element && child.tagName == tag) add(child)
        }
    }

    private fun hasAncestor(element: Element, tag: String): Boolean {
        var parent = element.parentNode
        while (parent != null) {
            if (parent is Element && parent.tagName == tag) return true
            parent = parent.parentNode
        }
        return false
    }

    private fun child(parent: Element, tag: String): String? = directChildren(parent, tag)
        .firstOrNull()?.textContent?.trim()?.takeIf(String::isNotBlank)

    private fun resolveProperty(value: String, properties: Map<String, String>): String? {
        var result = value.trim()
        repeat(5) {
            if (!result.contains($$"${")) return result
            val next = propertyReference.replace(result) { match ->
                properties[match.groupValues[1]] ?: match.value
            }
            if (next == result) return null
            result = next
        }
        return result.takeUnless { it.contains($$"${") }
    }

    private fun gradle(content: String): List<Declaration> = buildList {
        gradleCoordinate.findAll(content).forEach { match ->
            val lineStart = content.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val prefix = content.substring(lineStart, match.range.first)
            // Arbitrary literals and comments are not Gradle dependency declarations.
            // Do not promote versions from a log message or example into framework inventory.
            if (prefix.trimStart().startsWith("//") || !gradleDirectConfiguration.containsMatchIn(prefix))
                return@forEach
            val origin = if (gradlePluginConfiguration.containsMatchIn(prefix)) Origin.PLUGIN else Origin.DIRECT
            add(Declaration(match.groupValues[1], match.groupValues[2], exactVersion(match.groupValues[3]), origin))
        }
        gradlePlugin.findAll(content).forEach { match ->
            add(Declaration("plugin", match.groupValues[1], exactVersion(match.groupValues[2]), Origin.PLUGIN))
        }
    }

    private fun json(content: String): List<Declaration> = jsonDependencyBlock.findAll(content).flatMap { block ->
        jsonPackage.findAll(block.groupValues[1]).map { match ->
            val name = match.groupValues[1]
            Declaration(name.substringBeforeLast('/', ""), name, exactVersion(match.groupValues[2]), Origin.DIRECT)
        }
    }.toList()

    private fun python(fileName: String, content: String): List<Declaration> = pythonDependency.findAll(content).map { match ->
        Declaration("python", match.groupValues[1],
            if (match.groupValues[2] == "==") exactVersion(match.groupValues[3]) else null,
            if (fileName == "requirements.txt") Origin.DIRECT else Origin.DECLARED)
    }.toList()

    private fun gem(content: String): List<Declaration> = gemDependency.findAll(content).map { match ->
        Declaration("ruby", match.groupValues[1], exactVersion(match.groupValues[2]), Origin.DIRECT)
    }.toList()

    private fun cargo(content: String): List<Declaration> {
        val section = tomlDependencySection.find(content)?.groupValues?.get(1) ?: return emptyList()
        return tomlDependency.findAll(section).map { match ->
            // Cargo's ordinary version declarations are ranges; only '=' pins the version.
            Declaration("rust", match.groupValues[1], exactVersion(match.groupValues[2].removePrefix("="))
                ?.takeIf { match.groupValues[2].startsWith("=") }, Origin.DIRECT)
        }.toList()
    }

    private fun go(content: String): List<Declaration> = goDependency.findAll(content).map { match ->
        val module = match.groupValues[1]
        val end = content.indexOf('\n', match.range.last + 1).let { if (it < 0) content.length else it }
        val line = content.substring(match.range.first, end)
        Declaration(module.substringBeforeLast('/', ""), module.substringAfterLast('/'), exactVersion(match.groupValues[2]),
            if ("// indirect" in line) Origin.TRANSITIVE else Origin.DIRECT)
    }.toList()

    private fun pub(content: String): List<Declaration> {
        val section = yamlDependencySection.find(content)?.groupValues?.get(1) ?: return emptyList()
        return yamlDependency.findAll(section).map { match -> Declaration("dart", match.groupValues[1], origin = Origin.DIRECT) }.toList()
    }

    private fun exactVersion(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || !literalVersion.matches(value)) return null
        return value
    }

    private val propertyReference = Regex("\\$\\{([^}]+)}")
    private val literalVersion = Regex("v?[0-9]+(?:\\.[0-9A-Za-z_-]+)+(?:[.+-][0-9A-Za-z_.-]+)*")
    private val gradleCoordinate = Regex("[\"']([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.@/-]+)(?::([^\"']+))?[\"']")
    private val gradleDirectConfiguration = Regex("""\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|annotationProcessor|kapt|ksp|classpath)\s*[(\s]""")
    private val gradlePluginConfiguration = Regex("""\bclasspath\s*[(\s]""")
    private val gradlePlugin = Regex("""id\s*\(?\s*["']([\w.-]+)["']\s*\)?\s*version\s*["']([^"']+)["']""")
    private val jsonDependencyBlock = Regex(""""(?:dependencies|devDependencies|require|require-dev)"\s*:\s*\{([\s\S]*?)}""", RegexOption.IGNORE_CASE)
    private val jsonPackage = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
    private val pythonDependency = Regex("""(?m)^\s*["']?([a-zA-Z0-9_.-]{3,})(?:\[[^]]+])?\s*(==|>=|~=|<=|>|=)?\s*([0-9][\w.+-]*)?""")
    private val gemDependency = Regex("""(?m)^\s*gem\s+["']([^"']+)["'](?:\s*,\s*["']=?\s*([^"']+)["'])?""")
    private val tomlDependencySection = Regex("""(?s)\[dependencies]\s*(.*?)(?:\n\[|$)""")
    private val tomlDependency = Regex("""(?m)^\s*([a-zA-Z0-9_-]+)\s*=\s*["']([^"']+)["']""")
    private val goDependency = Regex("""(?m)^\s*([\w.-]+(?:/[\w.-]+)+)\s+(v[0-9][\w.+-]*)""")
    private val yamlDependencySection = Regex("""(?s)(?:^|\n)dependencies:\s*\n(.*?)(?:\n\S|$)""")
    private val yamlDependency = Regex("""(?m)^\s{2,}([a-zA-Z0-9_-]+):""")
}
