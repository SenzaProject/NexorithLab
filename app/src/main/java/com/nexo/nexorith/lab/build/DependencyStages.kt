package com.nexo.nexorith.lab.build

import com.nexo.nexorith.lab.maven.AarExtractor
import com.nexo.nexorith.lab.maven.HttpFetcher
import com.nexo.nexorith.lab.maven.MavenException
import com.nexo.nexorith.lab.maven.MavenResolver
import java.io.File
import java.io.IOException

class ResolveDependenciesStage : Stage("Resolve dependensi Maven") {
    override fun needed(ctx: BuildContext): Boolean = ctx.config?.dependencies?.isNotEmpty() == true

    override fun run(ctx: BuildContext) {
        val config = ctx.config ?: throw StageFailure("Konfigurasi belum dibaca")
        val resolver = MavenResolver(
            MavenResolver.DEFAULT_REPOS,
            HttpFetcher(),
            File(ctx.cacheDir, "maven"),
            { ctx.log(it) },
            { ctx.isCancelled() }
        )
        try {
            ctx.resolved = resolver.resolve(config.dependencies)
        } catch (e: MavenException) {
            throw StageFailure(e.message ?: "Gagal menyelesaikan dependensi")
        } catch (e: IOException) {
            throw StageFailure("Gagal mengunduh: ${e.message}")
        }
        ctx.log("Dependensi terselesaikan: ${ctx.resolved.size}")
    }
}

class ExtractAarStage : Stage("Ekstrak AAR") {
    override fun needed(ctx: BuildContext): Boolean = ctx.resolved.any { it.packaging == "aar" }

    override fun run(ctx: BuildContext) {
        var count = 0
        for (r in ctx.resolved) {
            if (r.packaging != "aar") continue
            ctx.checkCancelled()
            val c = r.coordinate
            val dest = File(ctx.cacheDir, "aar/${c.group}/${c.artifact}/${c.version}")
            try {
                AarExtractor.extract(r.file, dest)
            } catch (e: MavenException) {
                throw StageFailure(e.message ?: "Gagal mengekstrak AAR")
            } catch (e: IOException) {
                throw StageFailure("Gagal mengekstrak $c: ${e.message}")
            }
            r.extractDir = dest
            count++
        }
        ctx.log("AAR diekstrak: $count")
    }
}
