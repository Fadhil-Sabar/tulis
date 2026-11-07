# Tulis

Tulis adalah aplikasi catatan tulisan tangan untuk Android. Aplikasinya ringan, berjalan sepenuhnya offline, dan tidak meminta izin apa pun. Goresan digambar dengan [AndroidX Ink](https://developer.android.com/jetpack/androidx/releases/ink), sedangkan antarmukanya memakai View biasa tanpa Compose, AppCompat, atau Material.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/1.png" width="49%" alt="Beranda">
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/2.png" width="49%" alt="Editor halaman">
</p>

## Performa

Target utama Tulis adalah tinta yang muncul cepat saat stylus bergerak dan aplikasi yang tetap kecil.

| Ukuran | Nilai |
|---|---|
| Ukuran APK rilis | 5,9 MiB |
| Start dingin | sekitar 0,65 detik (median dari 5 kali ukur, rentang 0,59 sampai 0,87 detik) |
| Izin | tidak ada |

Angka di atas diukur pada build rilis di tablet Xiaomi 25040RP0AG dengan `am start -W` setelah `force-stop`.

Beberapa keputusan teknis yang membuat aplikasi ini ringan:

- Goresan digambar oleh mesin AndroidX Ink. Prediksi gerakan stylus (`MotionEventPredictor`) dipakai untuk memperkecil jeda antara ujung stylus dan tinta.
- Setiap halaman disimpan dalam satu file biner. File dimuat saat dibutuhkan (lazy loading) di luar thread UI, lalu hasil render halaman dan thumbnail disimpan di cache LRU.
- Ekspor PDF menyimpan goresan sebagai vektor, jadi hasilnya tetap tajam saat diperbesar.
- Aplikasi tidak memakai jaringan, analitik, atau layanan latar belakang.

## Fitur

- Dua mode per notebook: kanvas tak hingga, atau halaman yang digulir vertikal (potret atau lanskap) dengan peta mini
- Pena, spidol, stabilo, dan penghapus dengan ketebalan yang bisa diatur
- Urungkan dan ulangi yang berlaku lintas halaman
- Gaya kertas polos, bergaris, kotak-kotak, dan titik
- Folder bersarang dan tempat sampah
- Ekspor PDF (vektor) dan PNG
- Cadangan ke folder Obsidian vault lewat Storage Access Framework (SAF), meliputi arsip `.tulis`, publikasi catatan sebagai `.md` dan PNG, pemulihan notebook, dan penyelesaian konflik
- Tata letak untuk ponsel dan tablet

## Unduh

APK bertanda tangan tersedia di halaman [Releases](https://github.com/Fadhil-Sabar/tulis/releases), lengkap dengan nilai SHA-256 untuk tiap APK. Tulis membutuhkan Android 8.0 (API 26) atau lebih baru.

## Privasi

- Tulis tidak meminta izin apa pun, termasuk akses internet, dan tidak mengirim data analitik.
- Catatan disimpan di penyimpanan internal aplikasi. Folder vault hanya diakses setelah Anda memilihnya sendiri.
- Auto Backup Android aktif. Jika cadangan Google di perangkat Anda menyala, folder catatan ikut dicadangkan, kecuali isi tempat sampah.

## Build

Build membutuhkan JDK 17 dan Android SDK (`ANDROID_HOME`).

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew installDebug
```

Untuk APK rilis bertanda tangan, siapkan `keystore.properties` lokal atau variabel lingkungan `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, dan `KEY_PASSWORD`. Jika keduanya tidak ada, `assembleRelease` tetap berjalan dan menghasilkan APK tanpa tanda tangan.

## Berkontribusi

Issue dan pull request terbuka untuk siapa saja. Sebelum mengubah kode, baca [AGENTS.md](AGENTS.md) yang berisi struktur kode, jebakan yang sudah diketahui, dan konvensi commit. Perubahan yang terlihat oleh pengguna dicatat di [CHANGELOG.md](CHANGELOG.md). Kerentanan keamanan bisa dilaporkan lewat fitur "Report a vulnerability" di tab Security repo ini.

## Lisensi

Kode dirilis dengan lisensi [Apache-2.0](LICENSE). Font Caveat dan Patrick Hand memakai lisensi SIL OFL 1.1, detailnya ada di [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## English summary

Tulis is a small, fully offline handwriting notes app for Android. Strokes are drawn with AndroidX Ink and the UI uses plain Views. The release APK is 5.9 MiB and cold-starts in about 0.65 s on the test tablet. You can choose between an infinite canvas and vertically scrolling pages, organize notebooks in nested folders, export to PDF or PNG, and back up to an Obsidian vault through the Storage Access Framework. The app requests no permissions, and the UI is in Indonesian.

Signed APKs are on the [Releases](https://github.com/Fadhil-Sabar/tulis/releases) page. To build, use JDK 17 and run `./gradlew assembleDebug`. Licensed under Apache-2.0.
