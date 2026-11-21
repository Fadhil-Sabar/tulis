package io.github.fadhil_sabar.tulis.shape

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind { LINE, ARROW, RECT, ELLIPSE, TRIANGLE }

class ShapeSpec(val kind: ShapeKind, val points: FloatArray) {

    fun copy(): ShapeSpec = ShapeSpec(kind, points.copyOf())

    fun transformed(ax: Float, ay: Float, scale: Float, rotation: Float, dx: Float, dy: Float): ShapeSpec {
        val out = points.copyOf()
        val c = cos(rotation) * scale
        val s = sin(rotation) * scale
        for (p in 0 until vertexCount()) {
            val rx = out[2 * p] - ax
            val ry = out[2 * p + 1] - ay
            out[2 * p] = ax + rx * c - ry * s + dx
            out[2 * p + 1] = ay + rx * s + ry * c + dy
        }
        if (kind == ShapeKind.ELLIPSE) {
            out[2] = out[2] * abs(scale)
            out[3] = out[3] * abs(scale)
            out[4] = out[4] + rotation
        }
        return ShapeSpec(kind, out)
    }

    fun scaledXY(ax: Float, ay: Float, sx: Float, sy: Float): ShapeSpec {
        val out = points.copyOf()
        for (p in 0 until vertexCount()) {
            out[2 * p] = ax + (out[2 * p] - ax) * sx
            out[2 * p + 1] = ay + (out[2 * p + 1] - ay) * sy
        }
        if (kind == ShapeKind.ELLIPSE) {
            out[2] = out[2] * abs(sx)
            out[3] = out[3] * abs(sy)
        }
        return ShapeSpec(kind, out)
    }

    fun translated(dx: Float, dy: Float): ShapeSpec {
        val out = points.copyOf()
        for (p in 0 until vertexCount()) {
            out[2 * p] += dx
            out[2 * p + 1] += dy
        }
        return ShapeSpec(kind, out)
    }

    fun bounds(out: FloatArray) {
        if (kind == ShapeKind.ELLIPSE) {
            val cx = points[0]
            val cy = points[1]
            val rx = points[2]
            val ry = points[3]
            val c = cos(points[4])
            val s = sin(points[4])
            val hx = sqrt(rx * rx * c * c + ry * ry * s * s)
            val hy = sqrt(rx * rx * s * s + ry * ry * c * c)
            out[0] = cx - hx
            out[1] = cy - hy
            out[2] = cx + hx
            out[3] = cy + hy
            return
        }
        val source = if (kind == ShapeKind.ARROW) ShapeOutline.outline(this) else points
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < source.size) {
            left = min(left, source[i])
            right = max(right, source[i])
            top = min(top, source[i + 1])
            bottom = max(bottom, source[i + 1])
            i += 2
        }
        out[0] = left
        out[1] = top
        out[2] = right
        out[3] = bottom
    }

    private fun vertexCount(): Int = if (kind == ShapeKind.ELLIPSE) 1 else points.size / 2

    companion object {
        fun fromDrag(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float): ShapeSpec {
            val left = min(x0, x1)
            val right = max(x0, x1)
            val top = min(y0, y1)
            val bottom = max(y0, y1)
            val points = when (kind) {
                ShapeKind.LINE, ShapeKind.ARROW -> floatArrayOf(x0, y0, x1, y1)
                ShapeKind.RECT -> floatArrayOf(left, top, right, top, right, bottom, left, bottom)
                ShapeKind.TRIANGLE -> floatArrayOf((left + right) / 2f, top, left, bottom, right, bottom)
                ShapeKind.ELLIPSE -> floatArrayOf(
                    (left + right) / 2f,
                    (top + bottom) / 2f,
                    (right - left) / 2f,
                    (bottom - top) / 2f,
                    0f
                )
            }
            return ShapeSpec(kind, points)
        }
    }
}
