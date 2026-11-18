package com.nexo.nexorith.lab.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import com.nexo.nexorith.lab.project.AssetImporter
import com.nexo.nexorith.lab.project.IconGenerator
import java.io.File

class ProjectActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_ASSET = 101
        private const val REQ_ICON = 102
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
    private var pendingCategory: AssetImporter.Category? = null
    private var pendingBackground = "#FFFFFF"

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

        val bar2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bar2Params = { LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(2), dp(4), dp(2), 0) } }
        bar2.addView(actionButton("Impor aset") { chooseAssetCategory() }, bar2Params())
        bar2.addView(actionButton("Buat ikon") { askIconBackground() }, bar2Params())
        root.addView(bar2)

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

    private fun chooseAssetCategory() {
        val categories = AssetImporter.Category.values()
        AlertDialog.Builder(this)
            .setTitle("Impor sebagai")
            .setItems(categories.map { it.label }.toTypedArray()) { _, i ->
                pendingCategory = categories[i]
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(intent, REQ_ASSET)
            }
            .show()
    }

    private fun askIconBackground() {
        val input = EditText(this).apply {
            hint = "#RRGGBB"
            setText(pendingBackground)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(this)
            .setTitle("Warna latar ikon")
            .setMessage("Dipakai sebagai latar adaptive icon dan ikon lama.")
            .setView(box)
            .setPositiveButton("Pilih gambar") { _, _ ->
                val hex = input.text.toString().trim()
                if (!IconGenerator.isValidColor(hex)) {
                    toast("Warna harus berformat #RRGGBB")
                } else {
                    pendingBackground = hex
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*")
                    startActivityForResult(intent, REQ_ICON)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_ASSET -> importAssets(data)
            REQ_ICON -> data.data?.let { generateIcons(it) }
        }
    }

    private fun importAssets(data: Intent) {
        val category = pendingCategory
        if (category == null) {
            toast("Pilih kategori impor lagi")
            return
        }
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        Thread {
            val results = uris.distinct().map { uri ->
                AssetImporter.import(contentResolver, uri, displayName(uri), dir, category)
            }
            runOnUiThread {
                refreshFiles()
                val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
                toast("${results.count { it.isSuccess }} file diimpor" + (failure?.let { ", gagal: $it" } ?: ""))
            }
        }.start()
    }

    private fun generateIcons(uri: Uri) {
        val background = pendingBackground
        Thread {
            val result = runCatching {
                val bitmap = decodeScaled(uri) ?: error("Gambar tidak bisa dibaca")
                IconGenerator.generate(bitmap, dir, background)
            }
            runOnUiThread {
                refreshFiles()
                result
                    .onSuccess { toast("Ikon dibuat di res/mipmap-*") }
                    .onFailure { toast(it.message ?: "Gagal membuat ikon") }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrEmpty()) return name
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
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
