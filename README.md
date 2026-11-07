# Tulis

Aplikasi handwriting untuk Android, offline dan tidak minta izin apa pun, termasuk internet. Tinta digambar pakai [AndroidX Ink](https://developer.android.com/jetpack/androidx/releases/ink), antarmukanya View biasa, tidak memakai Compose, AppCompat, atau Material supaya APK tetap kecil.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/1.png" width="49%" alt="Beranda">
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/2.png" width="49%" alt="Editor halaman">
</p>

## Performa

Diukur dari build rilis di tablet Xiaomi 25040RP0AG:

| | |
|---|---|
| Ukuran APK | 5,9 MiB |
| Cold start | ±0,65 detik |
| Izin | 0 |

Angka cold start adalah median dari 5 kali `am start -W` setelah `force-stop`. Paling cepat 0,59 detik, paling lambat 0,87 detik.

Supaya tinta tidak telat mengikuti ujung stylus, goresan digambar oleh mesin AndroidX Ink dan posisinya diprediksi dengan `MotionEventPredictor`. Tiap halaman disimpan sebagai satu file biner yang baru dimuat saat dibutuhkan, di luar thread UI. Hasil render halaman dan thumbnail disimpan di cache LRU. Di dalam aplikasi juga tidak ada kode jaringan, analitik, atau layanan latar belakang.

## Fitur

- Notebook bisa berupa kanvas tak hingga atau halaman yang digulir ke bawah (potret atau lanskap), lengkap dengan peta mini
- Pena, spidol, stabilo, dan penghapus. Ketebalannya bisa diatur, dan urungkan/ulangi tetap jalan walaupun pindah halaman
- Kertas polos, bergaris, kotak-kotak, atau titik
- Folder di dalam folder, plus tempat sampah
- Ekspor ke PDF dan PNG. PDF-nya berisi vektor, jadi tetap tajam saat di-zoom
- Backup ke folder Obsidian vault lewat SAF. Notebook disimpan sebagai arsip `.tulis`, catatan bisa dipublikasikan sebagai `.md` dan PNG, dan isinya bisa dipulihkan lagi. Kalau versi di HP dan di vault bentrok, kamu bisa pilih pakai yang lokal, yang di vault, atau simpan keduanya
- Tampilan untuk ponsel dan tablet

## Unduh

APK yang sudah ditandatangani ada di [Releases](https://github.com/Fadhil-Sabar/tulis/releases), masing-masing dengan nilai SHA-256. Minimal Android 8.0 (API 26).

## Privasi

Tulis tidak minta izin apa pun dan tidak ada analitik. Catatan tersimpan di penyimpanan internal aplikasi. Folder vault baru dibuka kalau kamu sendiri yang memilihnya.

Tulis sendiri tidak punya fitur backup ke cloud. Aplikasinya hanya mengizinkan Auto Backup Android (`allowBackup`), jadi folder catatan bisa ikut ter-backup oleh layanan backup bawaan HP dan ikut pindah saat transfer data ke HP baru. Isi tempat sampah tidak ikut. Kalau HP kamu tidak punya layanan backup atau fiturnya mati, catatan hanya ada di HP dan di vault yang kamu pilih.

## Build

Butuh JDK 17 dan Android SDK (`ANDROID_HOME`).

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew installDebug
```

Untuk APK rilis yang ditandatangani, isi `keystore.properties` atau set env `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, dan `KEY_PASSWORD`. Kalau tidak ada, `assembleRelease` tetap jalan, hanya saja APK-nya tidak ditandatangani.

## Berkontribusi

Silakan buka issue atau pull request. Struktur kode, jebakan yang sudah ketahuan, dan aturan commit ada di [AGENTS.md](AGENTS.md). Perubahan yang terasa ke pengguna dicatat di [CHANGELOG.md](CHANGELOG.md). Kalau menemukan celah keamanan, laporkan lewat "Report a vulnerability" di tab Security.

## Lisensi

[Apache-2.0](LICENSE). Font Caveat dan Patrick Hand pakai SIL OFL 1.1, lihat [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## English summary

Tulis is an offline handwriting notes app for Android. Strokes are drawn with AndroidX Ink, the UI is plain Views, and it asks for no permissions at all. The release APK is 5.9 MiB and cold-starts in about 0.65 s on my test tablet. A notebook can be an infinite canvas or a stack of scrolling pages. You can export to PDF or PNG, or back up to an Obsidian vault through SAF. The UI is Indonesian only.

Grab a signed APK from [Releases](https://github.com/Fadhil-Sabar/tulis/releases), or build it with JDK 17 and `./gradlew assembleDebug`. Apache-2.0.
