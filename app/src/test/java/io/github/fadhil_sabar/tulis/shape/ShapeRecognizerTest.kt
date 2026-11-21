package io.github.fadhil_sabar.tulis.shape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {
    private val tol = 30f

    private class Trace(private val rnd: Random, private val amp: Float) {
        private val out = ArrayList<Float>()

        fun add(x: Float, y: Float) {
            out.add(x + jitter())
            out.add(y + jitter())
        }

        fun segment(x0: Float, y0: Float, x1: Float, y1: Float, step: Float = 8f) {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = kotlin.math.ceil(len / step).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val t = i.toFloat() / n
                add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }

        fun end(x: Float, y: Float) {
            add(x, y)
        }

        fun toArray(): FloatArray = out.toFloatArray()

        private fun jitter(): Float = (rnd.nextFloat() * 2f - 1f) * amp
    }

    private fun trace(seed: Long, amp: Float = 3f, block: Trace.() -> Unit): FloatArray {
        val t = Trace(Random(seed), amp)
        t.block()
        return t.toArray()
    }

    private fun recognized(pts: FloatArray): ShapeSpec {
        val spec = ShapeRecognizer.recognize(pts, tol)
        assertNotNull(spec)
        return spec!!
    }

    private fun assertNear(expected: Float, actual: Float, delta: Float, message: String) {
        assertTrue("$message expected=$expected actual=$actual", abs(expected - actual) <= delta)
    }

    @Test
    fun slightlyWavyLineIsLine() {
        val pts = trace(1L, amp = 2f) {
            for (i in 0..60) {
                val x = 100f + i * 5f
                val y = 300f + 6f * sin(x / 20f)
                add(x, y)
            }
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.LINE, spec.kind)
        assertNear(100f, spec.points[0], 8f, "start x")
        assertNear(300f, spec.points[1], 8f, "start y")
        assertNear(400f, spec.points[2], 8f, "end x")
        assertNear(300f, spec.points[3], 8f, "end y")
    }

    @Test
    fun arrowWithSingleBarbIsArrowWithTipAtShaftEnd() {
        val pts = trace(2L) {
            segment(100f, 300f, 400f, 300f)
            segment(400f, 300f, 347f, 328f)
            end(347f, 328f)
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.ARROW, spec.kind)
        assertNear(400f, spec.points[2], 12f, "tip x")
        assertNear(300f, spec.points[3], 12f, "tip y")
        assertNear(100f, spec.points[0], 12f, "tail x")
    }

    @Test
    fun arrowWithTwoBarbsIsArrowWithTipAtShaftEnd() {
        val pts = trace(3L) {
            segment(100f, 300f, 400f, 300f)
            segment(400f, 300f, 347f, 328f)
            segment(347f, 328f, 400f, 300f)
            segment(400f, 300f, 347f, 272f)
            end(347f, 272f)
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.ARROW, spec.kind)
        assertNear(400f, spec.points[2], 12f, "tip x")
        assertNear(300f, spec.points[3], 12f, "tip y")
    }

    @Test
    fun arrowWithKinkNearTipIsStillArrow() {
        val pts = floatArrayOf(853f, 1403f, 898f, 1363f, 954f, 1334f, 1001f, 1296f, 1046f, 1256f, 1104f, 1229f, 1151f, 1191f, 1204f, 1159f, 1248f, 1118f, 1305f, 1090f, 1351f, 1051f, 1345f, 1052f, 1338f, 1052f, 1330f, 1051f, 1321f, 1049f, 1322f, 1057f, 1307f, 1049f, 1304f, 1053f, 1297f, 1053f, 1288f, 1051f, 1286f, 1056f, 1295f, 1058f, 1295f, 1051f, 1301f, 1050f, 1316f, 1058f, 1317f, 1052f, 1322f, 1050f, 1329f, 1050f, 1335f, 1049f, 1348f, 1055f, 1345f, 1045f, 1346f, 1054f, 1343f, 1058f, 1342f, 1065f, 1346f, 1076f, 1345f, 1083f, 1340f, 1085f, 1338f, 1091f, 1345f, 1105f, 1341f, 1109f, 1340f, 1115f)
        val spec = ShapeRecognizer.recognize(pts, 27f)
        assertNotNull(spec)
        assertEquals(ShapeKind.ARROW, spec!!.kind)
        assertNear(1351f, spec.points[2], 12f, "tip x")
        assertNear(1051f, spec.points[3], 12f, "tip y")
    }

    @Test
    fun arrowHeldWithJitterIsStillArrowAfterTrimmingRest() {
        val held = floatArrayOf(853f, 1333f, 898f, 1293f, 954f, 1264f, 1001f, 1226f, 1046f, 1186f, 1104f, 1159f, 1151f, 1121f, 1204f, 1089f, 1248f, 1048f, 1305f, 1020f, 1351f, 981f, 1345f, 982f, 1338f, 982f, 1330f, 981f, 1321f, 979f, 1322f, 987f, 1307f, 979f, 1304f, 983f, 1297f, 983f, 1288f, 981f, 1286f, 986f, 1295f, 988f, 1295f, 981f, 1301f, 980f, 1316f, 988f, 1317f, 982f, 1322f, 980f, 1329f, 980f, 1335f, 979f, 1348f, 985f, 1345f, 975f, 1346f, 984f, 1343f, 988f, 1342f, 995f, 1346f, 1006f, 1345f, 1013f, 1340f, 1015f, 1338f, 1021f, 1345f, 1035f, 1341f, 1039f, 1340f, 1045f, 1350f, 1045f, 1335f, 1054f, 1335f, 1036f, 1350f, 1045f, 1335f, 1054f, 1335f, 1036f, 1350f, 1045f, 1335f, 1053f, 1335f, 1036f, 1350f, 1046f)
        assertNull(ShapeRecognizer.recognize(held, 27f))
        val trimmed = ShapeRecognizer.trimTrailingRest(held, 1350f, 1046f, 27f)
        assertEquals(ShapeKind.ARROW, ShapeRecognizer.recognize(trimmed, 27f)?.kind)
    }

    @Test
    fun trimTrailingRestKeepsArrivalPoint() {
        val pts = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f, 103f, 2f, 98f, -3f, 101f, 1f)
        val out = ShapeRecognizer.trimTrailingRest(pts, 100f, 0f, 6f)
        assertEquals(6, out.size)
        assertEquals(100f, out[4], 0f)
    }

    @Test
    fun noisyCircleIsEllipseWithEqualRadii() {
        val pts = trace(4L) {
            for (i in 0..120) {
                val t = 2.0 * PI * i / 120
                add(300f + 150f * cos(t).toFloat(), 400f + 150f * sin(t).toFloat())
            }
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.ELLIPSE, spec.kind)
        assertEquals(spec.points[2], spec.points[3], 0.001f)
        assertNear(150f, spec.points[2], 8f, "radius")
        assertNear(300f, spec.points[0], 8f, "cx")
        assertNear(400f, spec.points[1], 8f, "cy")
    }

    @Test
    fun noisyEllipseKeepsDistinctRadii() {
        val pts = trace(5L) {
            for (i in 0..120) {
                val t = 2.0 * PI * i / 120
                add(300f + 150f * cos(t).toFloat(), 400f + 75f * sin(t).toFloat())
            }
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.ELLIPSE, spec.kind)
        assertNear(150f, spec.points[2], 8f, "rx")
        assertNear(75f, spec.points[3], 8f, "ry")
        assertTrue(abs(spec.points[2] - spec.points[3]) > 50f)
    }

    @Test
    fun skewedRectangleSnapsToAxisAlignedRect() {
        val corners = arrayOf(
            floatArrayOf(105f, 96f),
            floatArrayOf(396f, 104f),
            floatArrayOf(404f, 347f),
            floatArrayOf(94f, 356f)
        )
        val pts = trace(6L) {
            for (k in 0..3) {
                val a = corners[k % 4]
                val b = corners[(k + 1) % 4]
                segment(a[0], a[1], b[0], b[1], step = 10f)
            }
            end(corners[0][0], corners[0][1])
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.RECT, spec.kind)
        val p = spec.points
        for (k in 0 until 4) {
            val a = k * 2
            val b = ((k + 1) % 4) * 2
            assertTrue(
                "side $k not axis-aligned",
                abs(p[a] - p[b]) < 0.01f || abs(p[a + 1] - p[b + 1]) < 0.01f
            )
        }
        assertNear(100f, p[0], 8f, "left")
        assertNear(400f, p[2], 8f, "right")
        assertNear(100f, p[1], 8f, "top")
        assertNear(350f, p[5], 8f, "bottom")
    }

    @Test
    fun triangleIsTriangle() {
        val pts = trace(7L) {
            val vs = arrayOf(floatArrayOf(300f, 100f), floatArrayOf(120f, 400f), floatArrayOf(480f, 400f))
            for (k in 0..2) {
                val a = vs[k % 3]
                val b = vs[(k + 1) % 3]
                segment(a[0], a[1], b[0], b[1])
            }
            end(vs[0][0], vs[0][1])
        }
        val spec = recognized(pts)
        assertEquals(ShapeKind.TRIANGLE, spec.kind)
        assertEquals(6, spec.points.size)
    }

    @Test
    fun randomScribbleIsNull() {
        val rnd = Random(8L)
        val pts = trace(9L, amp = 0f) {
            var x = 300f
            var y = 300f
            for (i in 0 until 200) {
                x += (rnd.nextFloat() * 2f - 1f) * 40f
                y += (rnd.nextFloat() * 2f - 1f) * 40f
                add(x, y)
            }
        }
        assertNull(ShapeRecognizer.recognize(pts, tol))
    }

    @Test
    fun zigzagWritingIsNull() {
        val pts = trace(10L) {
            for (i in 0..24) {
                val x = 100f + i * 16f
                val y = if (i % 2 == 0) 200f else 400f
                add(x, y)
            }
            for (i in 0..24) {
                val x = 100f + i * 16f
                val y = if (i % 2 == 0) 420f else 220f
                add(x, y)
            }
        }
        assertNull(ShapeRecognizer.recognize(pts, tol))
    }

    @Test
    fun smallSpiralIsNotLine() {
        val pts = trace(11L, amp = 1f) {
            for (i in 0..120) {
                val t = 3.0 * PI * i / 120
                val r = 10f + 60f * i / 120f
                add(300f + r * cos(t).toFloat(), 300f + r * sin(t).toFloat())
            }
        }
        val spec = ShapeRecognizer.recognize(pts, tol)
        assertFalse(spec?.kind == ShapeKind.LINE)
    }

    @Test
    fun outlinesAreFiniteAndClosedWhereExpected() {
        val specs = listOf(
            ShapeSpec.fromDrag(ShapeKind.LINE, 0f, 0f, 100f, 0f),
            ShapeSpec.fromDrag(ShapeKind.ARROW, 0f, 0f, 100f, 0f),
            ShapeSpec.fromDrag(ShapeKind.RECT, 0f, 0f, 100f, 50f),
            ShapeSpec.fromDrag(ShapeKind.TRIANGLE, 0f, 0f, 100f, 100f),
            ShapeSpec.fromDrag(ShapeKind.ELLIPSE, 0f, 0f, 200f, 100f),
            ShapeSpec.fromDrag(ShapeKind.LINE, 5f, 5f, 5f, 5f),
            ShapeSpec.fromDrag(ShapeKind.ELLIPSE, 5f, 5f, 5f, 5f)
        )
        for (spec in specs) {
            val out = ShapeOutline.outline(spec)
            assertTrue("${spec.kind} needs at least 2 points", out.size >= 4)
            for (v in out) assertFalse("${spec.kind} has NaN", v.isNaN())
            if (spec.kind == ShapeKind.RECT || spec.kind == ShapeKind.TRIANGLE || spec.kind == ShapeKind.ELLIPSE) {
                assertEquals(spec.kind.toString(), out[0], out[out.size - 2], 0.0001f)
                assertEquals(spec.kind.toString(), out[1], out[out.size - 1], 0.0001f)
            }
        }
    }

    @Test
    fun arrowOutlineStartsAtTailAndEndsOnBarb() {
        val spec = ShapeSpec.fromDrag(ShapeKind.ARROW, 0f, 0f, 100f, 0f)
        val out = ShapeOutline.outline(spec)
        assertEquals(0f, out[0], 0.0001f)
        assertEquals(0f, out[1], 0.0001f)
        assertTrue(out[out.size - 2] < 100f)
    }

    @Test
    fun fromDragNormalizesReversedBoundsAndTransformScales() {
        val rect = ShapeSpec.fromDrag(ShapeKind.RECT, 100f, 50f, 0f, 0f)
        val box = FloatArray(4)
        rect.bounds(box)
        assertEquals(listOf(0f, 0f, 100f, 50f), box.toList())

        val moved = rect.transformed(ax = 0f, ay = 0f, scale = 2f, rotation = 0f, dx = 10f, dy = 5f)
        moved.bounds(box)
        assertEquals(listOf(10f, 5f, 210f, 105f), box.toList())
    }

    @Test
    fun ellipseFromDragAndTransformScalesRadii() {
        val ellipse = ShapeSpec.fromDrag(ShapeKind.ELLIPSE, 0f, 0f, 200f, 100f)
        assertEquals(listOf(100f, 50f, 100f, 50f, 0f), ellipse.points.toList())
        val scaled = ellipse.transformed(ax = 100f, ay = 50f, scale = 2f, rotation = 0f, dx = 0f, dy = 0f)
        assertEquals(listOf(100f, 50f, 200f, 100f, 0f), scaled.points.toList())
        val stretched = ellipse.scaledXY(ax = 0f, ay = 0f, sx = 0.5f, sy = 1f)
        assertEquals(50f, stretched.points[0], 0.0001f)
        assertEquals(50f, stretched.points[2], 0.0001f)
    }

    @Test
    fun rotatedRectOutlineStaysClosedAndVerticesRotate() {
        val rect = ShapeSpec.fromDrag(ShapeKind.RECT, 0f, 0f, 200f, 100f)
        val rad30 = Math.toRadians(30.0).toFloat()
        val rotated = rect.transformed(ax = 100f, ay = 50f, scale = 1f, rotation = rad30, dx = 0f, dy = 0f)
        val out = ShapeOutline.outline(rotated)
        assertTrue(out.size >= 8)
        assertEquals(out[0], out[out.size - 2], 0.0001f)
        assertEquals(out[1], out[out.size - 1], 0.0001f)
        val expectedX0 = 100f + (-100f * cos(rad30) - (-50f) * sin(rad30))
        val expectedY0 = 50f + (-100f * sin(rad30) + (-50f) * cos(rad30))
        assertEquals(expectedX0, out[0], 0.001f)
        assertEquals(expectedY0, out[1], 0.001f)
        var maxDist = 0f
        var i = 0
        while (i + 1 < out.size) {
            val d = kotlin.math.hypot(out[i] - 100f, out[i + 1] - 50f)
            if (d > maxDist) maxDist = d
            i += 2
        }
        assertEquals(kotlin.math.hypot(100f, 50f), maxDist, 0.5f)
    }

    @Test
    fun rotatedEllipseBounds90DegreesSwapsWidthAndHeight() {
        val ellipse = ShapeSpec.fromDrag(ShapeKind.ELLIPSE, 0f, 0f, 200f, 80f)
        val origBox = FloatArray(4)
        ellipse.bounds(origBox)
        assertEquals(200f, origBox[2] - origBox[0], 0.001f)
        assertEquals(80f, origBox[3] - origBox[1], 0.001f)

        val rad90 = Math.toRadians(90.0).toFloat()
        val rotated = ellipse.transformed(ax = 100f, ay = 40f, scale = 1f, rotation = rad90, dx = 0f, dy = 0f)
        val rotBox = FloatArray(4)
        rotated.bounds(rotBox)
        assertEquals(80f, rotBox[2] - rotBox[0], 0.01f)
        assertEquals(200f, rotBox[3] - rotBox[1], 0.01f)
    }
}
