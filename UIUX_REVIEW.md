# Ulasan UI/UX Tulis (advisor, 4 Nov 2026)

Dasar: screenshot perangkat (Beranda, sheet baru, editor infinite/halaman tablet), design-ref/*.dc.html, dan kode. Belum diuji dengan stylus asli.

## Prioritas tinggi
- **T1 Palm rejection & stylus.** `fingerDraws` default aktif dan tidak disimpan; tool type hanya dari pointer 0 (`NoteCanvasView.kt:402-403`); telapak lalu stylus memicu `switchToPinch` (`:428-431`); `FLAG_CANCELED` tidak diperiksa. Saran: nonaktifkan "jari menggambar" otomatis saat stylus terdeteksi (simpan di SharedPreferences), cek tool type di `ACTION_POINTER_DOWN`, batalkan goresan pada `FLAG_CANCELED`, opsional tolak `touchMajor` > ~40dp. Usaha: sedang.
- **T2 Halaman tablet terpotong.** `fitPage()` fit-lebar (`NoteCanvasView.kt:239-246`); tablet hanya ±57% halaman terlihat, tanpa margin atas, tanpa indikator "n / total". Saran: di tablet fit-halaman `min((w-32dp)/pageW,(h-32dp)/pageH)` ditengahkan, ketuk dua kali = ganti fit-halaman/fit-lebar, pil "n / total" melayang 40dp. Usaha: kecil.
- **T3 Hapus permanen tanpa urung.** `repo.delete` = `deleteRecursively` (`NotebookRepository.kt:279-287`), dialog "tidak dapat dibatalkan". Saran: soft-delete ke `.trash/<id>`, bar "Urungkan" 6 detik, kosongkan `.trash` saat start, `list()` abaikan `.trash`; hapus halaman simpan snapshot memori. Usaha: sedang.
- **T4 Judul default & `updatedAt`.** Semua kartu "Tanpa Judul"; `saveMeta()` selalu menimpa `updatedAt` (`EditorActivity.kt:2314`) sehingga urutan berubah hanya karena dibuka. Saran: judul default bertanggal, flag `dirty`, ganti nama dari pil judul editor, samakan huruf "Tanpa judul". Usaha: kecil.
- **T5 Chrome tablet makan ±40% lebar.** Saran: panel kanan bisa dilipat (sisakan strip 56dp swatch aktif), rel halaman bisa disembunyikan, status lipat disimpan, abaikan sentuhan panel selama/300ms setelah goresan stylus. Usaha: sedang.

## Prioritas sedang
- **S1** Ketuk dua jari = urung, tiga jari = ulang (<250ms, <10dp); undo/redo di pojok jauh.
- **S2** Thumbnail rel hanya halaman 1 (`EditorActivity.kt:~805`), angka menimpa gambar; simpan `thumb_<n>.webp` per halaman, angka hanya bila belum ada thumbnail.
- **S3** `onConfigurationChanged` selalu `fitPage()` di PAGED (`EditorActivity.kt:166-171`) menghapus zoom pengguna; fit ulang hanya bila viewport belum digeser.
- **S4** Dialog rename/opsi/hapus pakai `AlertDialog` sistem (`MainActivity.kt:810-876`); pakai kerangka dialog sketsa (`EditorActivity.kt:2181`), tambah tombol titik-tiga 44dp di kartu.
- **S5** Keadaan kosong salah konteks saat pencarian/filter tanpa hasil (`MainActivity.kt:286,441`).
- **S6** Tombol samping stylus (`BUTTON_STYLUS_PRIMARY`) tidak dipakai; tahan = penghapus sementara.
- **S7** Aksesibilitas: swatch/ketebalan tanpa `contentDescription`; sakelar jari FrameLayout tanpa peran/status (`:1176,:1760`); tidak ada `stateDescription`; umumkan "Halaman n dari N".
- **S8** Tinggi tetap (tombol 44, toolbar 76, pil 48-52dp) terpotong pada font besar; pakai `minHeight`, batasi label toolbar `min(fontScale,1.3)`.
- **S9** Ponsel landscape/layar pendek: chrome >60% layar; toolbar vertikal 64dp bila `screenHeightDp < 500`.

## Prioritas rendah
R1 indikator simpan hanya bila lambat/gagal; R2 urut "Judul A-Z"; R3 tombol × pencarian + Back; R4 fokus keyboard judul hanya di ponsel, ingat jenis kanvas terakhir; R5 `forceDarkAllowed=false`; R6 tombol hapus halaman: sembunyikan (bukan pudar) bila 1 halaman.

## Dibanding aplikasi sejenis
Layak: palm rejection (T1), ketuk 2/3 jari (S1), template kertas PAGED (polos/garis/kotak/titik), ekspor PDF via `PdfDocument`, laso pilih/pindah/hapus, duplikat & urut ulang halaman.
Jangan (demi ringan): cloud/akun, OCR, rekam audio, layer, folder bertingkat/tag, kolaborasi, impor PDF anotasi, stiker, gulir kontinu banyak halaman, mode gelap kanvas.

## 5 tertinggi
1. T1  2. T2  3. T3  4. T4  5. T5 + S1

## Jangan disentuh
Gaya sketsa (palet, SketchDrawable, font), sheet Notebook baru dan grid 2/3/4 kolom, arsitektur kanvas (strokeToWorld, satu halaman di memori, executor I/O tunggal, tulis .tmp + rename), dua mode per notebook, 6 alat berlabel, autosave senyap, tanpa AppCompat/Material/Compose.

Risiko: T1 menyentuh gesture yang sudah terbukti (uji ulang pinch dengan stylus asli); T3 menambah status filesystem; T2 mengecilkan halaman sehingga perlu toggle fit-lebar.
