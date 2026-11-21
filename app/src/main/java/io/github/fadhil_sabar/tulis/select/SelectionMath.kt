package io.github.fadhil_sabar.tulis.select

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object SelectionMath {
    fun union(boxes: List<FloatArray>): FloatArray? {
        var hasBox = false
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        for (i in 0 until boxes.size) {
            val b = boxes[i]
            if (b.size < 4) continue
            if (!hasBox) {
                left = b[0]
                top = b[1]
                right = b[2]
                bottom = b[3]
                hasBox = true
            } else {
                if (b[0] < left) left = b[0]
                if (b[1] < top) top = b[1]
                if (b[2] > right) right = b[2]
                if (b[3] > bottom) bottom = b[3]
            }
        }
        return if (hasBox) floatArrayOf(left, top, right, bottom) else null
    }

    fun clampDelta(box: FloatArray, dx: Float, dy: Float, maxW: Float, maxH: Float): FloatArray {
        val cdx = if (box[2] - box[0] > maxW) 0f else dx.coerceIn(-box[0], maxW - box[2])
        val cdy = if (box[3] - box[1] > maxH) 0f else dy.coerceIn(-box[1], maxH - box[3])
        return floatArrayOf(cdx, cdy)
    }

    fun uniformScale(
        ax: Float,
        ay: Float,
        rx: Float,
        ry: Float,
        px: Float,
        py: Float,
        minScale: Float = 0.1f
    ): Float {
        val dx = rx - ax
        val dy = ry - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return 1f
        val dot = (px - ax) * dx + (py - ay) * dy
        return maxOf(dot / lenSq, minScale)
    }

    fun maxScaleInside(ax: Float, ay: Float, box: FloatArray, maxW: Float, maxH: Float): Float {
        if (box.size < 4) return 1f
        val left = box[0]
        val top = box[1]
        val right = box[2]
        val bottom = box[3]
        if (left < 0f || top < 0f || right > maxW || bottom > maxH) return 1f
        if (ax < 0f || ay < 0f || ax > maxW || ay > maxH) return 1f
        var limit = Float.POSITIVE_INFINITY
        if (left < ax) limit = minOf(limit, ax / (ax - left))
        if (right > ax) limit = minOf(limit, (maxW - ax) / (right - ax))
        if (top < ay) limit = minOf(limit, ay / (ay - top))
        if (bottom > ay) limit = minOf(limit, (maxH - ay) / (bottom - ay))
        return if (limit.isInfinite()) 1f else limit
    }

    fun rotationDegrees(cx: Float, cy: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx0 = (x0 - cx).toDouble()
        val dy0 = (y0 - cy).toDouble()
        val dx1 = (x1 - cx).toDouble()
        val dy1 = (y1 - cy).toDouble()
        if (dx0 * dx0 + dy0 * dy0 < 1e-6 || dx1 * dx1 + dy1 * dy1 < 1e-6) return 0f
        var deg = Math.toDegrees(atan2(dy1, dx1) - atan2(dy0, dx0)).toFloat()
        while (deg <= -180f) deg += 360f
        while (deg > 180f) deg -= 360f
        return if (deg == -0f) 0f else deg
    }

    fun snapRightAngle(deg: Float, tolerance: Float = 5f): Float {
        val nearest = round(deg / 90f) * 90f
        return if (abs(deg - nearest) <= tolerance) {
            if (nearest == 0f) 0f else nearest
        } else {
            deg
        }
    }

    fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        val dx = (x - cx).toDouble()
        val dy = (y - cy).toDouble()
        return floatArrayOf((cx + dx * c - dy * s).toFloat(), (cy + dx * s + dy * c).toFloat())
    }

    fun rotatedBounds(box: FloatArray, cx: Float, cy: Float, deg: Float): FloatArray {
        if (box.size < 4) return floatArrayOf(0f, 0f, 0f, 0f)
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        val xs = floatArrayOf(box[0], box[2], box[2], box[0])
        val ys = floatArrayOf(box[1], box[1], box[3], box[3])
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val dx = (xs[i] - cx).toDouble()
            val dy = (ys[i] - cy).toDouble()
            val rx = (cx + dx * c - dy * s).toFloat()
            val ry = (cy + dx * s + dy * c).toFloat()
            if (rx < minX) minX = rx
            if (ry < minY) minY = ry
            if (rx > maxX) maxX = rx
            if (ry > maxY) maxY = ry
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun dominantAngle(pts: FloatArray, minRatio: Float = 4f): Float? {
        val n = pts.size / 2
        if (n < 2) return null
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until n) {
            sumX += pts[i * 2]
            sumY += pts[i * 2 + 1]
        }
        val meanX = sumX / n
        val meanY = sumY / n
        var cxx = 0.0
        var cyy = 0.0
        var cxy = 0.0
        for (i in 0 until n) {
            val dx = pts[i * 2] - meanX
            val dy = pts[i * 2 + 1] - meanY
            cxx += dx * dx
            cyy += dy * dy
            cxy += dx * dy
        }
        cxx /= n
        cyy /= n
        cxy /= n
        val trace = cxx + cyy
        if (trace <= 1e-12) return null
        val diff = cxx - cyy
        val disc = sqrt(diff * diff + 4.0 * cxy * cxy)
        val l1 = 0.5 * (trace + disc)
        val l2 = 0.5 * (trace - disc)
        if (l2 > 1e-9 * l1 && l1 / l2 < minRatio) return null
        var deg = Math.toDegrees(0.5 * atan2(2.0 * cxy, diff)).toFloat()
        if (deg <= -90f) deg += 180f
        if (deg > 90f) deg -= 180f
        return if (deg == 0f) 0f else deg
    }

    fun snapRotation(delta: Float, baseAngle: Float?, tolerance: Float = 5f): FloatArray {
        val relSnap = round(delta / 90f) * 90f
        val relDist = abs(delta - relSnap)
        val relValid = relDist <= tolerance

        var absSnap = 0f
        var absDist = Float.POSITIVE_INFINITY
        var absValid = false
        if (baseAngle != null) {
            val targetTotal = round((baseAngle + delta) / 90f) * 90f
            absSnap = targetTotal - baseAngle
            absDist = abs(delta - absSnap)
            absValid = absDist <= tolerance
        }

        if (absValid && (!relValid || absDist <= relDist)) {
            return floatArrayOf(if (absSnap == 0f) 0f else absSnap, 2f)
        }
        if (relValid) {
            return floatArrayOf(if (relSnap == 0f) 0f else relSnap, 1f)
        }
        return floatArrayOf(if (delta == 0f) 0f else delta, 0f)
    }
}
