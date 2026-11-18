package com.nexo.nexorith.lab

import java.io.File

/**
 * Letak folder penting sebuah proyek. Mendukung dua susunan:
 *  - susunan sendiri (native): AndroidManifest.xml, src/, res/ langsung di akar proyek;
 *  - susunan Gradle: <modul>/src/main/{AndroidManifest.xml, java, res, assets, jniLibs}.
 */
class ProjectLayout(
    val root: File,
    val moduleDir: File,
    val manifest: File,
    val srcDirs: List<File>,
    val resDir: File,
    val assetsDir: File,
    val jniDir: File,
    val libsDir: File,
    val gradleFile: File?,
    val gradleStyle: Boolean
) {
    private fun rel(f: File): String = f.relativeTo(root).path.replace(File.separatorChar, '/')

    /** Folder tempat kelas Java baru dibuat. */
    val javaRoot: File get() = srcDirs.first()
    val javaRootRel: String get() = rel(javaRoot)
    val resRel: String get() = rel(resDir)
    val manifestRel: String get() = rel(manifest)
    val libsRel: String get() = rel(libsDir)
    val assetsRel: String get() = rel(assetsDir)

    /** File build.gradle yang dipakai; belum tentu sudah ada. */
    val gradleTarget: File get() = gradleFile ?: File(moduleDir, "build.gradle")

    companion object {
        fun detect(root: File): ProjectLayout {
            val rootManifest = File(root, "AndroidManifest.xml")
            if (rootManifest.isFile) return native(root)

            // susunan Gradle: app/ atau modul lain
            val modules = listOf(File(root, "app")) +
                (root.listFiles { f -> f.isDirectory && f.name != "app" && f.name != "out" }?.sortedBy { it.name } ?: emptyList())
            for (m in modules) {
                if (File(m, "src/main/AndroidManifest.xml").isFile) return gradle(root, m)
            }
            if (File(root, "src/main/AndroidManifest.xml").isFile) return gradle(root, root)
            return native(root)
        }

        private fun native(root: File) = ProjectLayout(
            root, root, File(root, "AndroidManifest.xml"),
            listOf(File(root, "src")),
            File(root, "res"), File(root, "assets"), File(root, "jniLibs"), File(root, "libs"),
            gradleOf(root), false
        )

        private fun gradle(root: File, module: File): ProjectLayout {
            val main = File(module, "src/main")
            return ProjectLayout(
                root, module, File(main, "AndroidManifest.xml"),
                listOf(File(main, "java"), File(main, "kotlin")),
                File(main, "res"), File(main, "assets"), File(main, "jniLibs"), File(module, "libs"),
                gradleOf(module), true
            )
        }

        private fun gradleOf(dir: File): File? =
            listOf("build.gradle", "build.gradle.kts").map { File(dir, it) }.firstOrNull { it.isFile }
    }
}
