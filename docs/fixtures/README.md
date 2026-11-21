# Berkas Contoh (Fixtures) .tulis v2

Folder ini berisi contoh berkas arsip notebook `.tulis` format versi 2 (`PAGE_CODEC = 2`, `FORMAT = 2`) yang netral untuk pengujian dan implementasi pembaca di platform lain (seperti iOS / Swift):

1. **`infinite_v2.tulis`**:
   - Mode: `INFINITE` (kanvas tak hingga)
   - Kertas: `DOTS` (spasi 28.0 pt)
   - Orientasi: `PORTRAIT`
   - Berisi goresan:
     - Pena (brushKind 0, stylus, variasi tekanan & kemiringan)
     - Spidol (brushKind 1, sentuhan, sentinel tekanan/kemiringan/orientasi `-1.0f`)
     - Stabilo (brushKind 2, mouse)
     - Goresan kosong (0 titik)
   - Teks UTF-8 multi-byte (bahasa Indonesia dan emoji).

2. **`paged_v2.tulis`**:
   - Mode: `PAGED` (mode halaman berurutan)
   - Kertas: `LINES` (spasi 32.0 pt)
   - Orientasi: `PORTRAIT`
   - Berisi folder path (`f1` -> "Kuliah")
   - Berisi goresan pena, spidol, stabilo, goresan 0 titik, dan teks UTF-8 dengan rumus matematika.

## Cara Menghasilkan Ulang (Regenerate)

Fixture dihasilkan secara deterministik melalui JVM unit test `RawPageCodecTest.fixturesGoldenTest`.

Untuk meregenerasi berkas fixture:

```bash
./gradlew testDebugUnitTest -Dtulis.generateFixtures=true
```

Atau hapus berkas `.tulis` di folder ini lalu jalankan unit test:

```bash
rm docs/fixtures/*.tulis
./gradlew testDebugUnitTest
```
