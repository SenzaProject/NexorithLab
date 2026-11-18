package com.nexo.nexorith.lab.maven

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class MavenException(message: String) : Exception(message)

data class Coordinate(val group: String, val artifact: String, val version: String) {
    val ga: String get() = "$group:$artifact"

    fun path(ext: String): String = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$ext"

    override fun toString(): String = "$group:$artifact:$version"
}

data class PomDependency(
    val group: String,
    val artifact: String,
    val version: String?,
    val scope: String?,
    val optional: Boolean,
    val type: String,
    val classifier: String?,
    val exclusions: Set<String>
)

class RawPom(
    val group: String?,
    val version: String?,
    val packaging: String,
    val parent: Coordinate?,
    val properties: Map<String, String>,
    val managed: List<PomDependency>,
    val dependencies: List<PomDependency>
)

object PomParser {

    fun parse(bytes: ByteArray): RawPom {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // tidak semua implementasi XML mendukung fitur ini
        }
        val root = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (e: Exception) {
            throw MavenException("POM tidak valid: ${e.message}")
        }

        val parentEl = child(root, "parent")
        val parent = parentEl?.let {
            val g = text(it, "groupId")
            val a = text(it, "artifactId")
            val v = text(it, "version")
            if (g == null || a == null || v == null) throw MavenException("Elemen <parent> tidak lengkap")
            Coordinate(g, a, v)
        }

        val properties = LinkedHashMap<String, String>()
        child(root, "properties")?.let { props ->
            for (p in allChildren(props)) properties[p.nodeName] = p.textContent.trim()
        }

        val managed = parseDependencies(child(child(root, "dependencyManagement"), "dependencies"))
        val deps = parseDependencies(child(root, "dependencies"))

        return RawPom(
            group = text(root, "groupId") ?: parent?.group,
            version = text(root, "version") ?: parent?.version,
            packaging = text(root, "packaging") ?: "jar",
            parent = parent,
            properties = properties,
            managed = managed,
            dependencies = deps
        )
    }

    private fun parseDependencies(container: Element?): List<PomDependency> {
        if (container == null) return emptyList()
        return children(container, "dependency").mapNotNull { d ->
            val g = text(d, "groupId")
            val a = text(d, "artifactId")
            if (g == null || a == null) return@mapNotNull null
            val exclusions = LinkedHashSet<String>()
            child(d, "exclusions")?.let { ex ->
                for (e in children(ex, "exclusion")) {
                    exclusions.add("${text(e, "groupId") ?: "*"}:${text(e, "artifactId") ?: "*"}")
                }
            }
            PomDependency(
                group = g,
                artifact = a,
                version = text(d, "version"),
                scope = text(d, "scope"),
                optional = text(d, "optional") == "true",
                type = text(d, "type") ?: "jar",
                classifier = text(d, "classifier"),
                exclusions = exclusions
            )
        }
    }

    private fun allChildren(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) out.add(n)
        }
        return out
    }

    private fun children(el: Element, name: String): List<Element> = allChildren(el).filter { it.nodeName == name }

    private fun child(el: Element?, name: String): Element? =
        if (el == null) null else children(el, name).firstOrNull()

    private fun text(el: Element?, name: String): String? =
        child(el, name)?.textContent?.trim()?.ifEmpty { null }
}
