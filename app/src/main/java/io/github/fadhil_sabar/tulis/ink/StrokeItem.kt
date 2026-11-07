package io.github.fadhil_sabar.tulis.ink

import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.strokes.Stroke
import io.github.fadhil_sabar.tulis.model.BrushKind

class StrokeItem(
    val stroke: Stroke,
    val brushKind: BrushKind,
    val colorArgb: Int,
    val sizePx: Float,
    val strokeToWorld: Matrix,
    var encodedInputs: ByteArray? = null
) {
    private var cachedPoints: FloatArray? = null
    private var cachedWorldBounds: RectF? = null
    private var hasCalculatedBounds = false
    private val scratchBounds = RectF()

    fun worldBounds(out: RectF): Boolean {
        if (!hasCalculatedBounds) {
            val box = stroke.shape.computeBoundingBox()
            if (box == null) {
                cachedWorldBounds = null
            } else {
                val b = RectF(box.xMin, box.yMin, box.xMax, box.yMax)
                val mapped = RectF()
                strokeToWorld.mapRect(mapped, b)
                cachedWorldBounds = mapped
            }
            hasCalculatedBounds = true
        }

        val cached = cachedWorldBounds ?: return false
        out.set(cached)
        return true
    }

    fun worldPoints(): FloatArray {
        val cached = cachedPoints
        if (cached != null) return cached

        val inputs = stroke.inputs
        val count = inputs.size
        val pts = FloatArray(count * 2)
        val scratchInput = androidx.ink.strokes.StrokeInput()
        for (i in 0 until count) {
            inputs.populate(i, scratchInput)
            pts[i * 2] = scratchInput.x
            pts[i * 2 + 1] = scratchInput.y
        }
        strokeToWorld.mapPoints(pts)
        cachedPoints = pts
        return pts
    }

    fun hitTest(wx: Float, wy: Float, radiusWorld: Float): Boolean {
        if (!worldBounds(scratchBounds)) return false
        if (wx < scratchBounds.left - radiusWorld || wx > scratchBounds.right + radiusWorld ||
            wy < scratchBounds.top - radiusWorld || wy > scratchBounds.bottom + radiusWorld
        ) {
            return false
        }

        val pts = worldPoints()
        val count = pts.size / 2
        if (count == 0) return false

        val r2 = radiusWorld * radiusWorld
        if (count == 1) {
            val dx = wx - pts[0]
            val dy = wy - pts[1]
            return dx * dx + dy * dy <= r2
        }

        for (i in 0 until count - 1) {
            val x1 = pts[i * 2]
            val y1 = pts[i * 2 + 1]
            val x2 = pts[(i + 1) * 2]
            val y2 = pts[(i + 1) * 2 + 1]

            val segDx = x2 - x1
            val segDy = y2 - y1
            val segLen2 = segDx * segDx + segDy * segDy

            val dist2 = if (segLen2 <= 1e-6f) {
                val dx = wx - x1
                val dy = wy - y1
                dx * dx + dy * dy
            } else {
                val t = ((wx - x1) * segDx + (wy - y1) * segDy) / segLen2
                val clampedT = if (t < 0f) 0f else if (t > 1f) 1f else t
                val projX = x1 + clampedT * segDx
                val projY = y1 + clampedT * segDy
                val dx = wx - projX
                val dy = wy - projY
                dx * dx + dy * dy
            }

            if (dist2 <= r2) return true
        }

        return false
    }
}
