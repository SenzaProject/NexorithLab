package com.nexo.nexorith.lab.project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/** Membuat ikon launcher (semua mipmap + adaptive icon) dari satu gambar. */
object IconGenerator {

    val DENSITIES: List<Pair<String, Int>> = listOf(
        "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192
    )

    private val HEX = Regex("^#[0-9A-Fa-f]{6}\$")

    data class FitBox(val left: Float, val top: Float, val width: Float, val height: Float)

    fun isValidColor(hex: String): Boolean = HEX.matches(hex)

    /** Adaptive icon berukuran 108dp, ikon launcher biasa 48dp. */
    fun foregroundSize(launcherPx: Int): Int = launcherPx * 108 / 48

    /** Menaruh gambar di tengah kotak persegi tanpa mengubah rasio. */
    fun fit(srcW: Int, srcH: Int, boxSize: Float, offset: Float = 0f): FitBox {
        val scale = minOf(boxSize / srcW, boxSize / srcH)
        val w = srcW * scale
        val h = srcH * scale
        return FitBox(offset + (boxSize - w) / 2f, offset + (boxSize - h) / 2f, w, h)
    }

    fun generate(source: Bitmap, projectDir: File, backgroundHex: String) {
        require(isValidColor(backgroundHex)) { "Warna harus berformat #RRGGBB" }
        val bg = Color.parseColor(backgroundHex)
        val res = File(projectDir, "src/main/res")

        for ((density, px) in DENSITIES) {
            val dir = File(res, "mipmap-$density")
            dir.mkdirs()
            save(legacy(source, px, bg, round = false), File(dir, "ic_launcher.png"))
            save(legacy(source, px, bg, round = true), File(dir, "ic_launcher_round.png"))
            save(foreground(source, foregroundSize(px)), File(dir, "ic_launcher_foreground.png"))
        }

        val adaptive = File(res, "mipmap-anydpi-v26")
        adaptive.mkdirs()
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/ic_launcher_background"/>
                <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
            </adaptive-icon>
        """.trimIndent() + "\n"
        File(adaptive, "ic_launcher.xml").writeText(xml)
        File(adaptive, "ic_launcher_round.xml").writeText(xml)

        val values = File(res, "values")
        values.mkdirs()
        File(values, "ic_launcher_background.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="ic_launcher_background">$backgroundHex</color>
            </resources>
            """.trimIndent() + "\n"
        )

        patchManifest(File(projectDir, "src/main/AndroidManifest.xml"))
    }

    private fun patchManifest(manifest: File) {
        if (!manifest.isFile) return
        val text = manifest.readText()
        if (text.contains("android:icon=")) return
        manifest.writeText(
            text.replaceFirst(
                "<application",
                "<application\n        android:icon=\"@mipmap/ic_launcher\"\n        android:roundIcon=\"@mipmap/ic_launcher_round\""
            )
        )
    }

    private fun legacy(src: Bitmap, px: Int, bg: Int, round: Boolean): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path()
        if (round) {
            path.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW)
        } else {
            val r = px * 0.22f
            path.addRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawColor(bg)
        val box = fit(src.width, src.height, px.toFloat())
        canvas.drawBitmap(src, null, rect(box), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun foreground(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val safe = size * 66f / 108f
        val box = fit(src.width, src.height, safe, (size - safe) / 2f)
        canvas.drawBitmap(src, null, rect(box), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun rect(b: FitBox) = RectF(b.left, b.top, b.left + b.width, b.top + b.height)

    private fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
