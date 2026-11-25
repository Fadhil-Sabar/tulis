package io.github.fadhil_sabar.tulis.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class ImageItem(
    val name: String,
    var cx: Float,
    var cy: Float,
    var w: Float,
    var h: Float,
    var rotationDeg: Float
) {
    fun copy(): ImageItem = ImageItem(name, cx, cy, w, h, rotationDeg)

    fun state(): FloatArray = floatArrayOf(cx, cy, w, h, rotationDeg)

    fun setState(s: FloatArray) {
        if (s.size < 5) return
        cx = s[0]
        cy = s[1]
        w = s[2]
        h = s[3]
        rotationDeg = s[4]
    }

    fun worldBounds(out: RectF) {
        val hw = abs(w) * 0.5f
        val hh = abs(h) * 0.5f
        if (rotationDeg == 0f) {
            out.set(cx - hw, cy - hh, cx + hw, cy + hh)
            return
        }
        val rad = rotationDeg * (PI / 180.0)
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val ex = hw * c + hh * s
        val ey = hw * s + hh * c
        out.set(cx - ex, cy - ey, cx + ex, cy + ey)
    }

    fun hitTest(x: Float, y: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        val dx = x - cx
        val dy = y - cy
        val hw = w * 0.5f
        val hh = h * 0.5f
        if (rotationDeg == 0f) {
            return abs(dx) <= hw && abs(dy) <= hh
        }
        val rad = rotationDeg * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        return abs(lx) <= hw && abs(ly) <= hh
    }

    fun drawMatrix(bmpW: Int, bmpH: Int, out: Matrix) {
        if (bmpW <= 0 || bmpH <= 0) {
            out.reset()
            return
        }
        out.setScale(w / bmpW, h / bmpH)
        out.postTranslate(-w * 0.5f, -h * 0.5f)
        out.postRotate(rotationDeg)
        out.postTranslate(cx, cy)
    }
}

fun interface ImageProvider {
    fun bitmap(name: String, maxSidePx: Int): Bitmap?
}

object ImageDraw {
    fun drawAll(
        canvas: Canvas,
        images: List<ImageItem>,
        toDevice: Matrix,
        deviceScale: Float,
        provider: ImageProvider?,
        paint: Paint,
        placeholder: Paint,
        tmp: Matrix
    ) {
        if (images.isEmpty()) return
        paint.isFilterBitmap = true
        val safeScale = if (deviceScale > 0f && deviceScale.isFinite()) deviceScale else 1f
        for (i in 0 until images.size) {
            val item = images[i]
            if (item.w <= 0f || item.h <= 0f) continue
            val maxSidePx = ceil(max(item.w, item.h) * safeScale).toInt().coerceAtLeast(1)
            val bmp = provider?.bitmap(item.name, maxSidePx)
            if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                item.drawMatrix(bmp.width, bmp.height, tmp)
                tmp.postConcat(toDevice)
                canvas.drawBitmap(bmp, tmp, paint)
            } else {
                val hw = item.w * 0.5f
                val hh = item.h * 0.5f
                tmp.setRotate(item.rotationDeg)
                tmp.postTranslate(item.cx, item.cy)
                tmp.postConcat(toDevice)
                canvas.save()
                canvas.concat(tmp)
                placeholder.isAntiAlias = true
                placeholder.style = Paint.Style.FILL
                placeholder.color = 0xFFE0E0E0.toInt()
                canvas.drawRect(-hw, -hh, hw, hh, placeholder)
                placeholder.style = Paint.Style.STROKE
                placeholder.color = 0xFF9E9E9E.toInt()
                placeholder.strokeWidth = 1.5f / safeScale
                canvas.drawRect(-hw, -hh, hw, hh, placeholder)
                canvas.restore()
            }
        }
    }
}
