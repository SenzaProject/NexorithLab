package com.nexo.nexorith.lab

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** UI Preview: merender XML layout proyek langsung (Design) atau menampilkan sumbernya (Code). */
class PreviewScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private class Device(val name: String, val wPx: Int, val hPx: Int, val density: Float) {
        val wDp get() = (wPx / density).toInt()
        val hDp get() = (hPx / density).toInt()
        val label get() = "$name ($wPx x $hPx)"
    }

    private val devices = listOf(
        Device("Pixel 7", 1080, 2400, 2.625f),
        Device("Pixel 4", 1080, 2280, 2.75f),
        Device("Small phone", 720, 1280, 2.0f),
        Device("Tablet", 1200, 1920, 2.0f)
    )

    private var deviceIdx = 0
    private var designMode = true
    private var layoutRel: String? = null

    private lateinit var layoutField: TextView
    private lateinit var deviceField: TextView
    private lateinit var area: LinearLayout
    private lateinit var modeBar: LinearLayout

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "UI Preview", R.drawable.ic_back, { act.back() },
                R.drawable.ic_edit to { editLayout() }
            ), act.lp()
        )
        modeBar = act.segmented(listOf("Design", "Code"), 0) { designMode = it == 0; render() }
        col.addView(modeBar, act.lp(l = 16, r = 16, b = 10))

        layoutField = dropdown { pickLayout() }
        deviceField = dropdown { pickDevice() }
        col.addView(layoutField, act.lp(l = 16, r = 16, b = 8))
        col.addView(deviceField, act.lp(l = 16, r = 16, b = 10))

        area = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(24))
        }
        col.addView(ScrollView(act).apply { addView(area) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun dropdown(onClick: () -> Unit): TextView = act.label("", 14f, Pal.text).apply {
        background = act.glassPress(14, Pal.field)
        setPadding(act.dp(14), act.dp(13), act.dp(14), act.dp(13))
        setSingleLine()
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun select(rel: String) { layoutRel = rel }

    override fun onShow() {
        val p = act.project
        val all = if (p != null) act.store.layouts(p) else emptyList()
        if (layoutRel == null || layoutRel !in all) layoutRel = all.firstOrNull()
        render()
    }

    private fun pickLayout() {
        val p = act.project ?: return
        val all = act.store.layouts(p)
        if (all.isEmpty()) return act.toast("Belum ada file layout di res/layout/")
        act.pickItem("Pilih layout", all.map { it.substringAfterLast('/') }) { layoutRel = all[it]; render() }
    }

    private fun pickDevice() {
        act.pickItem("Pilih perangkat", devices.map { it.label }) { deviceIdx = it; render() }
    }

    private fun editLayout() {
        val rel = layoutRel ?: return act.toast("Belum ada layout")
        act.openFile(rel)
    }

    private fun render() {
        area.removeAllViews()
        val dev = devices[deviceIdx]
        deviceField.text = "▾  " + dev.label
        val p = act.project
        val rel = layoutRel
        layoutField.text = "▾  " + (rel?.substringAfterLast('/') ?: "(tidak ada layout)")
        if (p == null || rel == null) {
            area.addView(message("Belum ada file layout.\nBuat file di res/layout/ lewat Project Explorer (tombol +).", Pal.sub))
            return
        }
        val file = File(act.store.dir(p), rel)
        // gunakan isi dari editor jika file ini sedang terbuka dan belum tersimpan
        if (act.currentFile == rel) act.editor.saveNow()
        val xml = try { file.readText() } catch (e: Exception) { "" }

        if (!designMode) {
            val tv = act.mono("", 12f, Pal.editorFg).apply {
                text = Highlighter.spannable(xml, Highlighter.XML)
                setTextIsSelectable(true)
                setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
                background = act.glass(14, Pal.editorBg)
            }
            area.addView(tv, act.lp())
            return
        }

        val res = LayoutPreview(act, act.store.layout(p).resDir).render(xml)
        if (res.view == null) {
            area.addView(message("⚠ " + (res.error ?: "Tidak bisa merender layout."), Pal.err))
            return
        }
        area.addView(deviceFrame(dev, res.view))
        if (res.warnings.isNotEmpty()) {
            val card = act.card(12, Pal.warnBg)
            card.addView(act.label("Catatan preview", 13f, Pal.warn, true))
            res.warnings.distinct().forEach { card.addView(act.label("• $it", 12f, Pal.text), act.lp(t = 3)) }
            card.addView(act.label("Preview hanya perkiraan. Hasil akhir dilihat setelah APK dipasang.", 11f, Pal.sub), act.lp(t = 6))
            area.addView(card, act.lp(t = 14))
        } else {
            area.addView(
                act.label("Preview hanya perkiraan. Hasil akhir dilihat setelah APK dipasang.", 11f, Pal.dim)
                    .apply { gravity = Gravity.CENTER },
                act.lp(t = 12)
            )
        }
    }

    private fun message(text: String, color: Int): TextView = act.label(text, 14f, color).apply {
        gravity = Gravity.CENTER
        setPadding(act.dp(16), act.dp(30), act.dp(16), act.dp(30))
    }

    /** Bingkai ponsel berukuran dp asli, lalu diperkecil agar muat di lebar layar. */
    private fun deviceFrame(dev: Device, content: View): View {
        val bezel = act.dp(6)
        val frameW = act.dp(dev.wDp) + bezel * 2
        val frameH = act.dp(dev.hDp) + bezel * 2
        val avail = act.resources.displayMetrics.widthPixels - act.dp(64)
        val scale = minOf(1f, avail.toFloat() / frameW)

        val frame = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0B0F1A.toInt())
                cornerRadius = act.dp(34).toFloat()
                setStroke(act.dp(3), 0xFF3A4F8F.toInt())
            }
            setPadding(bezel, bezel, bezel, bezel)
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }
        val screen = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, act.dpf(28f))
                }
            }
        }
        val status = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(18), 0, act.dp(18), 0)
            addView(act.label("10:28", 12f, Color.WHITE, true), act.lp(0, WRAP, weight = 1f))
            addView(act.label("▾ ◢ ▮", 11f, Color.WHITE))
        }
        screen.addView(status, LinearLayout.LayoutParams(MATCH, act.dp(26)))
        val body = FrameLayout(act)
        body.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val nav = LinearLayout(act).apply {
            gravity = Gravity.CENTER
            addView(act.label("◁", 16f, Color.WHITE), act.lp(act.dp(60), WRAP))
            addView(act.label("○", 16f, Color.WHITE), act.lp(act.dp(60), WRAP))
            addView(act.label("□", 16f, Color.WHITE), act.lp(act.dp(60), WRAP))
        }
        nav.getChildAt(0).let { (it as TextView).gravity = Gravity.CENTER }
        nav.getChildAt(1).let { (it as TextView).gravity = Gravity.CENTER }
        nav.getChildAt(2).let { (it as TextView).gravity = Gravity.CENTER }
        screen.addView(nav, LinearLayout.LayoutParams(MATCH, act.dp(44)))
        frame.addView(screen, FrameLayout.LayoutParams(act.dp(dev.wDp), act.dp(dev.hDp)))

        val holder = FrameLayout(act)
        holder.addView(frame, FrameLayout.LayoutParams(frameW, frameH))
        holder.layoutParams = LinearLayout.LayoutParams((frameW * scale).toInt(), (frameH * scale).toInt())
        return holder
    }
}
