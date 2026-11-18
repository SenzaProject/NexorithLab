package com.nexo.nexorith.lab.maven

import java.io.File
import java.util.zip.ZipInputStream

/** Mengekstrak AAR (classes.jar, res, AndroidManifest.xml, R.txt, dan lainnya) ke folder cache. */
object AarExtractor {

    fun extract(aar: File, dest: File) {
        val marker = File(dest, ".extracted")
        if (marker.isFile) return
        dest.deleteRecursively()
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(aar.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(base)) throw MavenException("Entri AAR tidak aman: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        marker.writeText("ok")
    }
}
