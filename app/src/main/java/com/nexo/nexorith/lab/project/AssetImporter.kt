package com.nexo.nexorith.lab.project

import android.content.ContentResolver
import android.net.Uri
import java.io.File

/** Menyalin gambar, font, audio, atau aset mentah dari penyimpanan HP ke folder proyek. */
object AssetImporter {

    enum class Category(
        val label: String,
        val path: String,
        val extensions: Set<String>?,
        val resourceName: Boolean
    ) {
        IMAGE("Gambar (drawable)", "src/main/res/drawable", setOf("png", "jpg", "jpeg", "webp"), true),
        FONT("Font", "src/main/res/font", setOf("ttf", "otf"), true),
        AUDIO("Audio (raw)", "src/main/res/raw", setOf("mp3", "wav", "ogg", "m4a", "aac", "flac"), true),
        ASSET("Aset mentah (assets)", "src/main/assets", null, false)
    }

    /** Memisahkan nama dasar dan ekstensi (huruf kecil). */
    fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot + 1).lowercase()
    }

    /** Nama resource Android: huruf kecil, angka, garis bawah, diawali huruf. */
    fun sanitizeBase(base: String): String {
        var s = base.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        if (s.isEmpty()) s = "file"
        if (!s[0].isLetter()) s = "f_$s"
        return s
    }

    fun targetFileName(category: Category, displayName: String, exists: (String) -> Boolean): String {
        val (base, ext) = splitName(displayName.substringAfterLast('/'))
        val allowed = category.extensions
        require(allowed == null || ext in allowed) {
            "Ekstensi .$ext tidak didukung untuk ${category.label}"
        }
        val stem = if (category.resourceName) sanitizeBase(base) else base.replace('\\', '_').ifEmpty { "file" }
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var name = stem + suffix
        var n = 2
        while (exists(name)) {
            name = "${stem}_$n$suffix"
            n++
        }
        return name
    }

    fun import(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        projectDir: File,
        category: Category
    ): Result<File> = runCatching {
        val destDir = File(projectDir, category.path)
        val name = targetFileName(category, displayName) { File(destDir, it).exists() }
        destDir.mkdirs()
        val dest = File(destDir, name)
        try {
            val input = resolver.openInputStream(uri) ?: error("Tidak bisa membuka file")
            input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        dest
    }
}
