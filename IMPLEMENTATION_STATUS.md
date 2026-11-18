# Status Implementasi Nexorith Lab

Status: NOT STARTED, IN PROGRESS, IMPLEMENTED, TESTING, PASSED, BLOCKED, FAILED.
IMPLEMENTED belum berarti PASSED. Belum ada fitur PASSED karena belum diuji di HP.

## Fase 1: Fondasi build

| No | Fitur | Status | Catatan |
|----|-------|--------|---------|
| 4 | Build di foreground service | IMPLEMENTED | Notifikasi progres + tombol Batal. Belum diuji di HP |
| 8 | Gradle-lite parser | IMPLEMENTED | Unit test ada, dijalankan di GitHub Actions |
| 7 | Struktur folder proyek | IN PROGRESS | Template Java kosong, explorer + editor sederhana |
| 6 | Impor aset + pembuat ikon | IMPLEMENTED | Gambar/font/audio/assets lewat file picker; ikon mipmap + adaptive dari satu gambar. Belum diuji di HP |
| 1 | Resolver Maven + AAR | NOT STARTED | |
| 2 | Manifest merger | NOT STARTED | |
| 3 | Resource banyak sumber + aapt2 | NOT STARTED | Butuh biner aapt2 untuk Android |
| 5 | Build bertahap + cache | NOT STARTED | |
| 6 | Multi-dex + desugaring (D8) | NOT STARTED | |

Pipeline build saat ini: Muat proyek, Baca konfigurasi, lalu berhenti di tahap
pertama yang belum ada dan melaporkannya apa adanya.
