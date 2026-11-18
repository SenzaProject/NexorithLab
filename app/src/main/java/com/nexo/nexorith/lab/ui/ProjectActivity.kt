package com.nexo.nexorith.lab.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nexo.nexorith.lab.build.BuildService
import com.nexo.nexorith.lab.build.BuildState
import java.io.File

class ProjectActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var dir: File
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var fileLabel: TextView
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    private var files: List<File> = emptyList()
    private var current: File? = null
    private var dirty = false
    private var loading = false

    private val stateListener: (BuildState.Snapshot) -> Unit = { s -> runOnUiThread { renderStatus(s) } }
    private val logListener: (String) -> Unit = { line -> runOnUiThread { appendLog(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        dir = File(path)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(12), dp(16), dp(12), dp(8))
        }
        root.addView(TextView(this).apply {
            text = dir.name
            textSize = 18f
            setTextColor(Palette.TEXT)
        })
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(status)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun barButton(label: String, action: () -> Unit) {
            bar.addView(
                actionButton(label, action),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
            )
        }
        barButton("Simpan") { saveNow() }
        barButton("File baru") { showNewFileDialog() }
        barButton("Build") { startBuild() }
        barButton("Batal") { cancelBuild() }
        root.addView(bar)

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 13f
                return v
            }
        }
        val fileList = ListView(this)
        fileList.adapter = adapter
        fileList.setOnItemClickListener { _, _, pos, _ -> openFile(files[pos]) }
        root.addView(fileList, LinearLayout.LayoutParams(MATCH, dp(130)).apply { topMargin = dp(8) })

        fileLabel = TextView(this).apply {
            text = "Pilih file untuk diedit"
            textSize = 12f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(fileLabel)

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            setTextColor(Palette.TEXT)
            setBackgroundColor(Palette.PANEL)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            isEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!loading) dirty = true
                }
            })
        }
        root.addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f))

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Palette.MUTED)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Palette.PANEL)
            addView(log)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(130)).apply { topMargin = dp(8) })

        setContentView(root)
        refreshFiles()
    }

    override fun onStart() {
        super.onStart()
        if (!::dir.isInitialized) return
        BuildState.addListener(stateListener)
        BuildState.addLogListener(logListener)
        log.text = BuildState.logs().joinToString("\n")
        renderStatus(BuildState.snapshot)
    }

    override fun onStop() {
        if (::dir.isInitialized) {
            BuildState.removeListener(stateListener)
            BuildState.removeLogListener(logListener)
        }
        super.onStop()
    }

    override fun onPause() {
        if (::dir.isInitialized) saveCurrent()
        super.onPause()
    }

    private fun refreshFiles() {
        files = dir.walkTopDown()
            .onEnter { it == dir || (it.name != "build" && it.name != ".git") }
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).path }
            .toList()
        adapter.clear()
        adapter.addAll(files.map { it.relativeTo(dir).path })
    }

    private fun openFile(file: File) {
        saveCurrent()
        if (file.length() > 1_000_000) {
            toast("File terlalu besar untuk editor (>1 MB)")
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            toast("Gagal membaca: ${e.message}")
            return
        }
        if (text.contains('\u0000')) {
            toast("File biner tidak bisa diedit")
            return
        }
        loading = true
        editor.setText(text)
        loading = false
        editor.isEnabled = true
        current = file
        dirty = false
        fileLabel.text = file.relativeTo(dir).path
    }

    private fun saveCurrent() {
        val file = current ?: return
        if (!dirty) return
        try {
            file.writeText(editor.text.toString())
            dirty = false
        } catch (e: Exception) {
            toast("Gagal menyimpan: ${e.message}")
        }
    }

    private fun saveNow() {
        val file = current
        if (file == null) {
            toast("Belum ada file yang dibuka")
            return
        }
        dirty = true
        saveCurrent()
        if (!dirty) toast("Tersimpan: ${file.name}")
    }

    private fun showNewFileDialog() {
        val input = EditText(this).apply {
            hint = "mis. src/main/java/com/contoh/Util.java"
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(this)
            .setTitle("File baru")
            .setView(box)
            .setPositiveButton("Buat") { _, _ -> createFile(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun createFile(relative: String) {
        if (relative.isEmpty()) return
        val target = File(dir, relative).canonicalFile
        if (!target.path.startsWith(dir.canonicalPath + File.separator)) {
            toast("Path tidak valid")
            return
        }
        if (target.exists()) {
            toast("File sudah ada")
            return
        }
        try {
            target.parentFile?.mkdirs()
            target.createNewFile()
        } catch (e: Exception) {
            toast("Gagal membuat file: ${e.message}")
            return
        }
        refreshFiles()
        openFile(target)
    }

    private fun startBuild() {
        saveCurrent()
        if (BuildState.snapshot.running) {
            toast("Build sedang berjalan")
            return
        }
        val intent = Intent(this, BuildService::class.java)
            .setAction(BuildService.ACTION_START)
            .putExtra(BuildService.EXTRA_PROJECT, dir.absolutePath)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun cancelBuild() {
        if (!BuildState.snapshot.running) {
            toast("Tidak ada build yang berjalan")
            return
        }
        startService(Intent(this, BuildService::class.java).setAction(BuildService.ACTION_CANCEL))
    }

    private fun renderStatus(s: BuildState.Snapshot) {
        if (s.running && s.stageIndex == 0) log.text = ""
        status.text = if (s.running) {
            "Membangun ${s.projectName}: tahap ${s.stageIndex}/${s.stageCount} - ${s.stageName ?: "memulai"}"
        } else {
            s.lastResult ?: "Siap"
        }
    }

    private fun appendLog(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
