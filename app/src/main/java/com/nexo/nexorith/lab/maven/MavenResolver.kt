package com.nexo.nexorith.lab.maven

import com.nexo.nexorith.lab.build.BuildCancelled
import com.nexo.nexorith.lab.build.Dependency
import java.io.File
import java.security.MessageDigest

class Resolved(
    val coordinate: Coordinate,
    val packaging: String,
    val file: File,
    val compileOnly: Boolean
) {
    var extractDir: File? = null
}

class EffectiveDependency(val coordinate: Coordinate, val exclusions: Set<String>)

class EffectivePom(val coordinate: Coordinate, val packaging: String, val dependencies: List<EffectiveDependency>)

/**
 * Resolver dependensi Maven: membaca POM (parent, properti, dependencyManagement, BOM import),
 * menyelesaikan dependensi transitif, memilih versi tertinggi saat konflik, lalu mengunduh
 * .jar/.aar dengan verifikasi SHA-1. Belum mendukung Gradle module metadata (.module).
 */
class MavenResolver(
    private val repos: List<String>,
    private val fetcher: Fetcher,
    private val cacheDir: File,
    private val log: (String) -> Unit,
    private val cancelled: () -> Boolean
) {
    companion object {
        val DEFAULT_REPOS = listOf(
            "https://dl.google.com/dl/android/maven2/",
            "https://repo1.maven.org/maven2/"
        )
        private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")
        private val SKIPPED_SCOPES = setOf("test", "provided", "system")
    }

    private class Edge(val coord: Coordinate, val compileOnly: Boolean, val exclusions: Set<String>)

    private class Graph(
        val requested: LinkedHashMap<String, MutableList<String>>,
        val compileOnly: HashMap<String, Boolean>
    )

    private class ManagedEntry(val version: String, val scope: String?, val exclusions: Set<String>)

    private class PomContext(
        val chain: List<RawPom>,
        val props: Map<String, String>,
        val managed: Map<String, ManagedEntry>
    )

    private val rawCache = HashMap<Coordinate, RawPom>()
    private val effectiveCache = HashMap<Coordinate, EffectivePom>()

    fun resolve(roots: List<Dependency>): List<Resolved> {
        val rootEdges = roots.map { d ->
            val version = d.version ?: throw MavenException("Versi dependensi ${d.group}:${d.artifact} belum diisi")
            Edge(Coordinate(d.group, d.artifact, pickVersion(version)), d.configuration == "compileOnly", emptySet())
        }

        var selected: Map<String, String> = emptyMap()
        var graph = traverse(rootEdges, selected)
        var rounds = 0
        while (true) {
            val next = graph.requested.mapValues { (_, versions) -> versions.maxWithOrNull(MavenVersion)!! }
            if (next == selected) break
            if (++rounds > 10) throw MavenException("Konflik versi dependensi tidak stabil")
            selected = next
            graph = traverse(rootEdges, selected)
        }

        val result = ArrayList<Resolved>()
        for ((ga, versions) in graph.requested) {
            if (cancelled()) throw BuildCancelled()
            val version = selected[ga] ?: versions.maxWithOrNull(MavenVersion)!!
            val (group, artifact) = ga.split(":")
            val coord = Coordinate(group, artifact, version)
            val ext = when (effective(coord).packaging) {
                "aar" -> "aar"
                "pom" -> null
                else -> "jar"
            }
            if (ext == null) {
                log("  ~ $coord (pom, tanpa artefak)")
                continue
            }
            val file = download(coord, ext)
            result += Resolved(coord, ext, file, graph.compileOnly[ga] ?: false)
            log("  + $coord ($ext)")
        }
        return result
    }

    private fun traverse(roots: List<Edge>, selected: Map<String, String>): Graph {
        val requested = LinkedHashMap<String, MutableList<String>>()
        val compileOnly = HashMap<String, Boolean>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Edge>()
        queue.addAll(roots)
        while (queue.isNotEmpty()) {
            if (cancelled()) throw BuildCancelled()
            val e = queue.removeFirst()
            val ga = e.coord.ga
            requested.getOrPut(ga) { mutableListOf() }.add(e.coord.version)
            compileOnly[ga] = (compileOnly[ga] ?: true) && e.compileOnly
            val version = selected[ga] ?: e.coord.version
            if (!visited.add("$ga:$version")) continue
            val eff = effective(Coordinate(e.coord.group, e.coord.artifact, version))
            for (d in eff.dependencies) {
                if (isExcluded(e.exclusions, d.coordinate)) continue
                queue.add(Edge(d.coordinate, e.compileOnly, e.exclusions + d.exclusions))
            }
        }
        return Graph(requested, compileOnly)
    }

    private fun isExcluded(exclusions: Set<String>, c: Coordinate): Boolean = exclusions.any { rule ->
        val parts = rule.split(":")
        parts.size == 2 && (parts[0] == "*" || parts[0] == c.group) && (parts[1] == "*" || parts[1] == c.artifact)
    }

    private fun effective(c: Coordinate): EffectivePom {
        effectiveCache[c]?.let { return it }
        val ctx = context(c, 0)

        val deps = LinkedHashMap<String, PomDependency>()
        for (p in ctx.chain.asReversed()) {
            for (d in p.dependencies) deps[d.group + ":" + d.artifact + ":" + d.type] = d
        }

        val out = ArrayList<EffectiveDependency>()
        for (d in deps.values) {
            val g = interp(d.group, ctx.props)
            val a = interp(d.artifact, ctx.props)
            if (unresolved(g) || unresolved(a)) {
                log("  ! dependensi pada $c dilewati (properti tidak terselesaikan)")
                continue
            }
            val managed = ctx.managed["$g:$a"]
            val scope = d.scope ?: managed?.scope ?: "compile"
            if (d.optional || scope in SKIPPED_SCOPES) continue
            if (d.type != "jar" && d.type != "aar") continue
            if (!d.classifier.isNullOrEmpty()) continue
            val rawVersion = d.version?.let { interp(it, ctx.props) } ?: managed?.version
            if (rawVersion == null || unresolved(rawVersion)) {
                log("  ! versi $g:$a pada $c tidak ditemukan, dilewati")
                continue
            }
            out += EffectiveDependency(
                Coordinate(g, a, pickVersion(rawVersion)),
                d.exclusions + (managed?.exclusions ?: emptySet())
            )
        }
        val result = EffectivePom(c, ctx.chain.first().packaging, out)
        effectiveCache[c] = result
        return result
    }

    private fun context(c: Coordinate, depth: Int): PomContext {
        if (depth > 5) throw MavenException("Impor BOM terlalu dalam pada $c")
        val chain = ArrayList<RawPom>()
        var cur: RawPom? = loadPom(c)
        while (cur != null) {
            chain.add(cur)
            if (chain.size > 20) throw MavenException("Rantai parent POM terlalu panjang pada $c")
            cur = cur.parent?.let { loadPom(it) }
        }
        val self = chain.first()

        val props = HashMap<String, String>()
        for (p in chain.asReversed()) props.putAll(p.properties)
        props["project.groupId"] = c.group
        props["project.artifactId"] = c.artifact
        props["project.version"] = c.version
        props["version"] = c.version
        props["pom.version"] = c.version
        self.parent?.let {
            props["project.parent.version"] = it.version
            props["project.parent.groupId"] = it.group
        }

        val managed = LinkedHashMap<String, ManagedEntry>()
        val imports = ArrayList<PomDependency>()
        for (p in chain.asReversed()) {
            for (m in p.managed) {
                if (m.scope == "import" && m.type == "pom") {
                    imports.add(m)
                    continue
                }
                val v = m.version?.let { interp(it, props) } ?: continue
                managed[interp(m.group, props) + ":" + interp(m.artifact, props)] =
                    ManagedEntry(pickVersion(v), m.scope, m.exclusions)
            }
        }
        for (imp in imports) {
            val g = interp(imp.group, props)
            val a = interp(imp.artifact, props)
            val v = imp.version?.let { interp(it, props) } ?: continue
            if (unresolved(g) || unresolved(a) || unresolved(v)) continue
            val bom = context(Coordinate(g, a, pickVersion(v)), depth + 1)
            for ((key, entry) in bom.managed) managed.putIfAbsent(key, entry)
        }
        return PomContext(chain, props, managed)
    }

    private fun loadPom(c: Coordinate): RawPom = rawCache.getOrPut(c) {
        val bytes = fetchCached(c.path("pom")) ?: throw MavenException("POM tidak ditemukan di repositori: $c")
        PomParser.parse(bytes)
    }

    private fun interp(s: String, props: Map<String, String>): String {
        var cur = s
        repeat(10) {
            val next = PLACEHOLDER.replace(cur) { m -> props[m.groupValues[1]] ?: m.value }
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    private fun unresolved(s: String): Boolean = s.contains("\${")

    private fun pickVersion(v: String): String {
        val t = v.trim()
        if (t.startsWith("[") || t.startsWith("(")) {
            val inner = t.trim('[', ']', '(', ')')
            return inner.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: t
        }
        return t
    }

    private fun fetchCached(path: String): ByteArray? {
        val cached = File(cacheDir, path)
        if (cached.isFile) return cached.readBytes()
        for (repo in repos) {
            if (cancelled()) throw BuildCancelled()
            val bytes = fetcher.fetch(repo + path) ?: continue
            writeAtomic(cached, bytes)
            return bytes
        }
        return null
    }

    private fun download(c: Coordinate, ext: String): File {
        val path = c.path(ext)
        val target = File(cacheDir, path)
        if (target.isFile) return target
        for (repo in repos) {
            if (cancelled()) throw BuildCancelled()
            val url = repo + path
            val bytes = fetcher.fetch(url) ?: continue
            log("  mengunduh $c.$ext (${bytes.size / 1024} KB)")
            verifySha1(bytes, url)
            writeAtomic(target, bytes)
            return target
        }
        throw MavenException("Artefak tidak ditemukan di repositori: $c.$ext")
    }

    private fun verifySha1(bytes: ByteArray, url: String) {
        val remote = fetcher.fetch("$url.sha1")
        if (remote == null) {
            log("  ! checksum SHA-1 tidak tersedia untuk $url")
            return
        }
        val expected = String(remote).trim().split(Regex("\\s+")).first().lowercase()
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        if (expected != actual) throw MavenException("Checksum SHA-1 tidak cocok untuk $url")
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
