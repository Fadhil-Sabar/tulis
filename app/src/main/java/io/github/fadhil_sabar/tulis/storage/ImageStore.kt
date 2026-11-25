package io.github.fadhil_sabar.tulis.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import io.github.fadhil_sabar.tulis.model.ImageProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object ImageStore {
    private const val MAX_PIXELS = 100_000_000L
    private const val MAX_SIDE_PX = 2048

    class Imported(val name: String, val width: Int, val height: Int)

    fun importFromUri(ctx: Context, uri: Uri, dir: File): Imported {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            } ?: throw IOException("Gambar tidak bisa dibaca")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gambar tidak bisa dibaca", e)
        }

        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) {
            throw IOException("Gambar tidak bisa dibaca")
        }
        if (origW.toLong() * origH.toLong() > MAX_PIXELS) {
            throw IOException("Gambar terlalu besar")
        }

        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val origMax = max(origW, origH)
        var sample = 1
        while (origMax / 2 / sample >= MAX_SIDE_PX) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var current: Bitmap = try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("Gambar terlalu besar", e)
        } catch (e: Exception) {
            throw IOException("Gambar tidak bisa dibaca", e)
        } ?: throw IOException("Gambar tidak bisa dibaca")

        val outW: Int
        val outH: Int
        var bestBytes: ByteArray? = null
        var bestExt: String? = null

        try {
            val curMax = max(current.width, current.height)
            if (curMax > MAX_SIDE_PX) {
                val scale = MAX_SIDE_PX.toFloat() / curMax.toFloat()
                val targetW = (current.width * scale).roundToInt().coerceIn(1, MAX_SIDE_PX)
                val targetH = (current.height * scale).roundToInt().coerceIn(1, MAX_SIDE_PX)
                val scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true)
                if (scaled !== current) {
                    current.recycle()
                    current = scaled
                }
            }

            val matrix = exifMatrix(orientation)
            if (matrix != null) {
                val transformed = Bitmap.createBitmap(
                    current,
                    0,
                    0,
                    current.width,
                    current.height,
                    matrix,
                    true
                )
                if (transformed !== current) {
                    current.recycle()
                    current = transformed
                }
            }

            outW = current.width
            outH = current.height

            fun consider(bytes: ByteArray?, ext: String, preferEqual: Boolean = false) {
                if (bytes == null || bytes.isEmpty()) return
                val cur = bestBytes
                if (cur == null || bytes.size < cur.size || (preferEqual && bytes.size == cur.size)) {
                    bestBytes = bytes
                    bestExt = ext
                }
            }

            if (current.hasAlpha()) {
                consider(encodeBitmap(current, Bitmap.CompressFormat.PNG, 100), ".png")
                if (Build.VERSION.SDK_INT >= 30) {
                    consider(encodeBitmap(current, Bitmap.CompressFormat.WEBP_LOSSLESS, 100), ".webp")
                }
            } else {
                consider(encodeBitmap(current, Bitmap.CompressFormat.JPEG, 88), ".jpg")
                val webpFormat = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                consider(encodeBitmap(current, webpFormat, 85), ".webp")
            }

            val needsScale = origMax > MAX_SIDE_PX
            val needsTransform = matrix != null
            val rawExt = rawExtForMime(boundsOpts.outMimeType ?: ctx.contentResolver.getType(uri))
            if (!needsScale && !needsTransform && rawExt != null) {
                val rawBytes = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }
                consider(rawBytes, rawExt, preferEqual = true)
            }
        } finally {
            if (!current.isRecycled) {
                current.recycle()
            }
        }

        val finalBytes = bestBytes ?: throw IOException("Gambar tidak bisa dibaca")
        val finalExt = bestExt ?: throw IOException("Gambar tidak bisa dibaca")
        val name = "img_" + ImageRefs.sha1Hex(finalBytes) + finalExt

        if (!dir.exists()) {
            dir.mkdirs()
        }
        val target = File(dir, name)
        if (!target.exists()) {
            val tmp = File(dir, "$name.tmp")
            try {
                FileOutputStream(tmp).use { fos ->
                    fos.write(finalBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    if (target.exists()) {
                        tmp.delete()
                    } else {
                        throw IOException("Gagal menyimpan gambar")
                    }
                }
            } catch (e: IOException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                throw IOException("Gagal menyimpan gambar", e)
            }
        }

        return Imported(name, outW, outH)
    }

    fun decode(file: File, maxSidePx: Int): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        } catch (_: Throwable) {
            return null
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        val longSide = max(origW, origH)
        val target = maxSidePx.coerceAtLeast(1)
        var sample = 1
        while (longSide / 2 / sample >= target) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    fun provider(dir: File): ImageProvider {
        val cache = HashMap<String, Bitmap>()
        val requestedMax = HashMap<String, Int>()
        val failed = HashSet<String>()
        val lock = Any()
        return ImageProvider { name, maxSidePx ->
            if (!ImageRefs.isValidName(name)) return@ImageProvider null
            val target = maxSidePx.coerceIn(1, MAX_SIDE_PX)
            synchronized(lock) {
                if (failed.contains(name)) return@ImageProvider null
                val existing = cache[name]
                val prevTarget = requestedMax[name] ?: 0
                if (existing != null && !existing.isRecycled) {
                    val existingMax = max(existing.width, existing.height)
                    val atFullRes = existingMax < prevTarget
                    if (prevTarget >= target || existingMax >= target || atFullRes) {
                        return@ImageProvider existing
                    }
                }
                val decoded = decode(File(dir, name), target)
                if (decoded == null) {
                    if (existing != null && !existing.isRecycled) {
                        return@ImageProvider existing
                    }
                    failed.add(name)
                    return@ImageProvider null
                }
                cache[name] = decoded
                requestedMax[name] = max(prevTarget, target)
                decoded
            }
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    private fun rawExtForMime(mime: String?): String? = when (mime?.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> null
    }

    private fun exifMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(270f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
            else -> return null
        }
        return m
    }
}
