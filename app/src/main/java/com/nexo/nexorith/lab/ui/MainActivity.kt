package com.nexo.nexorith.lab.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.nexo.nexorith.lab.project.ProjectRepository
import java.io.File

class MainActivity : Activity() {

    private lateinit var repo: ProjectRepository
    private lateinit var adapter: ArrayAdapter<String>
    private var projects: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProjectRepository(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Nexorith Lab"
            textSize = 24f
            setTextColor(Palette.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Lokasi proyek:\n${repo.root.absolutePath}"
            textSize = 11f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(4), 0, dp(12))
        })
        root.addView(actionButton("Proyek baru") { showCreateDialog() })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openProject(projects[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(projects[pos])
            true
        }
        val empty = TextView(this).apply {
            text = "Belum ada proyek. Ketuk \"Proyek baru\"."
            setTextColor(Palette.MUTED)
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        list.emptyView = empty
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        projects = repo.list()
        adapter.clear()
        adapter.addAll(projects.map { it.name })
    }

    private fun openProject(dir: File) {
        startActivity(Intent(this, ProjectActivity::class.java).putExtra(ProjectActivity.EXTRA_PATH, dir.absolutePath))
    }

    private fun showCreateDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = "Nama proyek (mis. HelloApp)"
            setSingleLine()
        }
        val pkg = EditText(this).apply {
            hint = "Package (mis. com.contoh.hello)"
            setText("com.contoh.")
            setSingleLine()
        }
        box.addView(name)
        box.addView(pkg)
        AlertDialog.Builder(this)
            .setTitle("Proyek baru (Java kosong)")
            .setView(box)
            .setPositiveButton("Buat") { _, _ ->
                repo.create(name.text.toString().trim(), pkg.text.toString().trim())
                    .onSuccess {
                        refresh()
                        openProject(it)
                    }
                    .onFailure { toast(it.message ?: "Gagal membuat proyek") }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Hapus ${dir.name}?")
            .setMessage("Seluruh isi proyek akan dihapus permanen.")
            .setPositiveButton("Hapus") { _, _ ->
                if (!repo.delete(dir)) toast("Sebagian file gagal dihapus")
                refresh()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
