package io.github.fadhil_sabar.tulis.select

import io.github.fadhil_sabar.tulis.model.PageContent
import io.github.fadhil_sabar.tulis.model.TextItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class SelectionTest {
    @Test
    fun concavePolygonExcludesPointInNotch() {
        val uShape = floatArrayOf(
            0f, 0f,
            100f, 0f,
            100f, 100f,
            70f, 100f,
            70f, 30f,
            30f, 30f,
            30f, 100f,
            0f, 100f
        )
        assertFalse(Lasso.contains(uShape, 50f, 60f))
        assertTrue(Lasso.contains(uShape, 15f, 60f))
        assertTrue(Lasso.contains(uShape, 85f, 60f))
        assertTrue(Lasso.contains(uShape, 50f, 15f))
        assertFalse(Lasso.contains(uShape, 120f, 50f))
    }

    @Test
    fun threshold60PercentSelects61AndRejects59() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

        val pts59 = FloatArray(200)
        for (i in 0 until 59) {
            pts59[i * 2] = 50f
            pts59[i * 2 + 1] = 50f
        }
        for (i in 59 until 100) {
            pts59[i * 2] = 150f
            pts59[i * 2 + 1] = 150f
        }
        assertEquals(0.59f, Lasso.fractionInside(square, pts59), 0.0001f)
        assertFalse(Lasso.selects(square, pts59))

        val pts61 = FloatArray(200)
        for (i in 0 until 61) {
            pts61[i * 2] = 50f
            pts61[i * 2 + 1] = 50f
        }
        for (i in 61 until 100) {
            pts61[i * 2] = 150f
            pts61[i * 2 + 1] = 150f
        }
        assertEquals(0.61f, Lasso.fractionInside(square, pts61), 0.0001f)
        assertTrue(Lasso.selects(square, pts61))
    }

    @Test
    fun degeneratePolygonAndEmptyPointsReturnFalseOrNull() {
        val zeroPts = floatArrayOf()
        val onePt = floatArrayOf(10f, 10f)
        val twoPts = floatArrayOf(0f, 0f, 100f, 100f)
        val validPts = floatArrayOf(5f, 5f, 10f, 10f)

        assertFalse(Lasso.contains(zeroPts, 5f, 5f))
        assertFalse(Lasso.contains(onePt, 10f, 10f))
        assertFalse(Lasso.contains(twoPts, 50f, 50f))

        assertFalse(Lasso.selects(twoPts, validPts))
        assertFalse(Lasso.selects(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), zeroPts))
        assertEquals(0f, Lasso.fractionInside(twoPts, validPts), 0f)
        assertEquals(0f, Lasso.fractionInside(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), zeroPts), 0f)
        assertNull(Lasso.bounds(zeroPts))
        assertNull(Lasso.bounds(floatArrayOf(1f)))
    }

    @Test
    fun unionEmptyIsNullAndNonEmptyComputesEnclosingBox() {
        assertNull(SelectionMath.union(emptyList()))
        val box = SelectionMath.union(
            listOf(
                floatArrayOf(10f, 20f, 30f, 40f),
                floatArrayOf(-5f, 25f, 50f, 35f)
            )
        )
        assertArrayEquals(floatArrayOf(-5f, 20f, 50f, 40f), box, 0f)
    }

    @Test
    fun clampDeltaClampsAllFourSidesAndFreezesOversizedAxis() {
        val box = floatArrayOf(20f, 30f, 60f, 80f)
        assertArrayEquals(floatArrayOf(-20f, -30f), SelectionMath.clampDelta(box, -50f, -60f, 100f, 120f), 0f)
        assertArrayEquals(floatArrayOf(40f, 40f), SelectionMath.clampDelta(box, 80f, 90f, 100f, 120f), 0f)
        assertArrayEquals(floatArrayOf(10f, -5f), SelectionMath.clampDelta(box, 10f, -5f, 100f, 120f), 0f)

        val wideBox = floatArrayOf(-10f, 10f, 120f, 50f)
        assertArrayEquals(floatArrayOf(0f, 20f), SelectionMath.clampDelta(wideBox, 30f, 20f, 100f, 120f), 0f)

        val tallBox = floatArrayOf(10f, -5f, 50f, 150f)
        assertArrayEquals(floatArrayOf(15f, 0f), SelectionMath.clampDelta(tallBox, 15f, -30f, 100f, 120f), 0f)
    }

    @Test
    fun pageContentRemoveAndReplaceTextsUndoRedo() {
        val page = PageContent()
        val t0 = TextItem(10f, 20f, 24f, 0, 100f, "a")
        val t1 = TextItem(30f, 40f, 24f, 0, 100f, "b")
        val t2 = TextItem(50f, 60f, 24f, 0, 100f, "c")
        page.addText(t0)
        page.addText(t1)
        page.addText(t2)

        page.removeItems(emptyList(), emptyList())
        assertEquals(3, page.texts.size)

        page.removeItems(emptyList(), listOf(t0, t2))
        assertEquals(1, page.texts.size)
        assertSame(t1, page.texts[0])

        assertTrue(page.undo())
        assertEquals(3, page.texts.size)
        assertSame(t0, page.texts[0])
        assertSame(t1, page.texts[1])
        assertSame(t2, page.texts[2])

        assertTrue(page.redo())
        assertEquals(1, page.texts.size)
        assertSame(t1, page.texts[0])

        page.undo()
        page.replaceItems(
            emptyList(),
            emptyList(),
            listOf(t0, t2),
            listOf(floatArrayOf(10f, 20f), floatArrayOf(50f, 60f)),
            listOf(floatArrayOf(15f, 25f), floatArrayOf(55f, 65f))
        )
        assertEquals(15f, t0.x, 0f)
        assertEquals(25f, t0.y, 0f)
        assertEquals(55f, t2.x, 0f)
        assertEquals(65f, t2.y, 0f)

        assertTrue(page.undo())
        assertEquals(10f, t0.x, 0f)
        assertEquals(20f, t0.y, 0f)
        assertEquals(50f, t2.x, 0f)
        assertEquals(60f, t2.y, 0f)

        assertTrue(page.redo())
        assertEquals(15f, t0.x, 0f)
        assertEquals(65f, t2.y, 0f)
    }

    @Test
    fun uniformScaleProjectsAndClampsToMinScale() {
        assertEquals(2f, SelectionMath.uniformScale(0f, 0f, 100f, 100f, 200f, 200f), 1e-4f)
        assertEquals(0.5f, SelectionMath.uniformScale(0f, 0f, 100f, 100f, 50f, 50f), 1e-4f)
        assertEquals(0.1f, SelectionMath.uniformScale(0f, 0f, 100f, 100f, -50f, -50f), 1e-4f)
        assertEquals(1f, SelectionMath.uniformScale(10f, 10f, 10f, 10f, 50f, 50f), 1e-4f)
        assertEquals(1f, SelectionMath.uniformScale(0f, 0f, 100f, 100f, 200f, 0f), 1e-4f)
    }

    @Test
    fun maxScaleInsideRespectsEdgesCornersAndOutsideBox() {
        val boxRight = floatArrayOf(100f, 100f, 300f, 200f)
        assertEquals(2f, SelectionMath.maxScaleInside(100f, 100f, boxRight, 500f, 1000f), 1e-4f)

        val boxBottom = floatArrayOf(100f, 100f, 200f, 300f)
        assertEquals(2f, SelectionMath.maxScaleInside(100f, 100f, boxBottom, 1000f, 500f), 1e-4f)

        val cornerBox = floatArrayOf(0f, 0f, 100f, 50f)
        assertEquals(4f, SelectionMath.maxScaleInside(0f, 0f, cornerBox, 400f, 300f), 1e-4f)

        val cornerBottomRight = floatArrayOf(900f, 800f, 1000f, 1000f)
        assertEquals(5f, SelectionMath.maxScaleInside(1000f, 1000f, cornerBottomRight, 1000f, 1000f), 1e-4f)

        assertEquals(1f, SelectionMath.maxScaleInside(0f, 0f, floatArrayOf(-10f, 10f, 100f, 100f), 500f, 500f), 1e-4f)
        assertEquals(1f, SelectionMath.maxScaleInside(10f, 10f, floatArrayOf(10f, 10f, 600f, 100f), 500f, 500f), 1e-4f)
    }

    @Test
    fun pageContentReplaceItemsWithFourValueTextStateRestoresSizeAndWidth() {
        val page = PageContent()
        val t0 = TextItem(10f, 20f, 24f, 0, 100f, "a")
        val t1 = TextItem(100f, 100f, 18f, 0, 80f, "b")
        page.addText(t0)
        page.addText(t1)

        page.replaceItems(
            emptyList(),
            emptyList(),
            listOf(t0, t1),
            listOf(
                floatArrayOf(10f, 20f, 24f, 100f),
                floatArrayOf(100f, 100f, 18f, 80f)
            ),
            listOf(
                floatArrayOf(10f, 20f, 48f, 200f),
                floatArrayOf(190f, 180f, 36f, 160f)
            )
        )

        assertEquals(10f, t0.x, 0f)
        assertEquals(20f, t0.y, 0f)
        assertEquals(48f, t0.sizeWorld, 0f)
        assertEquals(200f, t0.widthWorld, 0f)
        assertEquals(190f, t1.x, 0f)
        assertEquals(180f, t1.y, 0f)
        assertEquals(36f, t1.sizeWorld, 0f)
        assertEquals(160f, t1.widthWorld, 0f)

        assertTrue(page.undo())
        assertEquals(10f, t0.x, 0f)
        assertEquals(20f, t0.y, 0f)
        assertEquals(24f, t0.sizeWorld, 0f)
        assertEquals(100f, t0.widthWorld, 0f)
        assertEquals(100f, t1.x, 0f)
        assertEquals(100f, t1.y, 0f)
        assertEquals(18f, t1.sizeWorld, 0f)
        assertEquals(80f, t1.widthWorld, 0f)

        assertTrue(page.redo())
        assertEquals(48f, t0.sizeWorld, 0f)
        assertEquals(200f, t0.widthWorld, 0f)
        assertEquals(190f, t1.x, 0f)
        assertEquals(36f, t1.sizeWorld, 0f)
    }

    @Test
    fun rotationDegreesClockwiseAndCrossing180Boundary() {
        assertEquals(90f, SelectionMath.rotationDegrees(0f, 0f, 0f, -10f, 10f, 0f), 1e-4f)
        assertEquals(90f, SelectionMath.rotationDegrees(10f, 20f, 10f, 30f, 0f, 20f), 1e-4f)

        val crossCcw = SelectionMath.rotationDegrees(0f, 0f, -10f, -1f, -10f, 1f)
        assertTrue(crossCcw < 0f && crossCcw > -20f)

        val crossCw = SelectionMath.rotationDegrees(0f, 0f, -10f, 1f, -10f, -1f)
        assertTrue(crossCw > 0f && crossCw < 20f)
    }

    @Test
    fun snapRightAngleSnapsWithinTolerance() {
        assertEquals(90f, SelectionMath.snapRightAngle(87f), 0f)
        assertEquals(84f, SelectionMath.snapRightAngle(84f), 0f)
        assertEquals(0f, SelectionMath.snapRightAngle(-3f), 0f)
        assertEquals(-90f, SelectionMath.snapRightAngle(-94f), 0f)
        assertEquals(180f, SelectionMath.snapRightAngle(176f), 0f)
    }

    @Test
    fun rotatedBoundsSwapsWidthAndHeightAt90Degrees() {
        val rect = floatArrayOf(10f, 20f, 50f, 40f)
        val rotated = SelectionMath.rotatedBounds(rect, 30f, 30f, 90f)
        assertEquals(20f, rotated[0], 1e-4f)
        assertEquals(10f, rotated[1], 1e-4f)
        assertEquals(40f, rotated[2], 1e-4f)
        assertEquals(50f, rotated[3], 1e-4f)
    }

    @Test
    fun rotatePoint180DegreesReflectsAcrossCenter() {
        val pt = SelectionMath.rotatePoint(15f, 25f, 10f, 20f, 180f)
        assertEquals(5f, pt[0], 1e-4f)
        assertEquals(15f, pt[1], 1e-4f)
    }

    @Test
    fun dominantAngleHandlesLinesCirclesAndDegenerateInputs() {
        assertNull(SelectionMath.dominantAngle(floatArrayOf()))
        assertNull(SelectionMath.dominantAngle(floatArrayOf(10f, 20f)))
        assertNull(SelectionMath.dominantAngle(floatArrayOf(10f, 20f, 10f, 20f)))

        val horizontal = floatArrayOf(0f, 50f, 40f, 50f, 100f, 50f)
        assertEquals(0f, SelectionMath.dominantAngle(horizontal)!!, 1e-3f)

        val slope3 = tan(Math.toRadians(3.0)).toFloat()
        val tilted3 = floatArrayOf(0f, 0f, 50f, 50f * slope3, 100f, 100f * slope3)
        assertEquals(3f, SelectionMath.dominantAngle(tilted3)!!, 1e-3f)

        val vertical = floatArrayOf(30f, 0f, 30f, 50f, 30f, 120f)
        assertEquals(90f, SelectionMath.dominantAngle(vertical)!!, 1e-3f)

        val slopeNeg30 = tan(Math.toRadians(-30.0)).toFloat()
        val tiltedNeg30 = floatArrayOf(0f, 0f, 50f, 50f * slopeNeg30, 100f, 100f * slopeNeg30)
        assertEquals(-30f, SelectionMath.dominantAngle(tiltedNeg30)!!, 1e-3f)

        val circle = FloatArray(16)
        for (i in 0 until 8) {
            val a = Math.PI * 2.0 * i / 8.0
            circle[i * 2] = (50.0 * cos(a)).toFloat()
            circle[i * 2 + 1] = (50.0 * sin(a)).toFloat()
        }
        assertNull(SelectionMath.dominantAngle(circle))
    }

    @Test
    fun snapRotationPrioritizesAbsoluteAndFallsBackToRelativeOrRaw() {
        assertArrayEquals(floatArrayOf(87f, 2f), SelectionMath.snapRotation(85f, 3f), 1e-4f)
        assertArrayEquals(floatArrayOf(-3f, 2f), SelectionMath.snapRotation(-2f, 3f), 1e-4f)
        assertArrayEquals(floatArrayOf(90f, 1f), SelectionMath.snapRotation(88f, null), 1e-4f)
        assertArrayEquals(floatArrayOf(40f, 0f), SelectionMath.snapRotation(40f, 3f), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 2f), SelectionMath.snapRotation(2f, 0f), 1e-4f)
    }
}
