package io.github.fadhil_sabar.tulis.select

object Lasso {
    fun contains(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    fun bounds(pts: FloatArray): FloatArray? {
        val n = pts.size / 2
        if (n == 0) return null
        var minX = pts[0]
        var minY = pts[1]
        var maxX = minX
        var maxY = minY
        for (i in 1 until n) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun fractionInside(poly: FloatArray, pts: FloatArray): Float {
        val count = pts.size / 2
        if (count == 0 || poly.size / 2 < 3) return 0f
        var insideCount = 0
        for (i in 0 until count) {
            if (contains(poly, pts[i * 2], pts[i * 2 + 1])) {
                insideCount++
            }
        }
        return insideCount.toFloat() / count.toFloat()
    }

    fun selects(poly: FloatArray, pts: FloatArray, threshold: Float = 0.6f): Boolean {
        if (poly.size / 2 < 3 || pts.size < 2) return false
        val polyBox = bounds(poly) ?: return false
        val ptsBox = bounds(pts) ?: return false
        if (ptsBox[2] < polyBox[0] || ptsBox[0] > polyBox[2] ||
            ptsBox[3] < polyBox[1] || ptsBox[1] > polyBox[3]
        ) {
            return false
        }
        return fractionInside(poly, pts) >= threshold
    }
}
