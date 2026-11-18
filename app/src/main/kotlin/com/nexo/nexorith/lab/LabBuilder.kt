package com.nexo.nexorith.lab

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Error build dengan jenis, supaya UI bisa menampilkan pesan & tombol yang pas. */
class BuildError(
    val kind: Kind,
    message: String,
    val file: String? = null,
    val line: Int? = null
) : Exception(message) {
    enum class Kind { TOOLS, CODE, RESOURCE, DEPENDENCY, OTHER }
}

/**
 * Pipeline build di perangkat (8 langkah):
 * konfigurasi (Gradle-lite) -> dependency Maven -> gabung manifest -> aapt2 compile -> aapt2 link
 * -> ECJ -> D8 (multi-dex, cache per library) -> rakit APK (rata 4 byte) + tanda tangan (apksig).
 * Langkah resource dan Java dilewati jika masukannya tidak berubah sejak build sebelumnya.
 */
class LabBuilder(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onStep: (Int, String) -> Unit
) {
    companion object { const val TOTAL_STEPS = 8 }

    private val forge = File(ctx.filesDir, "forge")
    private val androidJar = File(forge, "tools/android.jar")
    private val cacheDir = File(forge, "cache")

    @Volatile private var cancelled = false
    @Volatile private var current: Process? = null

    fun cancel() { cancelled = true; current?.destroy() }
    fun resetCancel() { cancelled = false }
    private fun check() { if (cancelled) throw BuildError(BuildError.Kind.OTHER, "Build dibatalkan.") }

    fun apkOf(proj: File) = File(proj, "out/app-signed.apk")
    fun aapt2File() = File(ctx.applicationInfo.nativeLibraryDir, "libaapt2.so")
    fun hasAapt2() = aapt2File().exists()

    fun hasAndroidJar(): Boolean {
        if (androidJar.exists()) return true
        return try { ctx.assets.open("android.jar").close(); true } catch (e: IOException) { false }
    }

    fun prepareTools() {
        if (!androidJar.exists()) {
            androidJar.parentFile?.mkdirs()
            ctx.assets.open("android.jar").use { i -> androidJar.outputStream().use { i.copyTo(it) } }
        }
    }

    fun resolver(logFn: (String) -> Unit = log): DependencyResolver =
        DependencyResolver(File(cacheDir, "deps"), logFn) { cancelled }

    fun clearCaches() { cacheDir.deleteRecursively() }
    fun cacheBytes(): Long = if (cacheDir.exists()) cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** Selesaikan dan unduh semua dependency proyek (tanpa build). */
    fun resolveOnly(proj: File, logFn: (String) -> Unit): List<ResolvedLib> {
        cancelled = false
        val layout = ProjectLayout.detect(proj)
        return resolveDeps(layout, GradleLite.parse(layout.gradleFile), logFn)
    }

    private fun resolveDeps(layout: ProjectLayout, info: GradleInfo, logFn: (String) -> Unit = log): List<ResolvedLib> {
        val resolver = resolver(logFn)
        val libs = ArrayList<ResolvedLib>()
        try {
            if (info.deps.isNotEmpty()) {
                logFn("  ${info.deps.size} dependency di ${layout.gradleFile?.name ?: "build.gradle"}")
                libs.addAll(resolver.resolve(info.deps, info.boms, info.repos))
            }
            layout.libsDir.listFiles { f -> f.isFile && (f.extension == "jar" || f.extension == "aar") }
                ?.sortedBy { it.name }?.forEach { libs.add(resolver.localLib(it)) }
        } catch (e: ResolveError) {
            throw BuildError(BuildError.Kind.DEPENDENCY, e.message ?: "Dependency gagal diselesaikan.", layout.gradleFile?.path, null)
        }
        return libs
    }

    // =====================================================================

    fun build(proj: File, cfg: BuildConfig): File {
        if (!hasAndroidJar()) {
            throw BuildError(BuildError.Kind.TOOLS, "Pustaka Android (android.jar) tidak ada di dalam aplikasi ini.")
        }
        prepareTools()
        val aapt2 = aapt2File()
        if (!aapt2.exists()) {
            throw BuildError(BuildError.Kind.TOOLS, "Alat aapt2 belum terpasang di Nexorith Lab, jadi resource belum bisa diproses.")
        }

        // ---- 1. konfigurasi ----
        check()
        onStep(1, "Membaca konfigurasi proyek")
        val layout = ProjectLayout.detect(proj)
        if (!layout.manifest.isFile) {
            throw BuildError(BuildError.Kind.RESOURCE, "AndroidManifest.xml tidak ditemukan (${layout.manifestRel}).")
        }
        val info = GradleLite.parse(layout.gradleFile)
        info.warnings.forEach { log("peringatan: $it") }
        for (d in layout.srcDirs) {
            if (!d.isDirectory) continue
            val kt = d.walkTopDown().firstOrNull { it.isFile && it.extension == "kt" }
            if (kt != null) {
                throw BuildError(
                    BuildError.Kind.CODE,
                    "File Kotlin (${kt.name}) belum bisa dikompilasi. Saat ini hanya Java yang didukung.",
                    kt.path, null
                )
            }
        }
        val minSdk = maxOf(21, info.minSdk ?: 26)
        val targetSdk = info.targetSdk ?: 28
        val manifestPkg = ManifestMerger.readPackage(layout.manifest)
        val appId = if (layout.gradleStyle) info.applicationId ?: info.namespace ?: manifestPkg
        else manifestPkg ?: info.applicationId ?: info.namespace
        val rPackage = if (layout.gradleStyle) info.namespace ?: manifestPkg ?: appId else null
        if (appId == null) {
            throw BuildError(BuildError.Kind.RESOURCE, "Package aplikasi tidak ditemukan di AndroidManifest.xml maupun build.gradle.", layout.manifest.path, null)
        }
        val rCustom = rPackage?.takeIf { it != appId }

        val out = File(proj, "out").apply { mkdirs() }
        val fpFile = File(out, "fingerprints.properties")
        val fp = Properties()
        if (fpFile.isFile) fpFile.inputStream().use { fp.load(it) }
        fun saveFp() { fpFile.outputStream().use { fp.store(it, null) } }

        // ---- 2. dependency ----
        check()
        onStep(2, "Menyelesaikan dependency")
        val libs = resolveDeps(layout, info)
        val runtimeLibs = libs.filter { !it.compileOnly }
        val allJars = libs.flatMap { it.jars }
        val runtimeJars = runtimeLibs.flatMap { it.jars }
        if (libs.isNotEmpty()) log("  ${libs.size} library siap (${runtimeJars.size} jar)")

        // ---- 3. manifest ----
        check()
        onStep(3, "Menggabungkan manifest")
        val merged = File(out, "merged/AndroidManifest.xml")
        try {
            ManifestMerger.merge(layout.manifest, runtimeLibs.mapNotNull { it.manifest }, appId, merged)
        } catch (e: Exception) {
            throw BuildError(BuildError.Kind.RESOURCE, "Gagal menggabungkan manifest: ${e.message}", layout.manifest.path, null)
        }

        // ---- 4 & 5. resource ----
        check()
        val base = File(out, "base.apk")
        val gen = File(out, "gen")
        val resFiles = filesOf(layout.resDir) + filesOf(layout.assetsDir)
        val fpRes = sha1(
            listOf(
                "v1", cfg.release, cfg.versionCode, cfg.versionName, minSdk, targetSdk, appId, rPackage,
                merged.readText(), aapt2.length(),
                runtimeLibs.joinToString { it.coord + (it.resDir?.path ?: "") + (it.assetsDir?.path ?: "") },
                stat(resFiles)
            ).joinToString("\u0001")
        )
        if (fp.getProperty("res") == fpRes && base.isFile && gen.isDirectory) {
            onStep(4, "Resource tidak berubah, dilewati")
            onStep(5, "Resource tidak berubah, dilewati")
        } else {
            onStep(4, "Mengompilasi resource")
            gen.deleteRecursively(); gen.mkdirs()
            val zips = ArrayList<File>()
            for (lib in runtimeLibs.reversed()) {
                val rd = lib.resDir ?: continue
                zips.add(compileLibRes(aapt2, lib, rd))
            }
            var appZip: File? = null
            if (filesOf(layout.resDir).isNotEmpty()) {
                appZip = File(out, "res.zip")
                appZip.delete()
                execAapt(listOf(aapt2.path, "compile", "--dir", layout.resDir.path, "-o", appZip.path), "membaca resource")
            }

            check()
            onStep(5, "Menyusun resource dan manifest")
            val assetsMerged = File(out, "assets_merged")
            assetsMerged.deleteRecursively()
            val assetSources = runtimeLibs.reversed().mapNotNull { it.assetsDir } +
                listOfNotNull(layout.assetsDir.takeIf { it.isDirectory })
            if (assetSources.isNotEmpty()) {
                assetsMerged.mkdirs()
                assetSources.forEach { it.copyRecursively(assetsMerged, overwrite = true) }
            }
            val extraPkgs = runtimeLibs.mapNotNull { it.packageName }.filter { it != appId && it != rPackage }.distinct()
            val cmd = mutableListOf(
                aapt2.path, "link", "-o", base.path, "-I", androidJar.path,
                "--manifest", merged.path, "--java", gen.path, "--auto-add-overlay",
                "--min-sdk-version", minSdk.toString(), "--target-sdk-version", targetSdk.toString(),
                "--version-code", cfg.versionCode.toString(), "--version-name", cfg.versionName
            )
            if (rCustom != null) { cmd.add("--custom-package"); cmd.add(rCustom) }
            if (extraPkgs.isNotEmpty()) { cmd.add("--extra-packages"); cmd.add(extraPkgs.joinToString(":")) }
            if (assetsMerged.isDirectory) { cmd.add("-A"); cmd.add(assetsMerged.path) }
            if (!cfg.release) cmd.add("--debug-mode")
            zips.forEach { cmd.add(it.path) }
            appZip?.let { cmd.add(it.path) }
            execAapt(cmd, "menyusun resource")
            fp.setProperty("res", fpRes)
            saveFp()
        }

        // ---- 6 & 7. Java + dex ----
        check()
        val classes = File(out, "classes")
        val dexDir = File(out, "dex")
        val javaFiles = (layout.srcDirs.filter { it.isDirectory }.flatMap { it.walkTopDown().toList() } + gen.walkTopDown())
            .filter { it.isFile && it.extension == "java" }
        val fpJava = sha1(
            listOf("v1", fpRes, cfg.release, minSdk, stat(javaFiles.filter { !it.path.startsWith(gen.path) }), stat(allJars)).joinToString("\u0001")
        )
        if (fp.getProperty("java") == fpJava && File(dexDir, "classes.dex").isFile) {
            onStep(6, "Kode Java tidak berubah, dilewati")
            onStep(7, "Dex tidak berubah, dilewati")
        } else {
            onStep(6, "Mengompilasi kode Java (ECJ)")
            if (javaFiles.none { !it.path.startsWith(gen.path) }) {
                throw BuildError(BuildError.Kind.CODE, "Tidak ada file .java di proyek (${layout.javaRootRel}).")
            }
            classes.deleteRecursively(); classes.mkdirs()
            compileJava(javaFiles.map { it.path }, allJars, classes)

            check()
            onStep(7, "Mengubah ke dex (D8, multi-dex)")
            dexAll(classes, runtimeJars, allJars, minSdk, cfg.release, dexDir, out)
            fp.setProperty("java", fpJava)
            saveFp()
        }

        // ---- 8. rakit + tanda tangan ----
        check()
        onStep(8, "Merakit dan menandatangani APK")
        val unsigned = File(out, "app-unsigned.apk")
        assemble(base, dexDir, runtimeLibs, runtimeJars, layout, unsigned)
        val signedApk = apkOf(proj)
        try {
            sign(unsigned, signedApk, minSdk)
        } catch (e: Exception) {
            throw BuildError(BuildError.Kind.OTHER, "Gagal menandatangani APK: ${e.message}")
        }
        log("Selesai: ${signedApk.length() / 1024} KB")
        return signedApk
    }

    // =====================================================================
    //  langkah-langkah
    // =====================================================================

    private fun compileLibRes(aapt2: File, lib: ResolvedLib, resDir: File): File {
        val dir = lib.aarDir ?: throw BuildError(BuildError.Kind.OTHER, "Resource library tanpa AAR: ${lib.coord}")
        val zip = File(dir, ".res.flata.zip")
        val mark = File(dir, ".res.mark")
        val stamp = aapt2.length().toString()
        if (zip.isFile && mark.isFile && mark.readText() == stamp) return zip
        log("  compile resource ${lib.artifact}")
        zip.delete()
        execAapt(listOf(aapt2.path, "compile", "--dir", resDir.path, "-o", zip.path), "membaca resource ${lib.artifact}")
        mark.writeText(stamp)
        return zip
    }

    private fun compileJava(javaPaths: List<String>, jars: List<File>, classes: File) {
        val args = ArrayList<String>()
        args.addAll(listOf("-1.8", "-proc:none", "-nowarn", "-bootclasspath", androidJar.path))
        if (jars.isNotEmpty()) {
            args.add("-classpath")
            args.add(jars.joinToString(File.pathSeparator) { it.path })
        }
        args.addAll(listOf("-d", classes.path))
        args.addAll(javaPaths)
        val sw = StringWriter()
        val pw = PrintWriter(sw, true)
        val ok = org.eclipse.jdt.internal.compiler.batch.Main(pw, pw, false, null, null).compile(args.toTypedArray())
        pw.flush()
        val text = sw.toString()
        if (text.isNotBlank()) log(text)
        if (ok) return
        val m = Regex("ERROR in (.+?) \\(at line (\\d+)\\)\\R(?:.*\\R){2}(.*)").find(text)
        if (m != null) {
            val path = m.groupValues[1]
            val line = m.groupValues[2].toInt()
            throw BuildError(
                BuildError.Kind.CODE,
                "${File(path).name}, baris $line:\n${friendlyJava(m.groupValues[3].trim())}",
                path, line
            )
        }
        throw BuildError(BuildError.Kind.CODE, "Kode Java tidak bisa dikompilasi. Ketuk 'Salin log' untuk detail.")
    }

    /** Dex tiap library sekali (di-cache), dex kode aplikasi, lalu gabung semuanya (multi-dex otomatis). */
    private fun dexAll(
        classes: File, runtimeJars: List<File>, classpathJars: List<File>,
        minSdk: Int, release: Boolean, dexDir: File, out: File
    ) {
        val mode = if (release) CompilationMode.RELEASE else CompilationMode.DEBUG
        val cpPaths: List<Path> = classpathJars.map { it.toPath() }
        val dexCache = File(cacheDir, "dex").apply { mkdirs() }
        val archives = ArrayList<Path>()
        try {
            for (jar in runtimeJars.distinct()) {
                check()
                val key = sha1("${jar.path}|${jar.length()}|${jar.lastModified()}|$minSdk|$release")
                val arc = File(dexCache, "$key.zip")
                if (!arc.isFile) {
                    log("  dex ${jar.parentFile?.name ?: ""}/${jar.name}")
                    val tmp = File(dexCache, "$key.part.zip")
                    tmp.delete()
                    D8.run(
                        D8Command.builder()
                            .addProgramFiles(jar.toPath())
                            .addLibraryFiles(androidJar.toPath())
                            .addClasspathFiles(cpPaths.filter { it != jar.toPath() })
                            .setMinApiLevel(minSdk)
                            .setMode(mode)
                            .setIntermediate(true)
                            .setOutput(tmp.toPath(), OutputMode.DexFilePerClassFile)
                            .build()
                    )
                    if (tmp.isFile) { if (!tmp.renameTo(arc)) { tmp.copyTo(arc, true); tmp.delete() } }
                    else arc.writeBytes(ByteArray(0)) // jar tanpa kelas
                }
                if (arc.length() > 0) archives.add(arc.toPath())
            }

            check()
            val appArc = File(out, "app-classes.zip")
            appArc.delete()
            val classFiles = classes.walk().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList()
            if (classFiles.isNotEmpty()) {
                D8.run(
                    D8Command.builder()
                        .addProgramFiles(classFiles)
                        .addLibraryFiles(androidJar.toPath())
                        .addClasspathFiles(cpPaths)
                        .setMinApiLevel(minSdk)
                        .setMode(mode)
                        .setIntermediate(true)
                        .setOutput(appArc.toPath(), OutputMode.DexFilePerClassFile)
                        .build()
                )
                if (appArc.isFile) archives.add(appArc.toPath())
            }

            dexDir.deleteRecursively()
            dexDir.mkdirs()
            D8.run(
                D8Command.builder()
                    .addProgramFiles(archives)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                    .setMinApiLevel(minSdk)
                    .setMode(mode)
                    .build()
            )
        } catch (e: BuildError) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val hint = if (msg.contains("multiple times")) {
                "\n(Dua library berisi kelas yang sama. Hapus salah satu dependency yang bentrok di build.gradle.)"
            } else ""
            throw BuildError(BuildError.Kind.OTHER, "Gagal mengubah kode ke format Android: $msg$hint")
        }
    }

    /** Menyusun APK: isi base.apk + dex + library native + resource Java dari jar, dengan rata 4 byte. */
    private fun assemble(
        base: File, dexDir: File, libs: List<ResolvedLib>, runtimeJars: List<File>,
        layout: ProjectLayout, dest: File
    ) {
        val seen = HashSet<String>()
        val cnt = CountingOut(BufferedOutputStream(dest.outputStream()))
        ZipOutputStream(cnt).use { zo ->
            ZipFile(base).use { zf ->
                for (e in zf.entries()) {
                    if (!seen.add(e.name)) continue
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    putEntry(zo, cnt, e.name, bytes, e.method == ZipEntry.STORED)
                }
            }
            dexDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }?.forEach {
                if (seen.add(it.name)) putEntry(zo, cnt, it.name, it.readBytes(), false)
            }
            // library native (.so) dari proyek dan AAR
            val jniRoots = listOfNotNull(layout.jniDir.takeIf { it.isDirectory }) + libs.mapNotNull { it.jniDir }
            for (root in jniRoots) {
                root.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { f ->
                    val name = "lib/" + f.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (seen.add(name)) putEntry(zo, cnt, name, f.readBytes(), false)
                }
            }
            // resource Java di dalam jar (mis. file .properties, publicsuffixes.gz, META-INF/services)
            for (jar in runtimeJars.distinct()) {
                ZipFile(jar).use { zf ->
                    for (e in zf.entries()) {
                        if (e.isDirectory) continue
                        val n = e.name
                        if (n.endsWith(".class") || n == "AndroidManifest.xml" || n == "R.txt") continue
                        if (n.startsWith("META-INF/") && !n.startsWith("META-INF/services/")) continue
                        if (n.endsWith(".kotlin_module") || n.endsWith(".kotlin_builtins")) continue
                        if (!seen.add(n)) continue
                        putEntry(zo, cnt, n, zf.getInputStream(e).use { it.readBytes() }, false)
                    }
                }
            }
        }
    }

    private class CountingOut(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    private fun putEntry(zo: ZipOutputStream, cnt: CountingOut, name: String, bytes: ByteArray, stored: Boolean) {
        val ze = ZipEntry(name)
        if (stored) {
            ze.method = ZipEntry.STORED
            ze.size = bytes.size.toLong()
            ze.compressedSize = bytes.size.toLong()
            ze.crc = CRC32().also { it.update(bytes) }.value
            // rata 4 byte (wajib untuk resources.arsc pada aplikasi target Android 11+)
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val pad = ((4 - ((cnt.count + 30 + nameLen) % 4)) % 4).toInt()
            if (pad > 0) ze.extra = ByteArray(pad)
        }
        zo.putNextEntry(ze)
        zo.write(bytes)
        zo.closeEntry()
    }

    // =====================================================================
    //  helper
    // =====================================================================

    private fun filesOf(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else emptyList()

    private fun stat(files: List<File>): String =
        files.joinToString("\n") { "${it.path}|${it.length()}|${it.lastModified()}" }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun friendlyJava(msg: String): String = when {
        msg.contains("Syntax error") -> "$msg\n(Cek tanda ; ) } yang kurang atau berlebih.)"
        msg.contains("cannot be resolved to a type") -> "$msg\n(Mungkin kurang 'import', salah ketik, atau library belum ditambahkan di build.gradle.)"
        msg.contains("cannot be resolved") -> "$msg\n(Nama ini belum dibuat atau salah ketik.)"
        msg.contains("is undefined") -> "$msg\n(Fungsi ini tidak ada. Cek ejaan dan huruf besar/kecil.)"
        msg.contains("The import") && msg.contains("cannot be resolved") ->
            "$msg\n(Library untuk import ini belum ditambahkan. Buka layar Dependencies.)"
        else -> msg
    }

    private fun execAapt(cmd: List<String>, what: String) {
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "aapt2 tidak bisa dijalankan di perangkat ini. Pastikan itu versi Android ARM64 yang berdiri sendiri.\n${e.message}"
            )
        }
        current = p
        val outText = StringBuilder()
        try {
            p.inputStream.bufferedReader().forEachLine { outText.appendLine(it); log(it) }
        } finally {
            val code = p.waitFor()
            current = null
            if (cancelled) throw BuildError(BuildError.Kind.OTHER, "Build dibatalkan.")
            if (code != 0) aaptFailed(code, outText.toString(), what)
        }
    }

    private fun aaptFailed(code: Int, outText: String, what: String): Nothing {
        if (outText.contains("CANNOT LINK EXECUTABLE") || code == 126 || code == 127) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "File aapt2 tidak cocok dengan perangkat ini (butuh versi Android ARM64 yang berdiri sendiri)."
            )
        }
        val m = Regex("(\\S+?):(\\d+): error: (.+)").find(outText)
        if (m != null) {
            throw BuildError(
                BuildError.Kind.RESOURCE,
                "${File(m.groupValues[1]).name}, baris ${m.groupValues[2]}:\n${m.groupValues[3]}",
                m.groupValues[1], m.groupValues[2].toInt()
            )
        }
        val m2 = Regex("error: (.+)").find(outText)
        if (m2 != null) throw BuildError(BuildError.Kind.RESOURCE, m2.groupValues[1])
        throw BuildError(BuildError.Kind.RESOURCE, "Gagal $what (kode $code). Ketuk 'Salin log' untuk detail.")
    }

    private fun sign(input: File, output: File, minSdk: Int) {
        val pass = "android".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("debug.p12").use { ks.load(it, pass) }
        val alias = ks.aliases().nextElement()
        val key = ks.getKey(alias, pass) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        val cfg = ApkSigner.SignerConfig.Builder("forge", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }
}
