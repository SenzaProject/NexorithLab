package com.nexo.nexorith.lab

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Code Editor: tab file, nomor baris, syntax highlight, undo/redo, cari & ganti, dan tombol Run. */
class EditorScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    var pendingLine: Int? = null

    private var loadedPath: String? = null
    private var loadedProject: String? = null
    private var loading = false
    private var dirty = false

    private val history = ArrayList<String>()
    private var histIdx = -1

    private lateinit var title: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var editor: CodeEditText
    private lateinit var langLabel: TextView

    private val hlRunnable = Runnable { highlight(); snapshot() }
    private val saveRunnable = Runnable { if (autoSave()) saveNow() }

    override val view: View = buildView()

    private fun autoSave() = act.prefs.getBoolean("auto_save", true)

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title = act.label("", 18f, Pal.text, true).apply { setSingleLine() }
        val top = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(6), act.dp(8), act.dp(6), act.dp(8))
            addView(act.iconButton(R.drawable.ic_back, 24, Pal.text) { act.back() })
            addView(title, act.lp(0, WRAP, weight = 1f))
            addView(act.iconButton(R.drawable.ic_more, 22, Pal.text) { menu() })
        }
        col.addView(top, act.lp())

        tabRow = LinearLayout(act).apply { setPadding(act.dp(10), act.dp(2), act.dp(10), act.dp(8)) }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, act.lp())

        editor = CodeEditText(act).apply {
            setBackgroundColor(Pal.editorBg)
            setTextColor(Pal.editorFg)
            setHintTextColor(Pal.dim)
            hint = "Ketik kode di sini…"
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
            highlightColor = 0x552F6BFF
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply { setColor(Color.WHITE); setSize(act.dp(2), -1) }
            }
            onChanged {
                if (!loading) {
                    if (!dirty) { dirty = true; updateTitle() }
                    act.handler.removeCallbacks(hlRunnable)
                    act.handler.postDelayed(hlRunnable, 400)
                    act.handler.removeCallbacks(saveRunnable)
                    act.handler.postDelayed(saveRunnable, 1500)
                }
            }
        }
        col.addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(act.dp(8), 0, act.dp(8), 0)
        })

        // baris simbol
        val symRow = LinearLayout(act).apply { setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6)) }
        listOf(
            "Tab" to "    ", "{ }" to "{}", "( )" to "()", "[ ]" to "[]", "\" \"" to "\"\"",
            ";" to ";", "." to ".", "," to ",", "=" to "=", "<" to "<", ">" to ">", "/" to "/",
            "!" to "!", "+" to "+", "-" to "-", "*" to "*", "&" to "&", "|" to "|", "_" to "_", "@" to "@"
        ).forEach { (lab, ins) ->
            val backBy = if (ins.length == 2 && lab.contains(' ')) 1 else 0
            symRow.addView(act.label(lab, 15f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minWidth = act.dp(40)
                setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
                background = act.glassPress(10, Pal.glassStrong)
                isClickable = true
                setOnClickListener { insert(ins, backBy) }
            }, act.lp(WRAP, WRAP, r = 6))
        }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0x66050B2C)
            addView(symRow)
        }, act.lp())

        // bilah bawah: bahasa, undo, redo, cari, simpan, run
        val bottom = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.navBg)
            setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
        }
        langLabel = act.label("Java", 13f, Pal.text, true).apply {
            background = act.glass(18, Pal.glassStrong)
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
        }
        bottom.addView(langLabel)
        bottom.addView(View(act), act.lp(0, 1, weight = 1f))
        bottom.addView(act.iconButton(R.drawable.ic_undo, 22, Pal.primaryLight) { undo() })
        bottom.addView(act.iconButton(R.drawable.ic_redo, 22, Pal.primaryLight) { redo() })
        bottom.addView(act.iconButton(R.drawable.ic_search, 22, Pal.primaryLight) { findDialog() })
        bottom.addView(act.iconButton(R.drawable.ic_save, 22, Pal.primaryLight) { saveNow(); act.toast("Tersimpan ✓") })
        bottom.addView(
            act.primaryButton("Run", R.drawable.ic_play, 14f) { runBuild() },
            act.lp(WRAP, WRAP, l = 8)
        )
        col.addView(bottom, act.lp())
        return col
    }

    // =====================================================================

    override fun onShow() {
        val p = act.project
        val rel = act.currentFile
        applyPrefs()
        if (p == null || rel == null) {
            clearLoaded()
            title.text = "Editor"
            refreshTabs()
            return
        }
        val f = File(act.store.dir(p), rel)
        if (!f.isFile) { clearLoaded(); act.currentFile = null; refreshTabs(); return }
        if (loadedPath == rel && loadedProject == p) {
            // muat ulang jika isi file di disk berubah (mis. diubah dari layar Build)
            val disk = f.readText()
            if (!dirty && disk != editor.text.toString()) setText(disk, resetHistory = false)
        } else {
            saveNow()
            loadedPath = rel
            loadedProject = p
            dirty = false
            setText(f.readText(), resetHistory = true)
            editor.scrollTo(0, 0)
        }
        langLabel.text = when (Highlighter.modeFor(f.name)) {
            Highlighter.JAVA -> "Java"
            Highlighter.XML -> "XML"
            else -> "Teks"
        }
        updateTitle()
        refreshTabs()
        pendingLine?.let { goToLine(it) }
        pendingLine = null
    }

    override fun onHide() {
        saveIfAuto()
        act.handler.removeCallbacks(hlRunnable)
        act.handler.removeCallbacks(saveRunnable)
    }

    private fun applyPrefs() {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, act.prefs.getInt("font_size", 13).toFloat())
        editor.showLineNumbers = act.prefs.getBoolean("line_numbers", true)
    }

    private fun updateTitle() {
        val name = act.currentFile?.substringAfterLast('/') ?: "Editor"
        title.text = if (dirty) "$name •" else name
    }

    fun clearLoaded() {
        loadedPath = null
        loadedProject = null
        dirty = false
        if (::editor.isInitialized) setText("", resetHistory = true)
    }

    fun refreshTabs() {
        if (!::tabRow.isInitialized) return
        tabRow.removeAllViews()
        act.tabs.forEach { rel ->
            val sel = rel == act.currentFile
            val tab = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(14), act.dp(7), act.dp(6), act.dp(7))
                background = if (sel) act.glass(18, 0x552F6BFF, Pal.primary) else act.glassPress(18)
                isClickable = true
                setOnClickListener { if (!sel) switchTo(rel) }
            }
            tab.addView(act.label(rel.substringAfterLast('/'), 13f, if (sel) Color.WHITE else Pal.sub, sel))
            tab.addView(act.iconButton(R.drawable.ic_close, 14, if (sel) Color.WHITE else Pal.sub) { act.closeTab(rel) }.apply {
                setPadding(act.dp(6), act.dp(2), act.dp(6), act.dp(2))
                layoutParams = LinearLayout.LayoutParams(act.dp(26), act.dp(20))
            })
            tabRow.addView(tab, act.lp(WRAP, WRAP, r = 8))
        }
    }

    private fun switchTo(rel: String) {
        saveNow()
        act.currentFile = rel
        onShow()
    }

    // =====================================================================
    //  Teks, simpan, undo/redo
    // =====================================================================

    private fun setText(s: String, resetHistory: Boolean) {
        loading = true
        editor.setText(s)
        loading = false
        highlight()
        if (resetHistory) {
            history.clear()
            history.add(s)
            histIdx = 0
        }
    }

    private fun highlight() {
        Highlighter.apply(editor.text, Highlighter.modeFor(act.currentFile ?: ""))
    }

    fun saveNow() {
        val p = loadedProject ?: return
        val rel = loadedPath ?: return
        val f = File(act.store.dir(p), rel)
        if (f.exists() && dirty) f.writeText(editor.text.toString())
        dirty = false
        if (::title.isInitialized) updateTitle()
    }

    fun saveIfAuto() { if (autoSave()) saveNow() }

    private fun snapshot() {
        val t = editor.text.toString()
        if (histIdx >= 0 && history.getOrNull(histIdx) == t) return
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        history.add(t)
        if (history.size > 100) history.removeAt(0)
        histIdx = history.size - 1
    }

    private fun undo() {
        act.handler.removeCallbacks(hlRunnable)
        snapshot()
        if (histIdx <= 0) return act.toast("Tidak ada yang bisa di-undo")
        histIdx--
        restore(history[histIdx])
    }

    private fun redo() {
        if (histIdx >= history.size - 1) return act.toast("Tidak ada yang bisa di-redo")
        histIdx++
        restore(history[histIdx])
    }

    private fun restore(s: String) {
        val cur = editor.selectionStart.coerceAtLeast(0)
        loading = true
        editor.setText(s)
        loading = false
        editor.setSelection(cur.coerceAtMost(s.length))
        highlight()
        dirty = true
        updateTitle()
        act.handler.removeCallbacks(saveRunnable)
        act.handler.postDelayed(saveRunnable, 1500)
    }

    private fun insert(s: String, back: Int) {
        val st = editor.selectionStart.coerceAtLeast(0)
        val en = editor.selectionEnd.coerceAtLeast(0)
        val a = minOf(st, en)
        editor.text.replace(a, maxOf(st, en), s)
        editor.setSelection(a + s.length - back)
    }

    fun goToLine(line: Int) {
        val text = editor.text.toString()
        var idx = 0
        repeat((line - 1).coerceAtLeast(0)) {
            val n = text.indexOf('\n', idx)
            if (n >= 0) idx = n + 1
        }
        editor.requestFocus()
        editor.setSelection(idx.coerceAtMost(text.length))
        editor.bringPointIntoView(idx.coerceAtMost(text.length))
    }

    // =====================================================================
    //  Menu, cari & ganti, run
    // =====================================================================

    private fun menu() {
        act.pickItem(
            act.currentFile?.substringAfterLast('/'),
            listOf("Simpan sekarang", "Cari & ganti", "Ke baris…", "UI Preview layout ini", "Tutup tab ini", "Kembali ke proyek")
        ) { i ->
            when (i) {
                0 -> { saveNow(); act.toast("Tersimpan ✓") }
                1 -> findDialog()
                2 -> act.promptText("Ke baris", "Nomor baris") { n -> n.toIntOrNull()?.let { goToLine(it) } }
                3 -> {
                    val rel = act.currentFile
                    if (rel != null && Regex("(^|/)res/layout[^/]*/[^/]+\\.xml$").containsMatchIn(rel)) {
                        saveNow()
                        act.preview.select(rel)
                        act.navigate(act.preview)
                    } else act.toast("Buka file layout (res/layout/*.xml) untuk preview")
                }
                4 -> act.currentFile?.let { act.closeTab(it) }
                5 -> act.back()
            }
        }
    }

    private fun findDialog() {
        val q = act.textField("Cari…")
        val r = act.textField("Ganti dengan…")
        val info = act.label("", 12f, Pal.sub)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(20), act.dp(10), act.dp(20), 0)
        }
        box.addView(q, act.lp(b = 8))
        box.addView(r, act.lp(b = 8))
        box.addView(info, act.lp(b = 8))
        val row = LinearLayout(act)
        box.addView(row, act.lp())

        fun findNext() {
            val needle = q.text.toString()
            if (needle.isEmpty()) return
            val text = editor.text.toString()
            val total = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).count()
            var idx = text.indexOf(needle, editor.selectionEnd.coerceAtLeast(0), ignoreCase = true)
            if (idx < 0) idx = text.indexOf(needle, 0, ignoreCase = true)
            if (idx < 0) { info.text = "Tidak ditemukan"; return }
            editor.setSelection(idx, idx + needle.length)
            editor.bringPointIntoView(idx)
            info.text = "$total kecocokan"
        }
        fun replaceAll() {
            val needle = q.text.toString()
            if (needle.isEmpty()) return
            val text = editor.text.toString()
            val count = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).count()
            if (count == 0) { info.text = "Tidak ditemukan"; return }
            val out = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)
                .replace(text, Regex.escapeReplacement(r.text.toString()))
            editor.setText(out)
            info.text = "$count diganti"
        }
        row.addView(act.glassButton("Cari", R.drawable.ic_search) { findNext() }, act.lp(0, WRAP, r = 8, weight = 1f))
        row.addView(act.glassButton("Ganti semua", R.drawable.ic_findreplace) { replaceAll() }, act.lp(0, WRAP, weight = 1f))
        AlertDialog.Builder(act).setTitle("Cari & ganti").setView(box).setPositiveButton("Tutup", null).show()
    }

    private fun runBuild() {
        saveNow()
        act.navigate(act.build)
        act.build.startFromUi()
    }
}
