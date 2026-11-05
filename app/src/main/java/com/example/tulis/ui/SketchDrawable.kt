package com.example.tulis.ui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.util.Random

class SketchDrawable(
    seed: Long = System.nanoTime(),
    var fill: Int = Palette.CARD,
    var stroke: Int = Palette.INK,
    strokeWidthDp: Float = 2.5f,
    shadowOffsetDp: Float = 3f,
    cornerDp: Float = 14f,
    var round: Boolean = false,
    var shadowColor: Int = Palette.SHADOW_SOFT,
    var variant: Int = -1
) : Drawable() {

    private val density: Float = Resources.getSystem().displayMetrics.density
    private var currentSeed: Long = seed
    private var rng = Random(seed)

    var strokeWidthPx: Float = strokeWidthDp * density
        set(value) {
            field = value
            strokePaint.strokeWidth = value
            dirty = true
            invalidateSelf()
        }

    var shadowOffsetPx: Float = shadowOffsetDp * density
        set(value) {
            field = value
            dirty = true
            invalidateSelf()
        }

    var cornerPx: Float = cornerDp * density
        set(value) {
            field = value
            dirty = true
            invalidateSelf()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fill
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = stroke
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = shadowColor
    }

    private val fillPath = Path()
    private val strokePath = Path()
    private val shadowPath = Path()

    private val tempRectF = RectF()

    private var dirty = true

    fun applyFill(c: Int) {
        if (fill != c) {
            fill = c
            fillPaint.color = c
            invalidateSelf()
        }
    }

    fun applyStroke(c: Int) {
        if (stroke != c) {
            stroke = c
            strokePaint.color = c
            invalidateSelf()
        }
    }

    fun reseed(seed: Long = System.nanoTime()) {
        currentSeed = seed
        dirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        dirty = true
    }

    private fun rebuildPaths() {
        fillPath.reset()
        strokePath.reset()
        shadowPath.reset()

        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            dirty = false
            return
        }

        rng.setSeed(currentSeed)

        val halfStroke = strokeWidthPx * 0.5f
        val left = bounds.left.toFloat() + halfStroke
        val top = bounds.top.toFloat() + halfStroke
        val right = bounds.right.toFloat() - halfStroke
        val bottom = bounds.bottom.toFloat() - halfStroke

        if (right <= left || bottom <= top) {
            dirty = false
            return
        }

        val width = right - left
        val height = bottom - top

        if (round) {
            val rxTL = width * 0.52f
            val ryTL = height * 0.48f
            val rxTR = width * 0.48f
            val ryTR = height * 0.54f
            val rxBR = width * 0.55f
            val ryBR = height * 0.46f
            val rxBL = width * 0.45f
            val ryBL = height * 0.52f

            appendAsymmetricPath(
                left, top, right, bottom,
                rxTL, ryTL,
                rxTR, ryTR,
                rxBR, ryBR,
                rxBL, ryBL
            )
        } else if (variant >= 0) {
            val preset = PRESETS[variant % PRESETS.size]
            val scale = (cornerPx / 14f).coerceAtLeast(0.1f)

            var rxTL = preset[0] * scale
            var rxTR = preset[1] * scale
            var rxBR = preset[2] * scale
            var rxBL = preset[3] * scale

            var ryTL = preset[4] * scale
            var ryTR = preset[5] * scale
            var ryBR = preset[6] * scale
            var ryBL = preset[7] * scale

            val maxHorizontal = (width * 0.5f)
            val maxVertical = (height * 0.5f)

            val factorX = Math.min(
                1f,
                Math.min(
                    if (rxTL + rxTR > 0f) width / (rxTL + rxTR) else 1f,
                    if (rxBL + rxBR > 0f) width / (rxBL + rxBR) else 1f
                )
            ).coerceAtMost(if (rxTL > 0f) maxHorizontal / rxTL else 1f)
                .coerceAtMost(if (rxTR > 0f) maxHorizontal / rxTR else 1f)
                .coerceAtMost(if (rxBR > 0f) maxHorizontal / rxBR else 1f)
                .coerceAtMost(if (rxBL > 0f) maxHorizontal / rxBL else 1f)

            val factorY = Math.min(
                1f,
                Math.min(
                    if (ryTL + ryBL > 0f) height / (ryTL + ryBL) else 1f,
                    if (ryTR + ryBR > 0f) height / (ryTR + ryBR) else 1f
                )
            ).coerceAtMost(if (ryTL > 0f) maxVertical / ryTL else 1f)
                .coerceAtMost(if (ryTR > 0f) maxVertical / ryTR else 1f)
                .coerceAtMost(if (ryBR > 0f) maxVertical / ryBR else 1f)
                .coerceAtMost(if (ryBL > 0f) maxVertical / ryBL else 1f)

            val factor = Math.min(factorX, factorY)

            rxTL *= factor
            rxTR *= factor
            rxBR *= factor
            rxBL *= factor

            ryTL *= factor
            ryTR *= factor
            ryBR *= factor
            ryBL *= factor

            appendAsymmetricPath(
                left, top, right, bottom,
                rxTL, ryTL,
                rxTR, ryTR,
                rxBR, ryBR,
                rxBL, ryBL
            )
        } else {
            val cr = cornerPx.coerceAtMost(Math.min(width, height) * 0.5f)
            val jitter = 0.9f * density

            fun j(): Float = (rng.nextFloat() * 2f - 1f) * jitter

            val p0x = left + cr + j()
            val p0y = top + j()
            val p1x = right - cr + j()
            val p1y = top + j()

            val p2x = right + j()
            val p2y = top + cr + j()
            val p3x = right + j()
            val p3y = bottom - cr + j()

            val p4x = right - cr + j()
            val p4y = bottom + j()
            val p5x = left + cr + j()
            val p5y = bottom + j()

            val p6x = left + j()
            val p6y = bottom - cr + j()
            val p7x = left + j()
            val p7xTop = left + j()
            val p7y = top + cr + j()

            fillPath.moveTo(p0x, p0y)
            fillPath.lineTo(p1x, p1y)
            tempRectF.set(right - 2f * cr, top, right, top + 2f * cr)
            fillPath.arcTo(tempRectF, 270f, 90f, false)

            fillPath.lineTo(p3x, p3y)
            tempRectF.set(right - 2f * cr, bottom - 2f * cr, right, bottom)
            fillPath.arcTo(tempRectF, 0f, 90f, false)

            fillPath.lineTo(p5x, p5y)
            tempRectF.set(left, bottom - 2f * cr, left + 2f * cr, bottom)
            fillPath.arcTo(tempRectF, 90f, 90f, false)

            fillPath.lineTo(p7xTop, p7y)
            tempRectF.set(left, top, left + 2f * cr, top + 2f * cr)
            fillPath.arcTo(tempRectF, 180f, 90f, false)
            fillPath.close()

            strokePath.moveTo(p0x, p0y)
            strokePath.lineTo(p1x, p1y)
            tempRectF.set(right - 2f * cr, top, right, top + 2f * cr)
            strokePath.arcTo(tempRectF, 270f, 90f, false)

            strokePath.lineTo(p3x, p3y)
            tempRectF.set(right - 2f * cr, bottom - 2f * cr, right, bottom)
            strokePath.arcTo(tempRectF, 0f, 90f, false)

            strokePath.lineTo(p5x, p5y)
            tempRectF.set(left, bottom - 2f * cr, left + 2f * cr, bottom)
            strokePath.arcTo(tempRectF, 90f, 90f, false)

            strokePath.lineTo(p7xTop, p7y)
            tempRectF.set(left, top, left + 2f * cr, top + 2f * cr)
            strokePath.arcTo(tempRectF, 180f, 90f, false)

            strokePath.lineTo(p0x + 2f * jitter, p0y)
        }

        shadowPath.set(fillPath)
        shadowPath.offset(shadowOffsetPx, shadowOffsetPx)

        dirty = false
    }

    private fun appendAsymmetricPath(
        left: Float, top: Float, right: Float, bottom: Float,
        rxTL: Float, ryTL: Float,
        rxTR: Float, ryTR: Float,
        rxBR: Float, ryBR: Float,
        rxBL: Float, ryBL: Float
    ) {
        val jitter = 0.6f * density
        fun j(): Float = (rng.nextFloat() * 2f - 1f) * jitter

        val startX = left + rxTL + j()
        val startY = top + j()

        fillPath.moveTo(startX, startY)
        fillPath.lineTo(right - rxTR + j(), top + j())
        tempRectF.set(right - 2f * rxTR, top, right, top + 2f * ryTR)
        fillPath.arcTo(tempRectF, 270f, 90f, false)

        fillPath.lineTo(right + j(), bottom - ryBR + j())
        tempRectF.set(right - 2f * rxBR, bottom - 2f * ryBR, right, bottom)
        fillPath.arcTo(tempRectF, 0f, 90f, false)

        fillPath.lineTo(left + rxBL + j(), bottom + j())
        tempRectF.set(left, bottom - 2f * ryBL, left + 2f * rxBL, bottom)
        fillPath.arcTo(tempRectF, 90f, 90f, false)

        fillPath.lineTo(left + j(), top + ryTL + j())
        tempRectF.set(left, top, left + 2f * rxTL, top + 2f * ryTL)
        fillPath.arcTo(tempRectF, 180f, 90f, false)
        fillPath.close()

        strokePath.moveTo(startX, startY)
        strokePath.lineTo(right - rxTR + j(), top + j())
        tempRectF.set(right - 2f * rxTR, top, right, top + 2f * ryTR)
        strokePath.arcTo(tempRectF, 270f, 90f, false)

        strokePath.lineTo(right + j(), bottom - ryBR + j())
        tempRectF.set(right - 2f * rxBR, bottom - 2f * ryBR, right, bottom)
        strokePath.arcTo(tempRectF, 0f, 90f, false)

        strokePath.lineTo(left + rxBL + j(), bottom + j())
        tempRectF.set(left, bottom - 2f * ryBL, left + 2f * rxBL, bottom)
        strokePath.arcTo(tempRectF, 90f, 90f, false)

        strokePath.lineTo(left + j(), top + ryTL + j())
        tempRectF.set(left, top, left + 2f * rxTL, top + 2f * ryTL)
        strokePath.arcTo(tempRectF, 180f, 90f, false)

        strokePath.lineTo(startX + 1.5f * jitter, startY)
    }

    override fun draw(canvas: Canvas) {
        if (dirty) {
            rebuildPaths()
        }

        if (shadowOffsetPx > 0f) {
            shadowPaint.color = shadowColor
            canvas.drawPath(shadowPath, shadowPaint)
        }

        fillPaint.color = fill
        canvas.drawPath(fillPath, fillPaint)

        strokePaint.color = stroke
        canvas.drawPath(strokePath, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        val baseShadowAlpha = (shadowColor ushr 24) and 0xFF
        shadowPaint.alpha = (alpha * baseShadowAlpha) / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        shadowPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val PRESETS: Array<FloatArray> = arrayOf(
            floatArrayOf(20f, 8f, 18f, 10f, 10f, 18f, 8f, 20f),
            floatArrayOf(10f, 18f, 8f, 20f, 18f, 8f, 20f, 10f),
            floatArrayOf(14f, 6f, 16f, 8f, 8f, 14f, 6f, 16f),
            floatArrayOf(8f, 16f, 6f, 14f, 14f, 6f, 16f, 8f),
            floatArrayOf(16f, 8f, 14f, 6f, 6f, 14f, 8f, 16f),
            floatArrayOf(8f, 14f, 16f, 6f, 16f, 8f, 14f, 6f),
            floatArrayOf(14f, 6f, 8f, 16f, 8f, 16f, 6f, 14f),
            floatArrayOf(16f, 8f, 6f, 14f, 6f, 14f, 8f, 16f),
            floatArrayOf(22f, 10f, 20f, 12f, 12f, 20f, 10f, 22f),
            floatArrayOf(12f, 20f, 10f, 22f, 20f, 10f, 22f, 12f),
            floatArrayOf(30f, 12f, 28f, 14f, 14f, 28f, 12f, 30f),
            floatArrayOf(22f, 10f, 24f, 12f, 12f, 24f, 10f, 22f)
        )
    }
}
