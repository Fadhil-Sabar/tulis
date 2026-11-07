package io.github.fadhil_sabar.tulis.export

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.fadhil_sabar.tulis.ink.InkBridge
import io.github.fadhil_sabar.tulis.ink.forEachInDrawOrder
import io.github.fadhil_sabar.tulis.model.PageContent
import io.github.fadhil_sabar.tulis.model.PaperStyle
import io.github.fadhil_sabar.tulis.model.TextItem
import io.github.fadhil_sabar.tulis.ui.Palette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class PageRenderer(
    private val paperStyle: PaperStyle,
    private val spacing: Float,
    private val drawPaper: Boolean
) {
    private val strokeRenderer = InkBridge.createRenderer()
    private val tmp = Matrix()
    private val boundsTmp = RectF()
    private val textPaint = TextPaint().apply { isAntiAlias = true }

    private val dotPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f
        color = Palette.DOT_CANVAS
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x262B2724
        isAntiAlias = true
    }

    fun renderPaged(canvas: Canvas, page: PageContent, pageW: Float, pageH: Float, worldToDevice: Matrix) {
        canvas.drawColor(if (drawPaper) Palette.SHEET else 0xFFFFFFFF.toInt())
        canvas.save()
        canvas.concat(worldToDevice)
        canvas.clipRect(0f, 0f, pageW, pageH)
        if (drawPaper) drawPattern(canvas, 0f, 0f, pageW, pageH, spacing, spacing)
        canvas.restore()
        drawContent(canvas, page, worldToDevice)
    }

    fun renderInfinite(canvas: Canvas, page: PageContent, world: RectF, worldToDevice: Matrix) {
        canvas.drawColor(if (drawPaper) Palette.SHEET else 0xFFFFFFFF.toInt())
        if (drawPaper) {
            canvas.save()
            canvas.concat(worldToDevice)
            drawPattern(canvas, world.left, world.top, world.right, world.bottom, 10f, 14f)
            canvas.restore()
        }
        drawContent(canvas, page, worldToDevice)
    }

    private fun drawContent(canvas: Canvas, page: PageContent, worldToDevice: Matrix) {
        forEachInDrawOrder(page.strokes) { stroke ->
            InkBridge.draw(strokeRenderer, canvas, stroke, worldToDevice, tmp)
        }
        canvas.save()
        canvas.concat(worldToDevice)
        for (item in page.texts) {
            val layout = buildLayout(item)
            canvas.save()
            canvas.translate(item.x, item.y)
            textPaint.color = item.colorArgb
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawPattern(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ox: Float,
        oy: Float
    ) {
        if (paperStyle == PaperStyle.PLAIN) return
        var step = spacing
        while (((right - left) / step + 1) * ((bottom - top) / step + 1) > 160_000f) step *= 2f
        val startX = floor((left - ox) / step) * step + ox
        val startY = floor((top - oy) / step) * step + oy
        when (paperStyle) {
            PaperStyle.DOTS -> {
                var y = startY
                while (y <= bottom) {
                    var x = startX
                    while (x <= right) {
                        if (x >= left && y >= top) canvas.drawPoint(x, y, dotPaint)
                        x += step
                    }
                    y += step
                }
            }
            PaperStyle.GRID, PaperStyle.LINES -> {
                var y = startY
                while (y <= bottom) {
                    if (y >= top) canvas.drawLine(left, y, right, y, linePaint)
                    y += step
                }
                if (paperStyle == PaperStyle.GRID) {
                    var x = startX
                    while (x <= right) {
                        if (x >= left) canvas.drawLine(x, top, x, bottom, linePaint)
                        x += step
                    }
                }
            }
            PaperStyle.PLAIN -> Unit
        }
    }

    private fun buildLayout(item: TextItem): StaticLayout {
        textPaint.textSize = item.sizeWorld
        return StaticLayout.Builder
            .obtain(item.text, 0, item.text.length, textPaint, max(1, item.widthWorld.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    fun contentBounds(page: PageContent, out: RectF): Boolean {
        var has = false
        for (stroke in page.strokes) {
            if (stroke.worldBounds(boundsTmp)) {
                if (!has) out.set(boundsTmp) else out.union(boundsTmp)
                has = true
            }
        }
        for (item in page.texts) {
            val layout = buildLayout(item)
            boundsTmp.set(item.x, item.y, item.x + item.widthWorld, item.y + layout.height)
            if (!has) out.set(boundsTmp) else out.union(boundsTmp)
            has = true
        }
        return has
    }
}

internal fun ceilInt(v: Float): Int = ceil(v).toInt()
