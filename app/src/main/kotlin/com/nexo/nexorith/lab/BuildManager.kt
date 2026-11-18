package com.nexo.nexorith.lab

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pemilik tunggal status build. Hidup selama proses aplikasi, jadi build tetap berjalan
 * walau Activity ditutup (dibantu BuildService sebagai foreground service).
 */
object BuildManager {

    val TASKS = listOf(
        "configure", "resolveDependencies", "mergeManifest", "compileResources",
        "linkResources", "compileJava", "dexClasses", "packageApk"
    )

    val log = StringBuilder()
    val observers = CopyOnWriteArrayList<BuildObserver>()

    @Volatile var building = false
    @Volatile var lastApk: File? = null
    @Volatile var lastError: BuildError? = null
    @Volatile var project: String? = null
    @Volatile var step = 0
    @Volatile var stepText = ""

    private val main = Handler(Looper.getMainLooper())
    private var builder: LabBuilder? = null
    private var startMs = 0L

    @Synchronized
    fun builder(ctx: Context): LabBuilder {
        var b = builder
        if (b == null) {
            b = LabBuilder(ctx.applicationContext, { emitLog(it) }, { n, t -> emitStep(n, t) })
            builder = b
        }
        return b
    }

    fun emitLog(line: String) {
        synchronized(log) { log.append(line).append('\n') }
        main.post { observers.forEach { it.onLog(line + "\n") } }
    }

    private fun emitStep(n: Int, text: String) {
        step = n
        stepText = text
        val task = "> Task :" + TASKS.getOrElse(n - 1) { "step$n" } + "  ($text)"
        synchronized(log) { log.append(task).append('\n') }
        main.post {
            observers.forEach { it.onStep(n, LabBuilder.TOTAL_STEPS, text); it.onLog(task + "\n") }
        }
    }

    fun clearLog() { synchronized(log) { log.setLength(0) } }

    fun cancel() {
        builder?.cancel()
    }

    /** Mulai build di thread sendiri (dengan stack besar). false jika sudah ada build berjalan. */
    fun start(ctx: Context, projDir: File, projectName: String, cfg: BuildConfig): Boolean {
        if (building) return false
        val b = builder(ctx)
        b.resetCancel()
        building = true
        lastApk = null
        lastError = null
        project = projectName
        step = 0
        stepText = "Menyiapkan"
        clearLog()
        startMs = System.currentTimeMillis()
        BuildService.start(ctx)
        emitLog("> Nexorith Builder: Gradle-lite > Maven > aapt2 > ECJ > D8 > apksig  [${if (cfg.release) "release" else "debug"}]")
        Thread(null, {
            var ok = false
            try {
                lastApk = b.build(projDir, cfg)
                val sec = (System.currentTimeMillis() - startMs) / 1000
                emitLog("")
                emitLog("BUILD SUCCESSFUL in ${sec}s")
                emitLog("${LabBuilder.TOTAL_STEPS} actionable tasks")
                ok = true
            } catch (e: BuildError) {
                lastError = e
                emitLog("")
                emitLog("BUILD FAILED: ${e.message}")
            } catch (e: Throwable) {
                emitLog(e.stackTraceToString().take(2000))
                lastError = BuildError(BuildError.Kind.OTHER, "Terjadi kesalahan tak terduga: ${e.message ?: e.javaClass.simpleName}")
                emitLog("")
                emitLog("BUILD FAILED")
            } finally {
                building = false
                val success = ok
                main.post { observers.forEach { it.onFinish(success) } }
            }
        }, "nexorith-build", 128L * 1024 * 1024).start()
        return true
    }
}
