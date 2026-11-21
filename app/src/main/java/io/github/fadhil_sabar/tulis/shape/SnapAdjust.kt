package io.github.fadhil_sabar.tulis.shape

import kotlin.math.atan2
import kotlin.math.hypot

class SnapAdjust(
    val base: ShapeSpec,
    private val refX: Float,
    private val refY: Float,
    private val deadZone: Float
) {
    private val anchorX: Float
    private val anchorY: Float

    init {
        if (base.kind == ShapeKind.LINE || base.kind == ShapeKind.ARROW) {
            anchorX = base.points[0]
            anchorY = base.points[1]
        } else {
            val b = FloatArray(4)
            base.bounds(b)
            anchorX = (b[0] + b[2]) / 2f
            anchorY = (b[1] + b[3]) / 2f
        }
    }

    fun at(x: Float, y: Float): ShapeSpec {
        if (hypot(x - refX, y - refY) < deadZone) return base
        val v0x = refX - anchorX
        val v0y = refY - anchorY
        val len0 = hypot(v0x, v0y)
        if (len0 < deadZone) return base
        val v1x = x - anchorX
        val v1y = y - anchorY
        val scale = hypot(v1x, v1y) / len0
        val rotatable = base.kind != ShapeKind.RECT && base.kind != ShapeKind.ELLIPSE
        val rotation = if (rotatable) atan2(v1y, v1x) - atan2(v0y, v0x) else 0f
        return base.transformed(anchorX, anchorY, scale, rotation, 0f, 0f)
    }
}
