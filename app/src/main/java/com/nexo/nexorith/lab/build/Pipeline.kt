package com.nexo.nexorith.lab.build

import java.io.File

class StageFailure(message: String) : Exception(message)

class BuildCancelled : Exception("dibatalkan")

class BuildContext(
    val projectDir: File,
    private val sink: (String) -> Unit,
    private val cancelFlag: () -> Boolean
) {
    var config: ProjectConfig? = null

    fun log(line: String) = sink(line)

    fun checkCancelled() {
        if (cancelFlag()) throw BuildCancelled()
    }
}

abstract class Stage(val name: String) {
    open fun needed(ctx: BuildContext): Boolean = true
    abstract fun run(ctx: BuildContext)
}

class LoadProjectStage : Stage("Muat proyek") {
    override fun run(ctx: BuildContext) {
        val dir = ctx.projectDir
        if (!dir.isDirectory) throw StageFailure("Folder proyek tidak ditemukan: ${dir.path}")
        if (!File(dir, "build.gradle").isFile) throw StageFailure("build.gradle tidak ada di root proyek")
        if (!File(dir, "src/main/AndroidManifest.xml").isFile) {
            throw StageFailure("src/main/AndroidManifest.xml tidak ada")
        }
        val sources = File(dir, "src/main/java").walkTopDown()
            .count { it.isFile && (it.extension == "java" || it.extension == "kt") }
        ctx.log("Folder: ${dir.name}, file sumber: $sources")
    }
}

class ParseConfigStage : Stage("Baca konfigurasi (Gradle-lite)") {
    override fun run(ctx: BuildContext) {
        val config = GradleLite.parse(File(ctx.projectDir, "build.gradle").readText())
        val errors = GradleLite.validate(config)
        if (errors.isNotEmpty()) throw StageFailure(errors.joinToString("; "))
        ctx.config = config
        ctx.log("applicationId=${config.applicationId}, minSdk=${config.minSdk}, dependensi=${config.dependencies.size}")
        config.dependencies.forEach { ctx.log("  ${it.configuration} ${it.coordinate}") }
    }
}

/** Tahap yang belum dikerjakan. Pipeline berhenti dengan pesan jujur, tidak pura-pura sukses. */
class PendingStage(name: String, private val onlyWithDependencies: Boolean = false) : Stage(name) {
    override fun needed(ctx: BuildContext): Boolean =
        !onlyWithDependencies || ctx.config?.dependencies?.isNotEmpty() == true

    override fun run(ctx: BuildContext) {
        throw StageFailure("Tahap \"$name\" belum diimplementasikan (NOT STARTED). Pipeline berhenti di sini.")
    }
}

object BuildPipeline {
    fun default(): List<Stage> = listOf(
        LoadProjectStage(),
        ParseConfigStage(),
        PendingStage("Resolve dependensi Maven", onlyWithDependencies = true),
        PendingStage("Ekstrak AAR", onlyWithDependencies = true),
        PendingStage("Gabung manifest"),
        PendingStage("Kompilasi resource (aapt2)"),
        PendingStage("Kompilasi Java"),
        PendingStage("Konversi ke DEX"),
        PendingStage("Kemas APK"),
        PendingStage("Tanda tangan APK")
    )
}
