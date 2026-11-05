# Tulis — Rencana Eksekusi

Aplikasi catatan tulisan tangan + teks untuk Android yang super ringan dan responsif,
dengan dua mode per notebook: **infinite canvas** dan **per halaman**.

> Status: rencana disusun tanpa Android SDK; versi dependensi dan signature API Ink **belum diverifikasi lewat kompilasi** (lihat bagian 6).

## 1. Keputusan

| Topik | Keputusan | Alasan |
|---|---|---|
| Bahasa/UI | Kotlin native, **View biasa** (tanpa Compose, tanpa AppCompat/Material) | Dependensi minimal, APK kecil |
| Mesin goresan | `androidx.ink` 1.0.0 | Authoring latensi rendah, rendering, brush, penyimpanan input |
| Input | Tulisan tangan **dan** blok teks | |
| Mode | Dipilih **per notebook** saat dibuat: `INFINITE` atau `PAGED` | |
| Base project | Dari nol; **Cahier** (github.com/android/cahier, Apache 2.0) hanya referensi pola integrasi Ink | |
| Penyimpanan | File biner sendiri, satu file per halaman, dimuat malas | Tanpa Room |
| Gaya UI | Sketsa pena: garis goyang prosedural lewat `SketchDrawable`, kertas krem + tinta gelap | Tanpa aset gambar |

Tidak dipakai: Saber, Hilt, Room, Coil, Compose, AppCompat.

## 2. Toolchain
- JDK 17, Gradle **8.13**, AGP **8.13.0**, Kotlin **2.2.20**
- `compileSdk 36`, `targetSdk 35`, `minSdk 26`
- Ink: `ink-authoring`, `ink-rendering`, `ink-strokes`, `ink-storage` versi `1.0.0` (nama artifact `ink-storage` belum terkonfirmasi; `ink-brush`/`ink-geometry` transitif)
- Kotlin 2.2: `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`, bukan `kotlinOptions`.
- `gradle.properties`: `android.useAndroidX=true`, `android.nonTransitiveRClass=true`.
- Jika Ink menuntut compileSdk/AGP lebih tinggi, naikkan seperlunya dan catat.

## 3. Arsitektur
```
com.example.tulis
├── MainActivity.kt          daftar notebook, dialog buat/ganti nama/hapus
├── EditorActivity.kt        toolbar, bar halaman, dialog teks, autosave
├── model/ Models.kt, Viewport.kt
├── ink/   InkBridge.kt, StrokeItem.kt     (SATU-SATUNYA yang menyentuh API Ink)
├── ui/    SketchDrawable.kt, Icons.kt
├── canvas/ CanvasLayer.kt, NoteCanvasView.kt
└── storage/ PageCodec.kt, NotebookRepository.kt
```

### Ide kunci
1. **Goresan disimpan dalam koordinat layar saat dibuat, plus matriks `strokeToWorld`.** `InProgressStrokesView` dipakai dengan transform identitas (koordinat = layar). Ukuran kuas = `lebarDunia × scaleSaatItu` (piksel). Saat selesai, `strokeToWorld = inverse(worldToScreen saat mulai)`. Render: `strokeToScreen = worldToScreen × strokeToWorld`. Pan/zoom cukup mengganti satu Matrix.
2. **Culling**: lewati goresan/teks yang `worldBounds`-nya tidak memotong area terlihat.
3. **Hanya halaman aktif di memori**.
4. **Byte input ter-encode di-cache per goresan** (`encodedInputs`), autosave hanya menyalin array.
5. **Semua I/O lewat satu executor** (FIFO).
6. **UI lewat kode** (tanpa XML layout, tanpa AppCompat), `Theme.DeviceDefault.Light.NoActionBar`.
7. Tanpa alokasi objek per frame di `onDraw`.

### Mode
- `INFINITE`: latar polos + grid titik adaptif (spasi 40 unit dunia, dilipat dua saat terlalu rapat). Viewport terakhir disimpan di `meta.json`.
- `PAGED`: halaman 1000 × 1414 unit dunia (A4) di atas latar abu-abu, konten di-clip ke halaman, goresan hanya bisa dimulai di dalam halaman, pan dibatasi (sisakan ≥ 48 px halaman terlihat), fit-width tiap membuka halaman.

### Perilaku sentuhan
| Kondisi | Aksi |
|---|---|
| Stylus / tombol penghapus stylus | Gambar / hapus |
| Jari + opsi "jari menggambar" aktif | Gambar (default aktif) |
| Jari + opsi nonaktif, atau alat Geser | Pan (dengan fling) |
| Dua jari | Pinch-zoom + pan; batalkan goresan jari yang sedang berjalan |
| Stylus sedang menggambar lalu jari menyentuh | Abaikan jari |
| Alat Teks + ketuk (tanpa geser) | Dialog tambah/ubah/hapus teks |

### Format file
```
filesDir/notebooks/<uuid>/meta.json        {id,title,mode,pageCount,updatedAt,vp:[scale,tx,ty],lastPage}
filesDir/notebooks/<uuid>/page_<n>.bin
filesDir/notebooks/<uuid>/thumb.webp       pratinjau halaman 1 (±256px), dibuat di background saat autosave
```
`page_<n>.bin` (big-endian, `DataOutputStream`):
```
int   magic 0x494E4B50 ("INKP")      byte version = 1
int   strokeCount
  byte  brushKind(ordinal)   int colorArgb   float sizePx
  float a,b,c,d,e,f          (Matrix: SCALE_X, SKEW_X, TRANS_X, SKEW_Y, SCALE_Y, TRANS_Y)
  int   inputLen             byte[] inputs (hasil ink-storage encode)
int   textCount
  float x,y,sizeWorld   int colorArgb   float widthWorld   int utf8Len   byte[] utf8
```
Tulis ke `.tmp` lalu `renameTo` (fsync dulu); saat baca, goresan korup dilewati, file korup di-rename `.corrupt`. Batasi `inputLen` ≤ 16 MB.

## 4. Fase
- **Fase 0 Setup**: proyek Gradle (settings/root/app kts, gradle.properties, wrapper), Manifest (`MainActivity` launcher; `EditorActivity` dengan `configChanges` orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout, `windowSoftInputMode=adjustResize`; `allowBackup=false`), release minify+shrink. Selesai bila `./gradlew assembleDebug` sukses dengan Ink ter-resolve.
- **Fase 1 Kanvas+Ink**: InkBridge, StrokeItem, Viewport, CanvasLayer, NoteCanvasView (state gesture NONE/DRAW/ERASE/PAN/PINCH, OverScroller fling, VelocityTracker).
- **Fase 2 Model+penyimpanan**: Models (undo/redo maks 100), PageCodec, NotebookRepository, autosave debounce 1,2 dtk + flush onPause.
- **Fase 3 UI** (gaya sketsa pena): kertas `#F6F0E2`, kartu `#FBF7EC`, tinta `#2B2724`, tinta lembut `#5B534A`, pensil merah `#C23B1F`, stabilo `#F5D547`, biru `#2D5DA8`, hijau `#3E7D4F`. Garis 2,5 px, bayangan offset 2–3 px, radius divariasikan. Font sistem (tanpa aset font). SketchDrawable: jitter dari seed, seed dibuat ulang hanya saat muncul/ditekan (bukan animasi terus-menerus), cache Path per ukuran. Icons: path ikon (kembali, cari, tambah, urung, ulang, pena, spidol, stabilo, hapus, teks, geser, tempat sampah, infinite, halaman).
  - MainActivity (Beranda): judul "Tulis" + garis bergelombang merah, chip filter Semua/Infinite/Halaman, `GridView` 2 kolom kartu (thumbnail, judul, meta "Tak terbatas · tgl" / "n halaman · tgl", kemiringan ±1°), tombol "+ Baru" melayang. Tekan lama → ganti nama / hapus (konfirmasi). Dialog "Notebook baru": bottom sheet (judul, dua kartu pilihan Tak terbatas/Per halaman, tombol Batal & "Buat notebook").
  - EditorActivity ponsel: bar atas (kembali, judul, urung/ulang; mode infinite: chip "Pusatkan" + indikator zoom ketuk=reset). Toolbar bawah melayang 6 alat (Pena, Spidol, Stabilo, Hapus, Teks, Geser) dengan titik warna aktif; ketuk alat aktif → popover: 6 warna, 3 ketebalan, toggle "Jari bisa menggambar". Mode halaman: bar halaman (◀ n / total ▶, + Halaman, hapus halaman; minimal 1 halaman).
  - Tablet (lebar ≥ 840dp): rel thumbnail halaman kiri, panel opsi kanan, alat di bar atas (boleh disederhanakan).
  - Aksesibilitas: target sentuh ≥ 44dp, `contentDescription` semua ikon, `isSelected` untuk alat terpilih. Root `fitsSystemWindows = true`.
- **Fase 4 Mode halaman**: halaman + clip + border, `insidePage()`, tambah/pindah/hapus halaman (simpan dulu, kosongkan, muat async dengan token agar hasil usang diabaikan).
- **Fase 5 Penghapus, teks, undo/redo**: hit-test titik→segmen di koordinat dunia (radius 12dp/scale), satu aksi undo per gestur hapus. Teks: `StaticLayout` unit dunia (cache per item), tap kosong = tambah, tap teks = ubah/hapus.
- **Fase 6 Performa**: cache bitmap saat pan jika berat, prediksi gerakan, APK release.

## 5. Target
Pan/zoom ≈60fps pada 2000 goresan; buka halaman tidak memblokir UI; autosave tanpa jank; tanpa alokasi per frame di onDraw.

## 6. Checklist verifikasi API Ink 1.0.0 (hanya di `ink/`)
1. `InProgressStrokesView(context)`; `startStroke(event, pointerId, brush)`; `addToStroke(event, pointerId, strokeId)`; `finishStroke(event, pointerId, strokeId)`; `cancelStroke(strokeId, event)`.
2. `addFinishedStrokesListener(...)` bentuk callback; `removeFinishedStrokes(Set<InProgressStrokeId>)`; `eagerInit()`.
3. `CanvasStrokeRenderer.create()` dan `draw(canvas, stroke, strokeToScreenTransform: Matrix)`.
4. `StockBrushes.pressurePen()/marker()/highlighter()`; `Brush.createWithColorIntArgb(family, colorIntArgb, size, epsilon)`.
5. `Stroke(brush, inputs)`; `stroke.inputs`; `stroke.shape.computeBoundingBox()` → `Box?`.
6. Membaca titik: `StrokeInputBatch.size`, `get(i)`/`populate(i,out)`; `StrokeInput.x/y`.
7. Serialisasi: `StrokeInputBatch.encode(OutputStream)` / `decode(InputStream)` (modul `ink-storage`).
8. Stabilo: alpha manual (0x66) atau sudah ditangani brush bawaan.
Cara verifikasi di mesin ini: unduh sources/AAR dari Google Maven (dl.google.com/dl/android/maven2/androidx/ink/...) dan baca `javap`/sources jar; bukan menebak.

## 7. Cara kerja
Hanya edit file milik Anda. Semua pemanggilan API Ink hanya di folder `ink/`. Lihat CONTRACT.md untuk signature antar-file.
