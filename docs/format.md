# Spesifikasi Format Berkas Tulis (.tulis) & PageCodec v2

Dokumen ini mendefinisikan spesifikasi lengkap format cadangan `.tulis` dan format biner halaman `page_N.bin` (versi 2). Spesifikasi ini dirancang netral platform (tanpa ketergantungan pada AndroidX Ink atau Protobuf) agar dapat diimplementasikan dan dibaca/ditulis dari nol di platform lain, termasuk iOS (Swift).

---

## 1. Struktur Arsip `.tulis`

Berkas `.tulis` adalah arsip ZIP standar yang memuat berkas-berkas catatan berikut:

```
<notebook-id>.tulis
├── manifest.json       (Wajib: metadata arsip dan verifikasi hash)
├── meta.json           (Wajib: konfigurasi dan metadata catatan)
├── page_0.bin          (Halaman 0 dalam format biner)
├── page_1.bin          (Halaman 1, opsional jika ada)
├── ...
└── thumb.webp          (Opsional: gambar thumbnail cover catatan)
```

### 1.1 Aturan Validasi Entri ZIP
- Pola nama entri yang diizinkan (Regex):
  `^(meta\.json|manifest\.json|thumb\.webp|page_\d+\.bin)$`
- Entri di luar pola tersebut dianggap tidak valid (mencegah *Zip Slip* dan berkas asing).
- Batas keamanan pembaca:
  - Jumlah entri maksimal: 5.000 entri.
  - Ukuran total hasil ekstraksi maksimal: 512 MB.

---

## 2. Skema Berkas JSON

### 2.1 `manifest.json`

Memuat informasi versi format dan hash integritas catatan:

| Field | Tipe | Nilai / Contoh | Keterangan |
|---|---|---|---|
| `format` | Int | `2` | Versi struktur arsip (arsip lama: `1`, arsip saat ini: `2`). |
| `pageCodec` | Int | `2` | Versi codec biner halaman (v1: `1`, v2 netral: `2`). |
| `app` | String | `"0.2.0"` | Versi aplikasi pembuat arsip. |
| `id` | String | `"uuid-string"` | UUID unik catatan. |
| `title` | String | `"Catatan Fisika"` | Judul catatan. |
| `hash` | String | `"v2:a4f052..."` | Hash integritas catatan (lihat bagian Hash Integritas). |
| `pageCount` | Int | `3` | Jumlah halaman catatan. |
| `folderPath` | Array of Object | `[{"id": "f1", "name": "Kuliah"}]` | Jalur folder bertingkat dari atas ke bawah. Kosong `[]` jika di root. |

### 2.2 `meta.json`

Memuat konfigurasi catatan dan preferensi tampilan:

| Field | Tipe | Nilai Default | Keterangan |
|---|---|---|---|
| `id` | String | (Wajib) | UUID catatan. |
| `title` | String | (Wajib) | Judul catatan. |
| `mode` | String | (Wajib) | `"INFINITE"` (kanvas bebas) atau `"PAGED"` (halaman berurutan). |
| `pageCount` | Int | `1` | Jumlah halaman aktif. |
| `updatedAt` | Long | (Wajib) | Timestamp waktu modifikasi terakhir (milidetik sejak Unix epoch). |
| `lastPage` | Int | `0` | Indeks halaman terakhir yang dibuka (0-based). |
| `paper` | String | `"DOTS"` (INFINITE) / `"PLAIN"` (PAGED) | Gaya kertas: `"PLAIN"`, `"DOTS"`, `"GRID"`, `"LINES"`. |
| `paperSpacing` | Double | `28.0` | Jarak spasi garis/titik kertas dalam satuan poin (pt). Dibatasi antara `12.0` hingga `120.0`. |
| `orientation` | String | `"PORTRAIT"` | Orientasi: `"PORTRAIT"` atau `"LANDSCAPE"`. |
| `folderId` | String? | `null` / `""` | ID folder tempat catatan berada, atau kosong/null jika di root. |
| `vp` | Array of Double | `[1.0, 0.0, 0.0]` | *(Opsional)* Posisi viewport terakhir: `[scale, tx, ty]`. |

Dimensi standar halaman pada mode `PAGED`:
- `PORTRAIT`: lebar 1.000 pt, tinggi 1.414 pt (rasio $\sqrt{2}$).
- `LANDSCAPE`: lebar 1.414 pt, tinggi 1.000 pt.

---

## 3. Format Biner Halaman (`page_N.bin`)

Semua tipe data numerik ditulis dan dibaca dalam urutan **Big-Endian** (sesuai `java.io.DataOutputStream` / `DataInputStream` atau `Network Byte Order`).

### 3.1 Header Berkas

| Field | Tipe | Ukuran | Nilai | Keterangan |
|---|---|---|---|---|
| `magic` | Int32 | 4 byte | `0x494E4B50` | ASCII karakter `"INKP"`. |
| `version` | Int8 | 1 byte | `2` | Versi 2 (versi netral). Versi 1 adalah format lama AndroidX Ink. |

---

### 3.2 Layout Halaman Versi 2 (Detail Byte Demi Byte)

Struktur isi halaman v2 tersusun sebagai berikut:

```
[Header]
  magic: Int32 (0x494E4B50)
  version: Int8 (2)

[Bagian Goresan (Strokes)]
  strokeCount: Int32 (0 .. 1.000.000)
  per goresan:
    brushKind: Int8 (0: PEN, 1: MARKER, 2: HIGHLIGHTER)
    colorArgb: Int32 (ARGB 32-bit color int)
    sizePx: Float32 (ketebalan kuas dasar dalam pixel)
    matrix: 6 * Float32 (matriks transformasi 2D strokeToWorld: a, b, c, d, e, f)
    toolType: Int8 (0: UNKNOWN, 1: MOUSE, 2: TOUCH, 3: STYLUS)
    strokeUnitLengthCm: Float32 (unit panjang dalam cm, sentinel: 0.0f)
    pointCount: Int32 (jumlah titik, 0 .. 1.000.000)
    per titik:
      x: Float32 (koordinat x dalam stroke space)
      y: Float32 (koordinat y dalam stroke space)
      elapsedTimeMillis: Int64 (waktu dari awal goresan dalam milidetik)
      pressure: Float32 (tekanan [0.0..1.0], sentinel: -1.0f)
      tiltRadians: Float32 (kemiringan dalam radian, sentinel: -1.0f)
      orientationRadians: Float32 (sudut orientasi dalam radian, sentinel: -1.0f)

[Bagian Teks (Texts)]
  textCount: Int32 (0 .. 100.000)
  per item teks:
    x: Float32 (koordinat x teks di world space)
    y: Float32 (koordinat y teks di world space)
    sizeWorld: Float32 (ukuran font dalam world space)
    colorArgb: Int32 (ARGB 32-bit color int)
    widthWorld: Float32 (lebar batas kotak teks di world space)
    utf8Len: Int32 (panjang byte teks UTF-8, 0 .. 10.485.760)
    utf8Bytes: byte array sepanjang utf8Len
```

---

### 3.3 Nilai Sentinel dan Pemetaan Enum

#### A. Sentinel (Nilai ketika data sensor tidak tersedia)
- `strokeUnitLengthCm`: `0.0f` (`NO_STROKE_UNIT_LENGTH`)
- `pressure`: `-1.0f` (`NO_PRESSURE`)
- `tiltRadians`: `-1.0f` (`NO_TILT`)
- `orientationRadians`: `-1.0f` (`NO_ORIENTATION`)

#### B. Pemetaan `toolType` (1 byte)
- `0`: `UNKNOWN` (Alat tidak diketahui)
- `1`: `MOUSE`
- `2`: `TOUCH` (Sentuhan jari)
- `3`: `STYLUS` (Pena digital / Apple Pencil / S-Pen)

#### C. Pemetaan `brushKind` (1 byte)
- `0`: `PEN` (Pena peka tekanan)
- `1`: `MARKER` (Spidol)
- `2`: `HIGHLIGHTER` (Stabilo)

---

### 3.4 Transformasi Koordinat (Stroke Space ke World Space)

Titik-titik `(x, y)` pada tiap goresan disimpan dalam koordinat lokal goresan (*stroke space*). Untuk memetakan ke koordinat catatan (*world space*), gunakan 6 float matriks afinitas 2D:

$$\begin{bmatrix} a & b & c \\ d & e & f \\ 0 & 0 & 1 \end{bmatrix}$$

Urutan penyimpanan 6 float pada berkas biner:
1. `a`: `MSCALE_X`
2. `b`: `MSKEW_X`
3. `c`: `MTRANS_X` (translasi x)
4. `d`: `MSKEW_Y`
5. `e`: `MSCALE_Y`
6. `f`: `MTRANS_Y` (translasi y)

Transformasi titik:
$$x_{\text{world}} = a \cdot x + b \cdot y + c$$
$$y_{\text{world}} = d \cdot x + e \cdot y + f$$

Di Swift / CoreGraphics (iOS), matriks ini dapat langsung dibentuk dengan:
```swift
let transform = CGAffineTransform(
    a: CGFloat(a),
    b: CGFloat(d),
    c: CGFloat(b),
    d: CGFloat(e),
    tx: CGFloat(c),
    ty: CGFloat(f)
)
```

---

### 3.5 Catatan Rendering Kuas

1. **Pena (`PEN`)**:
   - Menghasilkan ketebalan dinamis berdasarkan nilai `pressure`.
   - Di Android menggunakan keluarga kuas `StockBrushes.pressurePen()`.
2. **Spidol (`MARKER`)**:
   - Garis padat seragam dengan sudut bulat.
   - Di Android menggunakan keluarga kuas `StockBrushes.marker()`.
3. **Stabilo (`HIGHLIGHTER`)**:
   - Garis semi-transparan yang digambar **di bawah** goresan pena/spidol dan teks.
   - **Aturan Alpha Stabilo**: Jika nilai alpha warna adalah `0xFF` (penuh) atau `0` (kosong), aplikasikan transparansi default 40% (`0x66`):
     ```
     highColor = (colorArgb & 0x00FFFFFF) | 0x66000000
     ```
     Jika alpha sudah diisi nilai antara 1 hingga 254, gunakan warna ARGB apa adanya.
   - Di Android menggunakan keluarga kuas `StockBrushes.highlighter()`.
   - Epsilon aproksimasi kurva: `0.1f`. (berlaku untuk ketiga kuas)

---

### 3.6 Format Halaman Versi 1 (Catatan Kompatibilitas)

Pada format v1 (`version = 1`), setiap goresan menyimpan:
- `brushKind` (1 byte)
- `colorArgb` (4 byte)
- `sizePx` (4 byte)
- 6 matriks float (24 byte)
- `inputLen` (Int32)
- `inputs` (byte array sepanjang `inputLen`): hasil `StrokeInputBatch.encode()` dari pustaka `androidx.ink:ink-storage` (protobuf terkompresi, format internal AndroidX Ink).

Pembaca di platform lain (seperti iOS / Swift) tidak perlu mengimplementasikan decoder protobuf v1. Aplikasi Android Tulis otomatis memigrasikan halaman v1 ke v2 di latar belakang.

---

## 4. Algoritma Hash Integritas (`contentHash`)

Integritas arsip catatan diverifikasi dengan SHA-256 berawalan prefiks `"v2:"`. Algoritma hash bersifat deterministik dan bekerja atas byte mentah serta field semantik:

1. **Inisialisasi** objek SHA-256 MessageDigest.
2. **Update Metadata Semantik**:
   Format string per baris dengan karakter newline `\n` (dikodekan dalam UTF-8):
   - `"title=" + title + "\n"`
   - `"mode=" + mode + "\n"`
   - `"paper=" + paper + "\n"` (jika kosong, fallback `"DOTS"` untuk INFINITE atau `"PLAIN"` untuk PAGED)
   - `"paperSpacing=" + String.format(Locale.ROOT, "%.2f", paperSpacing) + "\n"`
   - `"orientation=" + orientation + "\n"` (fallback `"PORTRAIT"`)
   - `"folderId=" + folderId + "\n"`
   - `"pageCount=" + pageCount + "\n"`
3. **Update Byte Halaman**:
   Untuk indeks halaman `i` dari `0` hingga `pageCount - 1`:
   - Jika berkas `page_i.bin` ada, baca seluruh byte berkas dan masukkan ke SHA-256.
   - Jika berkas `page_i.bin` tidak ditemukan, masukkan string UTF-8 `"missing=" + i + "\n"`.
4. **Hasil Akhir**:
   Keluaran 32-byte digest diubah menjadi 64 karakter heksadesimal huruf kecil, diawali dengan `"v2:"`:
   ```
   v2:08fecfbd1dd4543524421b746aecab51ed1ea36a77ffa28aa9933fb03b7b2a8e
   ```

---

## 5. Aturan Kompatibilitas, Migrasi, & Sinkronisasi Vault

### 5.1 Kompatibilitas Mundur & Maju
- Aplikasi dengan `FORMAT = 2` dapat membaca arsip lama (`format = 1`) maupun baru (`format = 2`).
- Aplikasi versi lama (`FORMAT = 1`) yang mendapati arsip `format = 2` akan menolak dengan pesan `"Perbarui Tulis"`, mencegah kerusakan data.
- Metode verifikasi halaman `validPage` menerima kedua versi halaman biner (`version == 1 || version == 2`).

### 5.2 Migrasi Otomatis (Android)
- `NotebookRepository` menjalankan pass migrasi otomatis satu kali di latar belakang pada executor serialized:
  1. Dijalankan saat inisialisasi aplikasi (`init`).
  2. Dijalankan setelah pemulihan/impor dari vault atau arsip (`installSync`).
- Setiap berkas `page_N.bin` yang masih berversi 1 dibaca dan ditulis ulang menjadi versi 2 secara atomik (`.tmp` lalu rename).
- Migrasi **tidak** mengubah `meta.json` atau timestamp `updatedAt`.
- Kalau ada goresan v1 yang gagal di-decode, halaman itu dibiarkan tetap v1 supaya tidak ada data yang hilang.

### 5.3 Determinisme & Bebas Konflik di Obsidian Vault
Algoritma konversi v1 ke v2 bersifat deterministik: urutan titik, sentuhan, nilai sensor, matriks, teks, dan format biner v2 menghasilkan byte yang identik 100%.

Alur sinkronisasi dengan Obsidian Vault (`VaultSync`):
1. Perangkat A memigrasikan catatan dari v1 ke v2. Hash lokal berubah dari $H_1$ ke $H_2$.
2. Di vault, berkas arsip masih memiliki hash lama $H_1$. Karena `vault == s.hash` ($H_1 == H_1$), Perangkat A mendorong (*push*) arsip v2 ($H_2$) ke vault.
3. Perangkat B memigrasikan catatan yang sama secara lokal ke v2. Karena konversi deterministik, hash lokal Perangkat B juga menjadi $H_2$.
4. Saat Perangkat B menyinkronkan dengan vault (yang kini berhash $H_2$), kondisi `vault == local` ($H_2 == H_2$) terpenuhi.
5. Perangkat B memperbarui status pelacakannya tanpa konflik palsu (*no false conflict*).
