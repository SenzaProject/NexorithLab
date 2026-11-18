# Nexorith Lab
IDE Android di HP: tulis kode Java/XML, preview UI, tambah library Maven, build, tanda tangan, dan install, semuanya di perangkat.
Dibuat oleh **SenzaProject**. Package: `com.nexo.nexorith.lab`.

## Fase 1 (versi 1.1.0)
- **Dependency Maven**: tulis `implementation 'group:artifact:versi'` di `build.gradle` (atau layar Dependencies). Library diunduh dari Google Maven dan Maven Central, dependency turunan dan konflik versi diselesaikan, lalu di-cache.
- **Manifest merger**: izin, activity, service, provider, dan meta-data dari AAR digabung ke manifest aplikasi.
- **Resource banyak sumber**: resource AAR ikut dikompilasi (di-cache per library), kelas `R` library dibuat otomatis, `assets/`, `res/raw`, `jniLibs/`, dan `libs/*.jar|aar` didukung.
- **Build bertahap**: langkah resource, Java, dan dex dilewati bila masukannya tidak berubah; dex library di-cache.
- **Multi-dex + desugaring** lewat D8. APK dirapikan 4 byte (aman untuk target Android 11+).
- **Foreground service**: build tetap jalan saat aplikasi ditinggal, dengan notifikasi progres dan tombol Batalkan.
- **Gradle-lite**: membaca `applicationId`, `namespace`, `minSdk`, `targetSdk`, dan dependencies dari `build.gradle` / `.kts`. Zip proyek Gradle standar (`app/src/main/...`) bisa diimpor.

## Batasan (jujur)
- Hanya **Java**. File `.kt` ditolak dengan pesan jelas. Annotation processing (Room, Hilt, Glide compiler) belum ada.
- Ini **bukan Gradle sungguhan**: plugin, task, flavor, dan modul ganda tidak dijalankan. `project(':x')` dan version catalog (`libs.xxx`) diabaikan dengan peringatan.
- Perlu internet saat pertama kali memakai sebuah library; setelah itu bisa offline.
- Library AndroidX butuh tema AppCompat/Material di manifest, dan Activity yang memakai `AppCompatActivity`.
- UI Preview hanya perkiraan (ConstraintLayout ditampilkan seperti FrameLayout, RecyclerView sebagai kotak kosong).
- Build butuh `libaapt2.so` (aapt2 ARM64 yang berdiri sendiri) di `app/src/main/jniLibs/arm64-v8a/`.
- Release memakai keystore bawaan yang sama dengan Debug.

## Push ke GitHub dari Termux
    cd ~/NexorithLab
    git init
    git add .
    git commit -m "Nexorith Lab 1.1.0 (Fase 1)"
    git branch -M main
    read -s -p "Token: " T; echo
    git push https://SenzaProject:$T@github.com/SenzaProject/NexorithLab.git main

Token GitHub perlu izin `repo` dan `workflow`. Setelah push, build berjalan di tab Actions; unduh artifact `NexorithLab-debug`.

## Alur build di dalam aplikasi (8 langkah)
konfigurasi > dependency > manifest > aapt2 compile > aapt2 link > ECJ > D8 > rakit + tanda tangan

## Struktur kode (`com.nexo.nexorith.lab`)
`MainActivity` (navigasi, drawer) · `*Screen.kt` (tiap layar) · `LabBuilder` (pipeline) · `DependencyResolver` ·
`ManifestMerger` · `GradleLite` · `ProjectLayout` · `BuildManager` + `BuildService` (build di latar) ·
`ProjectStore` (proyek, zip) · `LayoutPreview` (renderer XML) · `Theme` (tema Liquid Glass)
