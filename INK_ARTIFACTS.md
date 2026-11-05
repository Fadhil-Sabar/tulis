# Hasil Verifikasi Dependensi androidx.ink & androidx.input

Verifikasi dilakukan via `https://dl.google.com/dl/android/maven2/` dan resolusi dependensi Gradle menggunakan `./gradlew :app:dependencies --configuration debugRuntimeClasspath`.

## 1. Status Artifact & Versi

| Artifact | Versi Diminta | Status di Google Maven | Resolusi Gradle | Catatan |
|---|---|---|---|---|
| `androidx.ink:ink-authoring` | `1.0.0` | Tersedia (`1.0.0`) | `SUCCESS` | Menyediakan `InProgressStrokesView` |
| `androidx.ink:ink-rendering` | `1.0.0` | Tersedia (`1.0.0`) | `SUCCESS` | Menyediakan `CanvasStrokeRenderer` |
| `androidx.ink:ink-strokes` | `1.0.0` | Tersedia (`1.0.0`) | `SUCCESS` | Menyediakan `Stroke`, `StrokeInputBatch` |
| `androidx.ink:ink-storage` | `1.0.0` | Tersedia (`1.0.0`) | `SUCCESS` | Konfirmasi nama artifact valid & tersedia di versi 1.0.0 |
| `androidx.input:input-motionprediction` | `1.0.0` | Tersedia (`1.0.0`) | `SUCCESS` | Prediksi gerakan stylus/touch |

## 2. Dependensi Transitif Kunci
- `androidx.ink:ink-brush:1.0.0` (terbawa oleh authoring, rendering, strokes, storage)
- `androidx.ink:ink-geometry:1.0.0` (terbawa oleh brush, rendering, strokes)
- `androidx.ink:ink-nativeloader:1.0.0`
- `androidx.core:core:1.16.0`

## 3. Hasil Perintah Resolusi
Perintah:
```bash
JAVA_HOME=/home/diru/.local/jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/diru/android-sdk ./gradlew :app:dependencies --configuration debugRuntimeClasspath
```
Status: `BUILD SUCCESSFUL`. Seluruh artifact `1.0.0` berhasil di-resolve tanpa konflik versi.
