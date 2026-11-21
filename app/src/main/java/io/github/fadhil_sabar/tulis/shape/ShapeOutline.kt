package io.github.fadhil_sabar.tulis.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object ShapeOutline {
    private const val ARROW_ANGLE = 0.4887f
    private const val ARROW_MIN_BARB = 10f
    private const val ARROW_MAX_BARB = 60f
    private const val ARROW_BARB_RATIO = 0.25f
    private const val ELLIPSE_MIN_SEGMENTS = 64

    fun outline(spec: ShapeSpec, stepPx: Float = 4f): FloatArray {
        val step = if (stepPx > 0f) stepPx else 4f
        val p = spec.points
        val line = Polyline(p.size * 4)
        when (spec.kind) {
            ShapeKind.LINE -> {
                line.add(p[0], p[1])
                line.segment(p[0], p[1], p[2], p[3], step)
            }
            ShapeKind.ARROW -> arrow(line, p, step)
            ShapeKind.RECT, ShapeKind.TRIANGLE -> closedPolygon(line, p, step)
            ShapeKind.ELLIPSE -> ellipse(line, p, step)
        }
        return line.toArray()
    }

    private fun closedPolygon(line: Polyline, p: FloatArray, step: Float) {
        val count = p.size / 2
        line.add(p[0], p[1])
        for (i in 0 until count) {
            val j = (i + 1) % count
            line.segment(p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1], step)
        }
    }

    private fun ellipse(line: Polyline, p: FloatArray, step: Float) {
        val cx = p[0]
        val cy = p[1]
        val rx = abs(p[2])
        val ry = abs(p[3])
        val rot = p[4]
        val a = 3f * (rx + ry)
        val b = sqrt((3f * rx + ry) * (rx + 3f * ry))
        val circumference = PI.toFloat() * (a - b)
        val count = max(ELLIPSE_MIN_SEGMENTS, ceil(circumference / step).toInt())
        val c = cos(rot)
        val s = sin(rot)
        var firstX = 0f
        var firstY = 0f
        for (i in 0 until count) {
            val t = 2.0 * PI * i / count
            val ex = (rx * cos(t)).toFloat()
            val ey = (ry * sin(t)).toFloat()
            val x = cx + ex * c - ey * s
            val y = cy + ex * s + ey * c
            if (i == 0) {
                firstX = x
                firstY = y
            }
            line.add(x, y)
        }
        line.add(firstX, firstY)
    }

    private fun arrow(line: Polyline, p: FloatArray, step: Float) {
        val x0 = p[0]
        val y0 = p[1]
        val tx = p[2]
        val ty = p[3]
        val len = kotlin.math.hypot(tx - x0, ty - y0)
        line.add(x0, y0)
        if (len <= 0f) {
            line.segment(x0, y0, tx, ty, step)
            return
        }
        val ux = (x0 - tx) / len
        val uy = (y0 - ty) / len
        val barb = min(ARROW_MAX_BARB, max(ARROW_MIN_BARB, len * ARROW_BARB_RATIO))
        val b1x = tx + barb * (ux * cos(ARROW_ANGLE) - uy * sin(ARROW_ANGLE))
        val b1y = ty + barb * (ux * sin(ARROW_ANGLE) + uy * cos(ARROW_ANGLE))
        val b2x = tx + barb * (ux * cos(-ARROW_ANGLE) - uy * sin(-ARROW_ANGLE))
        val b2y = ty + barb * (ux * sin(-ARROW_ANGLE) + uy * cos(-ARROW_ANGLE))
        line.segment(x0, y0, tx, ty, step)
        line.segment(tx, ty, b1x, b1y, step)
        line.segment(b1x, b1y, tx, ty, step)
        line.segment(tx, ty, b2x, b2y, step)
    }

    private class Polyline(capacity: Int) {
        private var data = FloatArray(max(capacity, 8))
        private var size = 0

        fun add(x: Float, y: Float) {
            if (size + 2 > data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size] = x
            data[size + 1] = y
            size += 2
        }

        fun segment(x0: Float, y0: Float, x1: Float, y1: Float, step: Float) {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = max(1, ceil(len / step).toInt())
            for (i in 1..n) {
                val t = i.toFloat() / n
                add(x0 * (1f - t) + x1 * t, y0 * (1f - t) + y1 * t)
            }
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
