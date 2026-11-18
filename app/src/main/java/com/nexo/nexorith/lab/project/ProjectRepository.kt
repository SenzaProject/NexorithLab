package com.nexo.nexorith.lab.project

import android.content.Context
import com.nexo.nexorith.lab.build.GradleLite
import java.io.File

class ProjectRepository(context: Context) {

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "projects")
        .also { it.mkdirs() }

    fun list(): List<File> =
        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun delete(dir: File): Boolean = dir.deleteRecursively()

    /** Membuat proyek Java kosong dengan satu Activity. */
    fun create(name: String, packageName: String): Result<File> = runCatching {
        require(NAME_REGEX.matches(name)) {
            "Nama proyek: huruf, angka, _ atau -, diawali huruf"
        }
        require(GradleLite.isValidApplicationId(packageName)) {
            "Package tidak valid (contoh: com.contoh.hello)"
        }
        val dir = File(root, name)
        require(!dir.exists()) { "Proyek $name sudah ada" }

        val pkgPath = packageName.replace('.', '/')
        write(File(dir, "build.gradle"), """
            android {
                applicationId "$packageName"
                minSdk 24
                targetSdk 34
                compileSdk 34
                versionCode 1
                versionName "1.0"
            }

            dependencies {
            }
        """.trimIndent())
        write(File(dir, "src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName">
                <application
                    android:label="@string/app_name"
                    android:theme="@android:style/Theme.Material.Light.DarkActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())
        write(File(dir, "src/main/java/$pkgPath/MainActivity.java"), """
            package $packageName;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                }
            }
        """.trimIndent())
        write(File(dir, "src/main/res/layout/activity_main.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name" />
            </LinearLayout>
        """.trimIndent())
        write(File(dir, "src/main/res/values/strings.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">$name</string>
            </resources>
        """.trimIndent())
        dir
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text + "\n")
    }

    companion object {
        private val NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}\$")
    }
}
