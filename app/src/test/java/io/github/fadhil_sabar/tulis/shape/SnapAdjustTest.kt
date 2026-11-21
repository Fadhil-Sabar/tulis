package io.github.fadhil_sabar.tulis.shape

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SnapAdjustTest {
    private val rect = ShapeSpec(ShapeKind.RECT, floatArrayOf(100f, 100f, 300f, 100f, 300f, 200f, 100f, 200f))

    private fun width(s: ShapeSpec): Float {
        val b = FloatArray(4)
        s.bounds(b)
        return b[2] - b[0]
    }

    @Test
    fun closedShapeIgnoresJitterNearRestPoint() {
        val adjust = SnapAdjust(rect, 104f, 106f, 13f)
        assertSame(rect, adjust.at(110f, 112f))
        assertSame(rect, adjust.at(96f, 100f))
    }

    @Test
    fun closedShapeScalesFromCenterNotFromStrokeStart() {
        val adjust = SnapAdjust(rect, 100f, 100f, 13f)
        val grown = adjust.at(0f, 50f)
        assertEquals(400f, width(grown), 0.5f)
        val b = FloatArray(4)
        grown.bounds(b)
        assertEquals(200f, (b[0] + b[2]) / 2f, 0.5f)
        assertEquals(150f, (b[1] + b[3]) / 2f, 0.5f)
    }

    @Test
    fun smallMoveAfterSnapDoesNotCollapseClosedShape() {
        val adjust = SnapAdjust(rect, 102f, 104f, 13f)
        val moved = adjust.at(118f, 112f)
        val w = width(moved)
        assert(w > 150f && w < 250f) { "lebar $w" }
    }

    @Test
    fun lineExtendsFromItsStart() {
        val line = ShapeSpec(ShapeKind.LINE, floatArrayOf(0f, 0f, 100f, 0f))
        val adjust = SnapAdjust(line, 100f, 0f, 13f)
        assertSame(line, adjust.at(105f, 3f))
        assertArrayEquals(floatArrayOf(0f, 0f, 200f, 0f), adjust.at(200f, 0f).points, 0.01f)
        val rotated = adjust.at(0f, 100f).points
        assertEquals(0f, rotated[2], 0.01f)
        assertEquals(100f, rotated[3], 0.01f)
    }
}
