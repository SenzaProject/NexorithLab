package com.nexo.nexorith.lab

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Dependencies: daftar library proyek (dari build.gradle), tambah/hapus, unduh ke cache, dan hapus cache. */
class DepsScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private val popular = listOf(
        "AppCompat" to "androidx.appcompat:appcompat:1.6.1",
        "Material Components" to "com.google.android.material:material:1.11.0",
        "ConstraintLayout" to "androidx.constraintlayout:constraintlayout:2.1.4",
        "RecyclerView" to "androidx.recyclerview:recyclerview:1.3.2",
        "CardView" to "androidx.cardview:cardview:1.0.0",
        "ViewPager2" to "androidx.viewpager2:viewpager2:1.0.0",
        "SwipeRefreshLayout" to "androidx.swiperefreshlayout:swiperefreshlayout:1.1.0",
        "Fragment" to "androidx.fragment:fragment:1.6.2",
        "Gson (JSON)" to "com.google.code.gson:gson:2.10.1",
        "OkHttp (jaringan)" to "com.squareup.okhttp3:okhttp:4.12.0",
        "Glide (gambar)" to "com.github.bumptech.glide:glide:4.16.0"
    )

    @Volatile private var working = false

    private lateinit var body: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logCard: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancelBtn: LinearLayout

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Dependencies", R.drawable.ic_back, { act.back() },
                R.drawable.ic_plus to { addDialog() }
            ), act.lp()
        )
        body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // kartu log (dibuat sekali, dipasang ulang tiap rebuild)
        logCard = act.card(14)
        status = act.label("", 12f, Pal.sub)
        logCard.addView(act.label("Log unduhan", 15f, Pal.text, true))
        logCard.addView(status, act.lp(t = 4, b = 6))
        logView = TextView(act).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Pal.text)
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(act).apply {
            background = act.glass(10, 0x66050B2C, 0x22FFFFFF)
            addView(logView)
        }
        logCard.addView(logScroll, LinearLayout.LayoutParams(MATCH, act.dp(180)))
        cancelBtn = act.glassButton("Batalkan", R.drawable.ic_close, 13f, Pal.err) { act.builder.cancel() }
        cancelBtn.visibility = View.GONE
        logCard.addView(cancelBtn, act.lp(t = 8))
        return col
    }

    override fun onShow() { rebuild() }

    private fun rebuild() {
        body.removeAllViews()
        val p = act.project
        if (p == null) {
            body.addView(act.label("Buka proyek dulu.", 14f, Pal.sub), act.lp(t = 16))
            return
        }
        val l = act.store.layout(p)
        val info = GradleLite.parse(l.gradleFile)
        val resolver = act.builder.resolver()

        // ringkasan
        val head = act.card(14)
        head.addView(act.label(p, 16f, Pal.text, true))
        val gf = l.gradleFile
        head.addView(
            act.mono(
                if (gf != null) gf.relativeTo(l.root).path else "build.gradle (belum ada; dibuat saat menambah library)",
                11f, Pal.primaryLight
            ), act.lp(t = 2)
        )
        head.addView(
            act.label(
                "Library diunduh dari Google Maven dan Maven Central lalu disimpan di cache. Setelah terunduh, build bisa berjalan tanpa internet.",
                12f, Pal.sub
            ), act.lp(t = 8)
        )
        body.addView(head, act.lp(b = 12))

        // peringatan dari pembaca build.gradle
        if (info.warnings.isNotEmpty()) {
            val w = act.card(12, Pal.warnBg)
            w.addView(act.label("Catatan build.gradle", 13f, Pal.warn, true))
            info.warnings.forEach { w.addView(act.label("• $it", 12f, Pal.text), act.lp(t = 4)) }
            body.addView(w, act.lp(b = 12))
        }

        // daftar dependency
        body.addView(act.label("Library proyek", 15f, Pal.text, true), act.lp(b = 6))
        val list = act.card(6)
        val all = info.boms + info.deps
        if (all.isEmpty()) {
            list.addView(
                act.label("Belum ada library. Ketuk Tambah untuk memilih AppCompat, Material, RecyclerView, dan lainnya.", 13f, Pal.sub),
                act.lp(l = 8, t = 8, r = 8, b = 8)
            )
        }
        all.forEach { d ->
            val isBom = info.boms.contains(d)
            val ok = resolver.isCached(d)
            val r = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10))
                background = act.glassPress(12, 0)
                isClickable = true
                setOnClickListener { itemMenu(d) }
            }
            r.addView(
                act.icon(if (ok) R.drawable.ic_check else R.drawable.ic_import, 18, if (ok) Pal.ok else Pal.dim),
                LinearLayout.LayoutParams(act.dp(18), act.dp(18))
            )
            val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(act.label(d.coord, 13f, Pal.text, true))
            mid.addView(
                act.label(
                    (if (isBom) "platform (BOM)" else d.config) + "  ·  " + if (ok) "ada di cache" else "belum diunduh",
                    11f, Pal.sub
                )
            )
            r.addView(mid, act.lp(0, WRAP, l = 12, weight = 1f))
            list.addView(r)
        }
        body.addView(list, act.lp(b = 12))

        // library lokal
        body.addView(act.label("Library lokal (folder ${l.libsRel})", 15f, Pal.text, true), act.lp(b = 6))
        val locals = l.libsDir.listFiles { f -> f.isFile && (f.extension == "jar" || f.extension == "aar") }?.sortedBy { it.name } ?: emptyList()
        val lc = act.card(12)
        if (locals.isEmpty()) {
            lc.addView(
                act.label("Taruh file .jar atau .aar di folder ${l.libsRel} lewat File Manager; otomatis ikut dibuild.", 12f, Pal.sub)
            )
        }
        locals.forEach { lc.addView(act.mono("${it.name}  (${ProjectStore.humanSize(it.length())})", 12f, Pal.text), act.lp(t = 2)) }
        body.addView(lc, act.lp(b = 12))

        // aksi
        val row = LinearLayout(act)
        row.addView(act.primaryButton("Tambah", R.drawable.ic_plus, 14f) { addDialog() }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Unduh semua", R.drawable.ic_download, 14f) { download() }, act.lp(0, WRAP, weight = 1f))
        body.addView(row, act.lp(b = 8))
        val row2 = LinearLayout(act)
        row2.addView(act.glassButton("Buka build.gradle", R.drawable.ic_file, 13f) { openGradle() }, act.lp(0, WRAP, r = 6, weight = 1f))
        row2.addView(act.glassButton("Hapus cache", R.drawable.ic_trash, 13f, Pal.err) { clearCache() }, act.lp(0, WRAP, weight = 1f))
        body.addView(row2, act.lp(b = 12))

        body.addView(
            act.label("Cache dependency + dex: ${ProjectStore.humanSize(act.builder.cacheBytes())}", 11f, Pal.dim),
            act.lp(b = 12)
        )
        body.addView(logCard, act.lp())
        cancelBtn.visibility = if (working) View.VISIBLE else View.GONE
    }

    // ---------- aksi ----------

    private fun addDialog() {
        act.pickItem("Tambah library", popular.map { it.first } + "Ketik manual…") { i ->
            if (i < popular.size) add(popular[i].second)
            else act.promptText("Tambah dependency", "group:artifact:versi") { add(it) }
        }
    }

    private fun add(coord: String) {
        val c = coord.trim().trim('"', '\'')
        if (!Regex("[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+(:[A-Za-z0-9_.\\-+\\[\\],()@]+)?").matches(c)) {
            act.toast("Format: group:artifact:versi")
            return
        }
        val p = act.project ?: return
        val l = act.store.layout(p)
        if (!GradleLite.addDependency(l.gradleTarget, c)) {
            act.toast("Library itu sudah ada")
            return
        }
        act.explorer.refresh()
        act.toast("Ditambahkan. Ketuk 'Unduh semua' atau langsung Build.")
        rebuild()
    }

    private fun itemMenu(d: Dep) {
        act.pickItem(d.coord, listOf("Salin koordinat", "Hapus dari proyek")) { i ->
            when (i) {
                0 -> act.copyText("dependency", d.coord)
                1 -> act.confirm("Hapus ${d.ga} dari build.gradle?") {
                    val p = act.project
                    if (p != null) {
                        GradleLite.removeDependency(act.store.layout(p).gradleTarget, d)
                        act.explorer.refresh()
                        rebuild()
                    }
                }
            }
        }
    }

    private fun openGradle() {
        val p = act.project ?: return
        val l = act.store.layout(p)
        val f = l.gradleTarget
        if (!f.isFile) {
            f.parentFile?.mkdirs()
            f.writeText(GradleLite.defaultFile(f.name.endsWith(".kts")))
            act.explorer.refresh()
        }
        act.openFile(f.relativeTo(l.root).path.replace(java.io.File.separatorChar, '/'))
    }

    private fun clearCache() {
        act.confirm("Hapus semua library yang sudah diunduh dan cache dex? Build berikutnya akan mengunduh ulang.") {
            act.builder.clearCaches()
            rebuild()
        }
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun download() {
        val p = act.project ?: return
        if (working) { act.toast("Sedang berjalan…"); return }
        if (act.building) { act.toast("Tunggu build selesai dulu"); return }
        working = true
        logView.text = ""
        status.text = "Mengunduh dependency…"
        cancelBtn.visibility = View.VISIBLE
        val dir = act.store.dir(p)
        Thread {
            var msg: String
            try {
                val libs = act.builder.resolveOnly(dir) { line -> act.runOnUiThread { appendLog(line) } }
                msg = "Selesai: ${libs.size} library siap di cache."
            } catch (e: BuildError) {
                msg = "GAGAL: ${e.message}"
            } catch (e: Exception) {
                msg = "GAGAL: ${e.message ?: e.javaClass.simpleName}"
            }
            working = false
            act.runOnUiThread {
                appendLog(msg)
                status.text = msg
                rebuild()
            }
        }.start()
    }
}
