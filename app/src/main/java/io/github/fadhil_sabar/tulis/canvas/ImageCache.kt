package io.github.fadhil_sabar.tulis.canvas

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.fadhil_sabar.tulis.storage.ImageStore
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

object ImageCache {
    private const val MIN_BUCKET = 64
    private const val MAX_BUCKET = 2048
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
    private val BUCKETS_DESC = intArrayOf(2048, 1024, 512, 256, 128, 64)

    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    private var generation = 0L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache: LruCache<String, Bitmap> = run {
        val maxBytes = min(Runtime.getRuntime().maxMemory() / 8L, MAX_CACHE_BYTES)
            .toInt()
            .coerceAtLeast(1)
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.allocationByteCount.coerceAtLeast(1)
            }
        }
    }

    fun peek(dir: File, name: String, maxSidePx: Int, onReady: () -> Unit): Bitmap? {
        val bucket = bucketFor(maxSidePx)
        val key = "$name@$bucket"
        val cached = cache.get(key)
        if (cached != null) {
            if (!cached.isRecycled) return cached
            cache.remove(key)
        }

        val reqKey = "${dir.path}/$key"
        var scheduleGen = -1L
        synchronized(lock) {
            val recheck = cache.get(key)
            if (recheck != null) {
                if (!recheck.isRecycled) return recheck
                cache.remove(key)
            }
            if (!failed.contains(reqKey) && !inFlight.contains(reqKey)) {
                inFlight.add(reqKey)
                scheduleGen = generation
            }
        }

        if (scheduleGen >= 0L) {
            val file = File(dir, name)
            executor.execute {
                val decoded = try {
                    ImageStore.decode(file, bucket)
                } catch (_: Throwable) {
                    null
                }
                var notify = false
                synchronized(lock) {
                    if (scheduleGen == generation) {
                        inFlight.remove(reqKey)
                        if (decoded != null) {
                            cache.put(key, decoded)
                            notify = true
                        } else {
                            failed.add(reqKey)
                        }
                    }
                }
                if (notify) {
                    mainHandler.post { onReady() }
                }
            }
        }

        return bestCachedBitmap(name)
    }

    fun trim() {
        synchronized(lock) {
            generation++
            failed.clear()
            inFlight.clear()
        }
        cache.evictAll()
    }

    private fun bucketFor(maxSidePx: Int): Int {
        if (maxSidePx <= MIN_BUCKET) return MIN_BUCKET
        if (maxSidePx >= MAX_BUCKET) return MAX_BUCKET
        var b = MIN_BUCKET
        while (b < maxSidePx) {
            b = b shl 1
        }
        return b
    }

    private fun bestCachedBitmap(name: String): Bitmap? {
        for (b in BUCKETS_DESC) {
            val bmp = cache.get("$name@$b")
            if (bmp != null && !bmp.isRecycled) {
                return bmp
            }
        }
        return null
    }
}
