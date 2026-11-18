package com.nexo.nexorith.lab

import java.io.File

/** Satu baris dependency: group:artifact[:version]. */
data class Dep(val group: String, val artifact: String, val version: String?, val config: String) {
    val ga: String get() = "$group:$artifact"
    val coord: String get() = if (version != null) "$group:$artifact:$version" else ga
}

class GradleInfo(
    val applicationId: String?,
    val namespace: String?,
    val versionCode: Int?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val deps: List<Dep>,
    val boms: List<Dep>,
    val repos: List<String>,
    val warnings: List<String>
)

/**
 * "Gradle-lite": membaca sebagian kecil build.gradle / build.gradle.kts dengan regex.
 * Bukan Gradle sungguhan: plugin, task, dan logika Groovy/Kotlin tidak dijalankan.
 */
object GradleLite {

    val EMPTY = GradleInfo(null, null, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList())

    private val depRe = Regex(
        """\b(implementation|api|compile|runtimeOnly|compileOnly)\s*\(?\s*(platform\s*\(\s*)?["']([^"':\s]+):([^"':\s]+)(?::([^"'\s]+))?["']"""
    )
    private val repoRe = Regex("""\burl\s*(?:=\s*)?(?:uri\s*\(\s*)?["'](https?://[^"']+)["']""")

    fun parse(file: File?): GradleInfo =
        if (file == null || !file.isFile) EMPTY else parseText(file.readText())

    fun parseText(raw: String): GradleInfo {
        val text = raw.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("(?m)(?<![:\"'])//.*$"), "")
        fun str(key: String) = Regex("""\b$key\s*(?:=\s*|\s+)["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        fun num(key: String) = Regex("""\b$key\s*(?:=\s*|\s+)(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

        val deps = ArrayList<Dep>()
        val boms = ArrayList<Dep>()
        for (m in depRe.findAll(text)) {
            val ver = m.groupValues[5].takeIf { it.isNotEmpty() }?.substringBefore('@')
            val d = Dep(m.groupValues[3], m.groupValues[4], ver, m.groupValues[1])
            if (m.groupValues[2].isNotEmpty()) boms.add(d)
            else if (deps.none { it.ga == d.ga }) deps.add(d)
        }
        val warnings = ArrayList<String>()
        if (Regex("""\b(kapt|ksp|annotationProcessor)\s*\(?\s*["']""").containsMatchIn(text))
            warnings.add("Annotation processor (kapt/ksp/annotationProcessor) belum didukung; baris itu diabaikan.")
        if (Regex("""\bproject\s*\(\s*["']:""").containsMatchIn(text))
            warnings.add("Dependency antar-modul (project(':x')) belum didukung; baris itu diabaikan.")
        if (Regex("""\b(implementation|api)\s*\(?\s*libs\.""").containsMatchIn(text))
            warnings.add("Version catalog (libs.xxx) belum didukung; tulis koordinat lengkap 'group:artifact:versi'.")
        if (Regex("""kotlin-android|org\.jetbrains\.kotlin\.android""").containsMatchIn(text))
            warnings.add("Plugin Kotlin terdeteksi, tetapi kode Kotlin belum bisa dikompilasi.")

        return GradleInfo(
            applicationId = str("applicationId"),
            namespace = str("namespace"),
            versionCode = num("versionCode"),
            versionName = str("versionName"),
            minSdk = num("minSdk") ?: num("minSdkVersion"),
            targetSdk = num("targetSdk") ?: num("targetSdkVersion"),
            deps = deps, boms = boms,
            repos = repoRe.findAll(text).map { it.groupValues[1].trimEnd('/') }.toList(),
            warnings = warnings
        )
    }

    fun defaultFile(kts: Boolean): String =
        if (kts) "android {\n    defaultConfig {\n        minSdk = 26\n        targetSdk = 28\n    }\n}\n\ndependencies {\n}\n"
        else "android {\n    defaultConfig {\n        minSdk 26\n        targetSdk 28\n    }\n}\n\ndependencies {\n}\n"

    /** Tambah dependency 'group:artifact:versi' ke blok dependencies. Mengembalikan false jika sudah ada. */
    fun addDependency(file: File, coord: String, config: String = "implementation"): Boolean {
        val kts = file.name.endsWith(".kts")
        val parts = coord.split(':')
        val ga = if (parts.size >= 2) parts[0] + ":" + parts[1] else coord
        var text = if (file.isFile) file.readText() else defaultFile(kts)
        if (parseText(text).deps.any { it.ga == ga }) return false
        val stmt = if (kts) "    $config(\"$coord\")" else "    $config '$coord'"
        val m = Regex("""(?m)^\s*dependencies\s*\{[ \t]*$""").findAll(text).lastOrNull()
        text = if (m != null) {
            text.substring(0, m.range.last + 1) + "\n" + stmt + text.substring(m.range.last + 1)
        } else {
            text.trimEnd() + "\n\ndependencies {\n$stmt\n}\n"
        }
        file.parentFile?.mkdirs()
        file.writeText(text)
        return true
    }

    fun removeDependency(file: File, dep: Dep) {
        if (!file.isFile) return
        val lines = file.readLines().filterNot { line ->
            val m = depRe.find(line)
            m != null && m.groupValues[3] == dep.group && m.groupValues[4] == dep.artifact
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
