package io.github.fadhil_sabar.tulis.shape

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ShapeRecognizer {
    private const val MIN_POINTS = 4
    private const val CLOSE_RATIO = 0.2f
    private const val CLOSED_EPSILON_FACTOR = 1.5f
    private const val MERGE_FACTOR = 2f
    private const val MAX_STRAIGHT_ANGLE = 160.0
    private const val AXIS_SNAP_DEG = 12.0
    private const val ARROW_MIN_SHAFT_RATIO = 0.6f
    private const val ARROW_MAX_BARB_RATIO = 0.6f
    private const val ARROW_TIP_FACTOR = 1.5f
    private const val ARROW_MIN_ANGLE = 10.0
    private const val ARROW_MAX_ANGLE = 75.0
    private const val ARROW_MAX_VERTICES = 5
    private const val POLY_AVG_FACTOR = 0.3f
    private const val POLY_MAX_FACTOR = 1f
    private const val ELLIPSE_SNAP_RATIO = 0.9f
    private const val ELLIPSE_MAX_MEAN_ERROR = 0.1f
    private const val ELLIPSE_MAX_ERROR = 0.3f

    private class Vertex(val x: Float, val y: Float)

    fun trimTrailingRest(pts: FloatArray, restX: Float, restY: Float, radius: Float): FloatArray {
        var n = pts.size / 2
        while (n > 1 &&
            hypot(pts[2 * (n - 1)] - restX, pts[2 * (n - 1) + 1] - restY) <= radius &&
            hypot(pts[2 * (n - 2)] - restX, pts[2 * (n - 2) + 1] - restY) <= radius
        ) {
            n--
        }
        return pts.copyOf(2 * n)
    }

    fun recognize(pts: FloatArray, tolerance: Float): ShapeSpec? {
        val n = pts.size / 2
        if (n < MIN_POINTS || tolerance <= 0f) return null
        val pathLen = pathLength(pts, n)
        if (pathLen <= 3f * tolerance) return null
        val gap = distance(pts, 0, n - 1)
        return if (gap < CLOSE_RATIO * pathLen) {
            recognizeClosed(pts, n, tolerance)
        } else {
            recognizeOpen(pts, n, tolerance, pathLen)
        }
    }

    private fun recognizeOpen(pts: FloatArray, n: Int, tol: Float, pathLen: Float): ShapeSpec? {
        val keep = BooleanArray(n)
        simplify(pts, 0, n - 1, tol, keep)
        val verts = cleanPath(collect(pts, keep, 0, n - 1))
        arrow(verts, pts, n, tol, pathLen)?.let { return it }
        if (chordDeviation(pts, n) < tol) {
            return ShapeSpec(ShapeKind.LINE, floatArrayOf(pts[0], pts[1], pts[2 * (n - 1)], pts[2 * (n - 1) + 1]))
        }
        return null
    }

    private fun arrow(verts: List<Vertex>, pts: FloatArray, n: Int, tol: Float, pathLen: Float): ShapeSpec? {
        if (verts.size !in 3..ARROW_MAX_VERTICES) return null
        val start = verts[0]
        val tip = verts[1]
        val shaft = hypot(tip.x - start.x, tip.y - start.y)
        if (shaft < 2f * tol || shaft < ARROW_MIN_SHAFT_RATIO * pathLen) return null
        val ux = (start.x - tip.x) / shaft
        val uy = (start.y - tip.y) / shaft
        var barbs = 0
        for (k in 2 until verts.size) {
            val dx = verts[k].x - tip.x
            val dy = verts[k].y - tip.y
            val d = hypot(dx, dy)
            if (d < ARROW_TIP_FACTOR * tol) continue
            val along = dx * ux + dy * uy
            val perp = abs(dx * uy - dy * ux)
            val angle = Math.toDegrees(atan2(perp.toDouble(), along.toDouble()))
            if (along <= 0f || angle < ARROW_MIN_ANGLE || angle > ARROW_MAX_ANGLE) return null
            if (d > ARROW_MAX_BARB_RATIO * shaft) return null
            barbs++
        }
        if (barbs == 0) return null
        return ShapeSpec(ShapeKind.ARROW, floatArrayOf(start.x, start.y, tip.x, tip.y))
    }

    private fun recognizeClosed(pts: FloatArray, n: Int, tol: Float): ShapeSpec? {
        val ring = FloatArray(2 * (n + 1))
        pts.copyInto(ring, 0, 0, 2 * n)
        ring[2 * n] = pts[0]
        ring[2 * n + 1] = pts[1]
        var far = 0
        var farDist = -1f
        for (i in 0 until n) {
            val d = distance(pts, 0, i)
            if (d > farDist) {
                farDist = d
                far = i
            }
        }
        val keep = BooleanArray(n + 1)
        val epsilon = CLOSED_EPSILON_FACTOR * tol
        simplify(ring, 0, far, epsilon, keep)
        simplify(ring, far, n, epsilon, keep)
        val poly = cleanPolygon(collect(ring, keep, 0, n), tol)
        if ((poly.size == 3 || poly.size == 4) && polygonFits(pts, n, poly, tol)) {
            return if (poly.size == 3) {
                ShapeSpec(ShapeKind.TRIANGLE, flatten(poly))
            } else {
                ShapeSpec(ShapeKind.RECT, rectangle(poly))
            }
        }
        return ellipse(pts, n, tol)
    }

    private fun rectangle(poly: List<Vertex>): FloatArray {
        var axisAligned = true
        for (k in 0 until 4) {
            val a = poly[k]
            val b = poly[(k + 1) % 4]
            val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            val m = ((deg % 90.0) + 90.0) % 90.0
            if (min(m, 90.0 - m) > AXIS_SNAP_DEG) {
                axisAligned = false
            }
        }
        if (!axisAligned) return flatten(poly)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (v in poly) {
            left = min(left, v.x)
            right = max(right, v.x)
            top = min(top, v.y)
            bottom = max(bottom, v.y)
        }
        return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
    }

    private fun ellipse(pts: FloatArray, n: Int, tol: Float): ShapeSpec? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, pts[2 * i])
            maxX = max(maxX, pts[2 * i])
            minY = min(minY, pts[2 * i + 1])
            maxY = max(maxY, pts[2 * i + 1])
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        var rx = (maxX - minX) / 2f
        var ry = (maxY - minY) / 2f
        if (rx <= tol / 2f || ry <= tol / 2f) return null
        if (min(rx, ry) / max(rx, ry) > ELLIPSE_SNAP_RATIO) {
            val avg = (rx + ry) / 2f
            rx = avg
            ry = avg
        }
        var sum = 0.0
        var worst = 0.0
        for (i in 0 until n) {
            val ex = (pts[2 * i] - cx) / rx
            val ey = (pts[2 * i + 1] - cy) / ry
            val err = abs(sqrt((ex * ex + ey * ey).toDouble()) - 1.0)
            sum += err
            worst = max(worst, err)
        }
        if (sum / n > ELLIPSE_MAX_MEAN_ERROR || worst > ELLIPSE_MAX_ERROR) return null
        return ShapeSpec(ShapeKind.ELLIPSE, floatArrayOf(cx, cy, rx, ry, 0f))
    }

    private fun polygonFits(pts: FloatArray, n: Int, poly: List<Vertex>, tol: Float): Boolean {
        var sum = 0.0
        var worst = 0f
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (k in poly.indices) {
                val a = poly[k]
                val b = poly[(k + 1) % poly.size]
                best = min(best, segmentDistance(pts[2 * i], pts[2 * i + 1], a.x, a.y, b.x, b.y))
            }
            sum += best
            worst = max(worst, best)
        }
        return sum / n < POLY_AVG_FACTOR * tol && worst < POLY_MAX_FACTOR * tol
    }

    private fun cleanPolygon(input: List<Vertex>, tol: Float): List<Vertex> {
        val v = input.toMutableList()
        var changed = true
        while (changed && v.size > 2) {
            changed = false
            for (i in v.indices) {
                val j = (i + 1) % v.size
                if (hypot(v[i].x - v[j].x, v[i].y - v[j].y) < MERGE_FACTOR * tol) {
                    v.removeAt(j)
                    changed = true
                    break
                }
            }
            if (changed) continue
            for (i in v.indices) {
                val prev = v[(i - 1 + v.size) % v.size]
                val next = v[(i + 1) % v.size]
                if (interiorAngle(prev, v[i], next) > MAX_STRAIGHT_ANGLE) {
                    v.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        return v
    }

    private fun cleanPath(input: List<Vertex>): List<Vertex> {
        val v = input.toMutableList()
        var i = 1
        while (i < v.size - 1) {
            if (interiorAngle(v[i - 1], v[i], v[i + 1]) > MAX_STRAIGHT_ANGLE) {
                v.removeAt(i)
            } else {
                i++
            }
        }
        return v
    }

    private fun interiorAngle(prev: Vertex, cur: Vertex, next: Vertex): Double {
        val ax = (prev.x - cur.x).toDouble()
        val ay = (prev.y - cur.y).toDouble()
        val bx = (next.x - cur.x).toDouble()
        val by = (next.y - cur.y).toDouble()
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la == 0.0 || lb == 0.0) return 180.0
        val c = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun simplify(pts: FloatArray, first: Int, last: Int, epsilon: Float, keep: BooleanArray) {
        keep[first] = true
        keep[last] = true
        val stack = ArrayList<Int>()
        stack.add(first)
        stack.add(last)
        while (stack.isNotEmpty()) {
            val b = stack.removeAt(stack.size - 1)
            val a = stack.removeAt(stack.size - 1)
            var maxD = -1f
            var idx = -1
            for (i in a + 1 until b) {
                val d = lineDistance(pts, i, a, b)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > epsilon) {
                keep[idx] = true
                stack.add(a)
                stack.add(idx)
                stack.add(idx)
                stack.add(b)
            }
        }
    }

    private fun collect(pts: FloatArray, keep: BooleanArray, from: Int, to: Int): List<Vertex> {
        val out = ArrayList<Vertex>()
        for (i in from..to) {
            if (keep[i]) out.add(Vertex(pts[2 * i], pts[2 * i + 1]))
        }
        return out
    }

    private fun flatten(poly: List<Vertex>): FloatArray {
        val out = FloatArray(poly.size * 2)
        for (i in poly.indices) {
            out[2 * i] = poly[i].x
            out[2 * i + 1] = poly[i].y
        }
        return out
    }

    private fun chordDeviation(pts: FloatArray, n: Int): Float {
        var worst = 0f
        for (i in 0 until n) {
            worst = max(worst, lineDistance(pts, i, 0, n - 1))
        }
        return worst
    }

    private fun lineDistance(pts: FloatArray, i: Int, a: Int, b: Int): Float {
        val ax = pts[2 * a]
        val ay = pts[2 * a + 1]
        val bx = pts[2 * b]
        val by = pts[2 * b + 1]
        val px = pts[2 * i]
        val py = pts[2 * i + 1]
        val len = hypot(bx - ax, by - ay)
        if (len < 1e-6f) return hypot(px - ax, py - ay)
        return abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun distance(pts: FloatArray, a: Int, b: Int): Float =
        hypot(pts[2 * b] - pts[2 * a], pts[2 * b + 1] - pts[2 * a + 1])

    private fun pathLength(pts: FloatArray, n: Int): Float {
        var total = 0f
        for (i in 1 until n) {
            total += distance(pts, i - 1, i)
        }
        return total
    }
}
