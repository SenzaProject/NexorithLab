package com.nexo.nexorith.lab

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ResolveError(message: String) : Exception(message)

/** Satu library hasil resolusi: jar yang dipakai compiler/dex, plus isi AAR bila ada. */
class ResolvedLib(
    val group: String,
    val artifact: String,
    val version: String,
    val jars: List<File>,
    val aarDir: File?,
    val compileOnly: Boolean
) {
    val coord: String get() = "$group:$artifact:$version"

    val resDir: File? by lazy {
        aarDir?.let { File(it, "res") }?.takeIf { d -> d.isDirectory && d.walkTopDown().any { it.isFile } }
    }
    val manifest: File? get() = aarDir?.let { File(it, "AndroidManifest.xml") }?.takeIf { it.isFile }
    val assetsDir: File? get() = aarDir?.let { File(it, "assets") }?.takeIf { it.isDirectory }
    val jniDir: File? get() = aarDir?.let { File(it, "jni") }?.takeIf { it.isDirectory }
    val packageName: String? by lazy {
        manifest?.readText()?.let { Regex("""package\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
    }
}

/**
 * Penyelesai dependency Maven: membaca .pom (termasuk parent dan BOM), memilih versi tertinggi saat bentrok,
 * mengunduh .jar / .aar dari Google Maven dan Maven Central (serta repo tambahan), lalu menyimpannya di cache.
 * Setelah semua file ada di cache, resolusi bisa berjalan tanpa internet.
 */
class DependencyResolver(
    private val cacheRoot: File,
    private val log: (String) -> Unit,
    private val cancelled: () -> Boolean
) {
    companion object {
        const val GOOGLE = "https://dl.google.com/dl/android/maven2"
        const val CENTRAL = "https://repo1.maven.org/maven2"
        private val GOOGLE_FIRST = listOf("androidx.", "com.android.", "com.google.android.", "com.google.firebase", "com.google.gms", "com.google.mlkit")

        fun compareVersions(a: String, b: String): Int {
            val re = Regex("""\d+|[A-Za-z]+""")
            val ta = re.findAll(a).map { it.value }.toList()
            val tb = re.findAll(b).map { it.value }.toList()
            for (i in 0 until maxOf(ta.size, tb.size)) {
                val x = ta.getOrNull(i)
                val y = tb.getOrNull(i)
                if (x == y) continue
                val c = when {
                    x == null -> tailVsMissing(y!!) * -1
                    y == null -> tailVsMissing(x)
                    x[0].isDigit() && y[0].isDigit() -> (x.toBigInteger()).compareTo(y.toBigInteger())
                    x[0].isDigit() -> 1
                    y[0].isDigit() -> -1
                    else -> rank(x).compareTo(rank(y)).takeIf { it != 0 } ?: x.lowercase().compareTo(y.lowercase())
                }
                if (c != 0) return c
            }
            return 0
        }

        // token tersisa di versi yang lebih panjang: angka > 0 berarti lebih tinggi, kualifier (alpha/rc) lebih rendah
        private fun tailVsMissing(t: String): Int = when {
            t[0].isDigit() -> if (t.toBigInteger().signum() == 0) 0 else 1
            t.lowercase() in setOf("final", "release", "ga") -> 0
            else -> -1
        }

        private fun rank(s: String): Int = when (s.lowercase()) {
            "alpha", "a" -> 1
            "beta", "b" -> 2
            "milestone", "m" -> 3
            "rc", "cr" -> 4
            "snapshot" -> 5
            else -> 0
        }
    }

    private val mavenDir = File(cacheRoot, "maven").apply { mkdirs() }
    private var extraRepos: List<String> = emptyList()
    private val poms = HashMap<String, Pom>()

    // ---------- struktur POM ----------

    private class XNode(val name: String) {
        val children = ArrayList<XNode>()
        var text = ""
        fun child(n: String) = children.firstOrNull { it.name == n }
        fun kids(n: String) = children.filter { it.name == n }
        fun txt(n: String) = child(n)?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private class PomDep(
        val g: String, val a: String, val v: String?, val scope: String,
        val optional: Boolean, val type: String, val excl: List<String>
    )

    private class Pom(
        val g: String, val a: String, val v: String, val packaging: String,
        val props: Map<String, String>, val managed: Map<String, String>, val deps: List<PomDep>
    )

    private fun parseXml(file: File): XNode {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        file.inputStream().reader().use { reader ->
            p.setInput(reader)
            val stack = ArrayList<XNode>()
            var root: XNode? = null
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        val n = XNode(p.name)
                        stack.lastOrNull()?.children?.add(n)
                        if (root == null) root = n
                        stack.add(n)
                    }
                    XmlPullParser.TEXT -> stack.lastOrNull()?.let { it.text += p.text }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    XmlPullParser.END_DOCUMENT -> return root ?: throw IOException("XML kosong")
                }
            }
        }
        throw IOException("XML tidak lengkap")
    }

    // ---------- cache & unduhan ----------

    private fun artDir(g: String, a: String, v: String) = File(File(File(mavenDir, g.replace('.', '/')), a), v)

    fun isCached(d: Dep): Boolean =
        d.version != null && File(artDir(d.group, d.artifact, d.version), "${d.artifact}-${d.version}.pom").isFile

    fun clearCache() { cacheRoot.deleteRecursively() }

    fun cacheSize(): Long = cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun reposFor(g: String): List<String> {
        val base = if (GOOGLE_FIRST.any { g.startsWith(it) }) listOf(GOOGLE, CENTRAL) else listOf(CENTRAL, GOOGLE)
        return base + extraRepos
    }

    /** Ambil file artefak ke cache; null bila tidak ada di repo mana pun. */
    private fun fetch(g: String, a: String, v: String, ext: String): File? {
        val d = artDir(g, a, v)
        val f = File(d, "$a-$v.$ext")
        if (f.isFile && f.length() > 0) return f
        val miss = File(d, "$a-$v.$ext.missing")
        if (miss.exists()) return null
        for (repo in reposFor(g)) {
            if (cancelled()) throw ResolveError("Dibatalkan.")
            val url = "$repo/${g.replace('.', '/')}/$a/$v/$a-$v.$ext"
            val ok = try {
                download(url, f)
            } catch (e: IOException) {
                throw ResolveError("Tidak bisa mengunduh $g:$a:$v ($ext): ${e.message}. Periksa koneksi internet lalu coba lagi.")
            }
            if (ok) {
                if (ext != "pom") log("  + $a-$v.$ext (${f.length() / 1024} KB)")
                return f
            }
        }
        d.mkdirs()
        miss.writeText("404")
        return null
    }

    /** false jika 404. */
    private fun download(url: String, dest: File): Boolean {
        var attempt = 0
        while (true) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "NexorithLab/1.1")
                val code = c.responseCode
                if (code == 404 || code == 410) { c.disconnect(); return false }
                if (code != 200) { c.disconnect(); throw IOException("HTTP $code dari ${URL(url).host}") }
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                c.disconnect()
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
                return true
            } catch (e: IOException) {
                if (++attempt >= 3 || cancelled()) throw e
                Thread.sleep(800)
            }
        }
    }

    private fun extractAar(aar: File, dir: File) {
        val done = File(dir, ".done")
        if (done.exists()) return
        dir.deleteRecursively()
        dir.mkdirs()
        val canon = dir.canonicalPath + File.separator
        ZipInputStream(aar.inputStream().buffered()).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: break
                if (e.isDirectory) continue
                val f = File(dir, e.name)
                if (!f.canonicalPath.startsWith(canon)) throw ResolveError("AAR berisi path berbahaya: ${aar.name}")
                f.parentFile?.mkdirs()
                f.outputStream().use { zi.copyTo(it) }
            }
        }
        done.writeText("ok")
    }

    // ---------- POM ----------

    private fun loadPom(g: String, a: String, v: String, depth: Int = 0): Pom {
        val key = "$g:$a:$v"
        poms[key]?.let { return it }
        if (depth > 10) throw ResolveError("Rantai POM terlalu dalam: $key")
        val file = fetch(g, a, v, "pom")
            ?: throw ResolveError("$key tidak ditemukan di Google Maven maupun Maven Central. Cek penulisan dan versinya.")
        val root = try { parseXml(file) } catch (e: Exception) {
            file.delete()
            throw ResolveError("POM $key rusak (${e.message}). Coba lagi.")
        }
        val parent = root.child("parent")?.let {
            val pg = it.txt("groupId"); val pa = it.txt("artifactId"); val pv = it.txt("version")
            if (pg != null && pa != null && pv != null) loadPom(pg, pa, pv, depth + 1) else null
        }
        val pgroup = root.txt("groupId") ?: parent?.g ?: g
        val pver = root.txt("version") ?: parent?.v ?: v

        val props = HashMap<String, String>()
        parent?.props?.let { props.putAll(it) }
        root.child("properties")?.children?.forEach { props[it.name] = it.text.trim() }
        props["project.version"] = pver; props["version"] = pver; props["pom.version"] = pver
        props["project.groupId"] = pgroup; props["pom.groupId"] = pgroup
        props["project.artifactId"] = a
        if (parent != null) { props["project.parent.version"] = parent.v; props["parent.version"] = parent.v }

        val propRe = Regex("""\$\{([^}]+)\}""")
        fun interp(s: String?): String? {
            var r = s?.trim() ?: return null
            var n = 0
            while (r.contains("$" + "{") && n++ < 10) {
                val next = propRe.replace(r) { m -> props[m.groupValues[1]] ?: m.value }
                if (next == r) break
                r = next
            }
            return r
        }

        val managed = HashMap<String, String>()
        parent?.managed?.let { managed.putAll(it) }
        root.child("dependencyManagement")?.child("dependencies")?.kids("dependency")?.forEach { d ->
            val dg = interp(d.txt("groupId")); val da = interp(d.txt("artifactId")); val dv = interp(d.txt("version"))
            if (dg == null || da == null || dv == null) return@forEach
            if (interp(d.txt("scope")) == "import") {
                try {
                    loadPom(dg, da, dv, depth + 1).managed.forEach { (k, x) -> managed.putIfAbsent(k, x) }
                } catch (e: ResolveError) {
                    log("  peringatan: BOM $dg:$da:$dv tidak bisa dibaca")
                }
            } else managed["$dg:$da"] = dv
        }

        val deps = ArrayList<PomDep>()
        parent?.deps?.let { deps.addAll(it) }
        root.child("dependencies")?.kids("dependency")?.forEach { d ->
            val dg = interp(d.txt("groupId")); val da = interp(d.txt("artifactId"))
            if (dg == null || da == null) return@forEach
            val dv = interp(d.txt("version")) ?: managed["$dg:$da"]
            val excl = d.child("exclusions")?.kids("exclusion")?.map {
                (interp(it.txt("groupId")) ?: "*") + ":" + (interp(it.txt("artifactId")) ?: "*")
            } ?: emptyList()
            deps.add(PomDep(dg, da, dv, interp(d.txt("scope")) ?: "compile", interp(d.txt("optional")) == "true",
                interp(d.txt("type")) ?: "jar", excl))
        }
        val pom = Pom(pgroup, a, pver, interp(root.txt("packaging")) ?: "jar", props, managed, deps)
        poms[key] = pom
        return pom
    }

    private fun pickVersion(spec: String): String {
        val s = spec.trim()
        if (!(s.startsWith("[") || s.startsWith("("))) return s
        val parts = s.trim('[', ']', '(', ')').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.firstOrNull() ?: s
    }

    private fun matchExcl(pat: String, g: String, a: String): Boolean {
        val pg = pat.substringBefore(':')
        val pa = pat.substringAfter(':')
        return (pg == "*" || pg == g) && (pa == "*" || pa == a)
    }

    // ---------- resolusi ----------

    private class Req(val g: String, val a: String, val v: String, val compileOnly: Boolean, val excl: List<String>)
    private class Sel(var version: String, var compileOnly: Boolean)

    fun resolve(roots: List<Dep>, boms: List<Dep>, repos: List<String>): List<ResolvedLib> {
        extraRepos = repos.distinct()
        val bomManaged = HashMap<String, String>()
        for (b in boms) {
            val v = b.version ?: throw ResolveError("BOM ${b.ga} perlu versi.")
            loadPom(b.group, b.artifact, pickVersion(v)).managed.forEach { (k, x) -> bomManaged.putIfAbsent(k, x) }
        }
        val queue = ArrayDeque<Req>()
        for (d in roots) {
            val v = d.version ?: bomManaged[d.ga]
                ?: throw ResolveError("Versi untuk ${d.ga} belum ditulis. Pakai format 'group:artifact:versi'.")
            queue.add(Req(d.group, d.artifact, pickVersion(v), d.config == "compileOnly", emptyList()))
        }

        val selected = LinkedHashMap<String, Sel>()
        while (queue.isNotEmpty()) {
            if (cancelled()) throw ResolveError("Dibatalkan.")
            val r = queue.removeFirst()
            val key = "${r.g}:${r.a}"
            val cur = selected[key]
            if (cur == null) {
                selected[key] = Sel(r.v, r.compileOnly)
            } else {
                val higher = compareVersions(r.v, cur.version) > 0
                val widen = cur.compileOnly && !r.compileOnly
                if (!higher && !widen) continue
                if (higher) cur.version = r.v
                if (widen) cur.compileOnly = false
            }
            val sel = selected[key]!!
            val pom = loadPom(r.g, r.a, sel.version)
            for (d in pom.deps) {
                if (d.optional || d.type == "pom" || d.type == "test-jar") continue
                if (d.scope != "compile" && d.scope != "runtime") continue
                if (r.excl.any { matchExcl(it, d.g, d.a) }) continue
                val dv = d.v ?: bomManaged["${d.g}:${d.a}"]
                if (dv == null) { log("  peringatan: versi ${d.g}:${d.a} tidak diketahui, dilewati"); continue }
                queue.add(Req(d.g, d.a, pickVersion(dv), sel.compileOnly, r.excl + d.excl))
            }
        }

        // kasus khusus: listenablefuture "kosong" (9999.0-empty...) hanya benar bila guava ikut dipakai
        if (selected.containsKey("com.google.guava:guava")) selected.remove("com.google.guava:listenablefuture")
        else selected["com.google.guava:listenablefuture"]?.let { if (it.version.startsWith("9999")) it.version = "1.0" }

        val out = ArrayList<ResolvedLib>()
        for ((key, sel) in selected) {
            if (cancelled()) throw ResolveError("Dibatalkan.")
            out.add(materialize(key.substringBefore(':'), key.substringAfter(':'), sel.version, sel.compileOnly))
        }
        return out
    }

    private fun materialize(g: String, a: String, v: String, compileOnly: Boolean): ResolvedLib {
        val pom = loadPom(g, a, v)
        if (pom.packaging == "pom") return ResolvedLib(g, a, v, emptyList(), null, compileOnly)
        val prefer = if (pom.packaging == "aar") listOf("aar", "jar") else listOf("jar", "aar")
        var found: Pair<File, String>? = null
        for (e in prefer) {
            val f = fetch(g, a, v, e)
            if (f != null) { found = f to e; break }
        }
        if (found == null) {
            log("  peringatan: $g:$a:$v tidak punya file jar/aar (mungkin library Multiplatform; tambahkan versi '-jvm' bila classnya tidak ketemu)")
            return ResolvedLib(g, a, v, emptyList(), null, compileOnly)
        }
        val (file, ext) = found
        if (ext == "jar") return ResolvedLib(g, a, v, listOf(file), null, compileOnly)
        val aarDir = File(file.parentFile, "${file.nameWithoutExtension}-aar")
        extractAar(file, aarDir)
        return ResolvedLib(g, a, v, aarJars(aarDir), aarDir, compileOnly)
    }

    private fun aarJars(aarDir: File): List<File> {
        val jars = ArrayList<File>()
        File(aarDir, "classes.jar").takeIf { it.isFile }?.let { jars.add(it) }
        File(aarDir, "libs").listFiles { f -> f.extension == "jar" }?.sortedBy { it.name }?.let { jars.addAll(it) }
        return jars
    }

    /** Library lokal dari folder libs/ proyek (.jar atau .aar). */
    fun localLib(f: File): ResolvedLib {
        if (f.extension == "jar") return ResolvedLib("lokal", f.nameWithoutExtension, "-", listOf(f), null, false)
        val key = "${f.nameWithoutExtension}-${f.length()}-${f.lastModified()}"
        val d = File(File(cacheRoot, "local"), key)
        extractAar(f, d)
        return ResolvedLib("lokal", f.nameWithoutExtension, "-", aarJars(d), d, false)
    }
}
