package com.nexo.nexorith.lab

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Pengaturan build per proyek (disimpan di nexorith.properties). */
data class BuildConfig(
    val appName: String,
    val versionCode: Int,
    val versionName: String,
    val release: Boolean
)

/** Mengelola banyak proyek di filesDir/forge/projects/<nama>. */
class ProjectStore(private val ctx: Context) {

    companion object {
        val TEXT_EXT = setOf(
            "java", "xml", "txt", "md", "json", "properties", "gradle", "kts", "kt", "html", "css", "js", "csv", "yml", "yaml"
        )

        fun isTextFile(f: File) = f.extension.lowercase() in TEXT_EXT || f.name.startsWith(".git")

        /** Saran package dari nama aplikasi: "Kalkulator Ku" -> com.example.kalkulatorku */
        fun suggestPackage(appName: String): String {
            var seg = appName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            if (seg.isEmpty()) seg = "app"
            if (seg[0].isDigit()) seg = "app$seg"
            if (seg in Highlighter.JAVA_KEYWORDS) seg += "app"
            return "com.example.$seg"
        }

        fun humanSize(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> String.format("%.1f MB", n / 1048576.0)
        }
    }

    val base = File(ctx.filesDir, "forge").apply { mkdirs() }
    val root = File(base, "projects").apply { mkdirs() }

    fun list(): List<String> =
        root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun dir(name: String) = File(root, name)

    /** Letak folder proyek (susunan sendiri atau susunan Gradle). */
    fun layout(name: String): ProjectLayout = ProjectLayout.detect(dir(name))

    fun packageOf(name: String): String {
        val l = layout(name)
        val fromManifest = l.manifest.takeIf { it.isFile }?.let { ManifestMerger.readPackage(it) }
        if (l.gradleStyle) {
            val g = GradleLite.parse(l.gradleFile)
            return g.namespace ?: fromManifest ?: g.applicationId ?: ""
        }
        return fromManifest ?: ""
    }

    fun create(name: String, pkg: String): File {
        require(Regex("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}").matches(name)) {
            "Nama hanya boleh huruf, angka, spasi, _ atau - (maksimal 40 karakter)."
        }
        require(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(pkg)) {
            "Package tidak valid. Contoh: com.contoh.app (huruf kecil, dipisah titik)."
        }
        require(pkg.split('.').none { it in Highlighter.JAVA_KEYWORDS }) {
            "Package tidak boleh memakai kata kunci Java."
        }
        val d = dir(name)
        require(!d.exists()) { "Proyek '$name' sudah ada." }
        copyTemplate("template", d, "", pkg, name)
        return d
    }

    // ---------- berkas ----------

    /** Semua file .java dan .xml di proyek (tanpa folder out), path relatif. */
    fun files(name: String): List<String> {
        val d = dir(name)
        return d.walkTopDown()
            .onEnter { it == d || it.name != "out" }
            .filter { it.isFile && (it.extension == "java" || it.extension == "xml") }
            .map { it.relativeTo(d).path }
            .sorted()
            .toList()
    }

    fun layouts(name: String): List<String> {
        val prefix = layout(name).resRel + "/layout"
        return files(name).filter { it.startsWith(prefix) && it.endsWith(".xml") }
    }

    fun newFile(project: String, rel: String, content: String? = null): File {
        require(rel.isNotBlank() && !rel.contains("..") && !rel.startsWith("/")) { "Path tidak valid." }
        require(rel.endsWith(".java") || rel.endsWith(".xml")) { "Hanya file .java atau .xml." }
        val l = layout(project)
        require(!rel.endsWith(".java") || rel.startsWith(l.javaRootRel + "/")) { "File .java harus di dalam ${l.javaRootRel}/." }
        val f = File(dir(project), rel)
        require(!f.exists()) { "File itu sudah ada." }
        f.parentFile?.mkdirs()
        f.writeText(content ?: skeleton(rel, l))
        return f
    }

    private fun skeleton(rel: String, l: ProjectLayout): String {
        if (rel.endsWith(".java")) {
            val parts = rel.removePrefix(l.javaRootRel + "/").removeSuffix(".java").split('/')
            val cls = parts.last()
            val pkg = parts.dropLast(1).joinToString(".")
            val header = if (pkg.isEmpty()) "" else "package $pkg;\n\n"
            return "${header}public class $cls {\n\n}\n"
        }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        if (rel.startsWith(l.resRel + "/layout")) {
            return xml + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"match_parent\"\n" +
                "    android:orientation=\"vertical\">\n\n</LinearLayout>\n"
        }
        return if (rel.startsWith(l.resRel + "/values")) "$xml<resources>\n\n</resources>\n" else xml
    }

    // ---------- pengaturan build ----------

    private fun configFile(name: String) = File(dir(name), "nexorith.properties")

    private fun stringsFile(name: String) = File(layout(name).resDir, "values/strings.xml")

    fun appLabel(name: String): String {
        val f = stringsFile(name)
        if (!f.exists()) return name
        return Regex("<string\\s+name=\"app_name\"[^>]*>([^<]*)</string>").find(f.readText())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: name
    }

    /** Ubah teks app_name di strings.xml. Mengembalikan false jika tidak ditemukan. */
    fun setAppLabel(name: String, label: String): Boolean {
        val f = stringsFile(name)
        if (!f.exists()) return false
        val text = f.readText()
        val re = Regex("(<string\\s+name=\"app_name\"[^>]*>)[^<]*(</string>)")
        if (!re.containsMatchIn(text)) return false
        val safe = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")
        val m = re.find(text) ?: return false
        val out = text.substring(0, m.range.first) + m.groupValues[1] + safe + m.groupValues[2] +
            text.substring(m.range.last + 1)
        if (out != text) f.writeText(out)
        return true
    }

    fun config(name: String): BuildConfig {
        val p = Properties()
        configFile(name).takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
        return BuildConfig(
            appName = appLabel(name),
            versionCode = p.getProperty("versionCode")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            versionName = p.getProperty("versionName")?.takeIf { it.isNotBlank() } ?: "1.0.0",
            release = p.getProperty("type") == "release"
        )
    }

    fun saveConfig(name: String, c: BuildConfig) {
        val p = Properties()
        p.setProperty("versionCode", c.versionCode.toString())
        p.setProperty("versionName", c.versionName)
        p.setProperty("type", if (c.release) "release" else "debug")
        configFile(name).outputStream().use { p.store(it, "Nexorith Lab build config") }
    }

    // ---------- impor / ekspor ----------

    fun exportZip(name: String, out: OutputStream) {
        val d = dir(name)
        ZipOutputStream(out.buffered()).use { zo ->
            d.walkTopDown()
                .onEnter { it == d || it.name != "out" }
                .filter { it.isFile }
                .forEach { f ->
                    zo.putNextEntry(ZipEntry(name + "/" + f.relativeTo(d).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zo) }
                    zo.closeEntry()
                }
        }
    }

    /** Impor proyek dari zip. Mengembalikan nama proyek baru. */
    fun importZip(input: InputStream, suggestedName: String): String {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zi.readBytes()
                total += bytes.size
                require(total < 200L * 1024 * 1024) { "Zip terlalu besar." }
                entries[e.name.replace('\\', '/')] = bytes
            }
        }
        val manifest = entries.keys
            .filter { it == "AndroidManifest.xml" || it.endsWith("/AndroidManifest.xml") }
            .minByOrNull { it.count { c -> c == '/' } }
        require(manifest != null) {
            "Zip ini bukan proyek Android (AndroidManifest.xml tidak ditemukan)."
        }
        // susunan Gradle: <modul>/src/main/AndroidManifest.xml  -> akar proyek = induk modul
        val gradleSuffix = "src/main/AndroidManifest.xml"
        val prefix: String
        if (manifest.endsWith(gradleSuffix)) {
            val moduleDir = manifest.removeSuffix(gradleSuffix) // "", "app/", "Proj/app/"
            val hasSettings = entries.keys.any { it == moduleDir + "settings.gradle" || it == moduleDir + "settings.gradle.kts" }
            prefix = if (hasSettings || moduleDir.isEmpty()) moduleDir
            else moduleDir.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        } else {
            prefix = manifest.removeSuffix("AndroidManifest.xml")
        }
        require(prefix.count { it == '/' } <= 1) { "Struktur zip terlalu dalam. Letakkan proyek di akar zip." }

        var clean = suggestedName.removeSuffix(".zip").filter { it.isLetterOrDigit() || it in " _-" }.trim().take(40)
        if (clean.isEmpty()) clean = "Imported"
        var name = clean
        var n = 2
        while (dir(name).exists()) name = "$clean-${n++}"

        val dest = dir(name).apply { mkdirs() }
        val destCanon = dest.canonicalPath
        for ((path, bytes) in entries) {
            if (!path.startsWith(prefix)) continue
            val rel = path.removePrefix(prefix)
            if (rel.isEmpty() || rel.startsWith("out/")) continue
            if (rel.startsWith(".git/") || rel.startsWith(".gradle/") || rel.startsWith(".idea/") ||
                rel.startsWith("build/") || rel.contains("/build/") || rel == "local.properties"
            ) continue
            val f = File(dest, rel)
            require(f.canonicalPath.startsWith(destCanon + File.separator)) { "Zip berisi path berbahaya." }
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        return name
    }

    private fun copyTemplate(assetPath: String, destRoot: File, rel: String, pkg: String, name: String) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            val outRel = rel.replace("com/example/hello", pkg.replace('.', '/'))
            val text = ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
                .replace("com.example.hello", pkg)
                .replace("__APP_NAME__", name)
            File(destRoot, outRel).apply { parentFile?.mkdirs() }.writeText(text)
        } else {
            children.forEach {
                copyTemplate("$assetPath/$it", destRoot, if (rel.isEmpty()) it else "$rel/$it", pkg, name)
            }
        }
    }
}
