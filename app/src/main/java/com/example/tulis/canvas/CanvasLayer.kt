package com.example.tulis.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.example.tulis.ink.InkBridge
import com.example.tulis.ink.InkRenderer
import com.example.tulis.model.NoteMode
import com.example.tulis.model.PageContent
import com.example.tulis.model.PageStack
import com.example.tulis.model.PaperStyle
import com.example.tulis.model.TextItem
import com.example.tulis.model.Viewport
import com.example.tulis.ui.Palette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class CanvasLayer(context: Context) : View(context) {
    var mode: NoteMode = NoteMode.INFINITE
        set(value) {
            field = value
            contentDirty = true
            invalidate()
        }

    var stack: PageStack? = null
        set(value) {
            field = value
            invalidate()
        }

    var paperStyle: PaperStyle = PaperStyle.DOTS
        private set

    var paperSpacing: Float = 28f
        private set

    fun setPaper(style: PaperStyle, spacing: Float) {
        paperStyle = style
        paperSpacing = spacing.coerceIn(12f, 120f)
        invalidate()
    }

    var page: PageContent? = null
        set(value) {
            if (field !== value) {
                field = value
                if (value == null) {
                    trimCache()
                }
                contentDirty = true
                invalidate()
            }
        }

    val viewport: Viewport = Viewport().apply {
        onChanged = {
            onViewportChanged()
            invalidate()
        }
    }

    var pageW: Float = 1000f
        private set
    var pageH: Float = 1414f
        private set

    fun setPageSize(w: Float, h: Float) {
        pageW = w
        pageH = h
        pageWorldRect.set(0f, 0f, w, h)
        invalidate()
    }

    private val density: Float = context.resources.displayMetrics.density

    private val strokeRenderer: InkRenderer = InkBridge.createRenderer()

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val pageFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Palette.SHEET
    }

    private val pageShadowPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Palette.SHADOW_PAGE
    }

    private val pageBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0x8C2B2724.toInt()
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    private val pageRenderCache: PageRenderCache = PageRenderCache(context)

    private val dotRadiusPx = 1.4f * density
    private val dotPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * dotRadiusPx
        color = Palette.DOT_CANVAS
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x262B2724
        isAntiAlias = true
    }

    private val textPaint = TextPaint().apply {
        isAntiAlias = true
    }

    private val tmpMatrix = Matrix()
    private val tmpItemMatrix = Matrix()
    private val pageMatrix = Matrix()
    private val visibleWorldRect = RectF()
    private val strokeBoundsRect = RectF()
    private val pageWorldRect = RectF(0f, 0f, pageW, pageH)
    private val pagedItemWorldRect = RectF()
    private val pageScreenRect = RectF()
    private val pageShadowRect = RectF()
    private val textBoundsRect = RectF()
    private val localTextBoundsRect = RectF()

    private val shadowOffsetXDp = 4f * density
    private val shadowOffsetYDp = 6f * density

    private var dotPoints = FloatArray(512)
    private var linePoints = FloatArray(512)

    private val contentNode: RenderNode? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RenderNode("CanvasLayerContent").apply {
            setClipToBounds(false)
        }
    } else {
        null
    }

    private var recPage: PageContent? = null
    private var recRevision: Int = -1
    private var recCount: Int = 0
    private var recScale: Float = 1f
    private var recTx: Float = 0f
    private var recTy: Float = 0f
    private val recBounds = RectF()
    private var contentDirty: Boolean = true
    private var gestureActive: Boolean = false
    private var sharpenPending: Boolean = false

    private val sharpenRunnable = Runnable {
        sharpenPending = false
        contentDirty = true
        invalidate()
    }

    private val safetySharpenRunnable = Runnable {
        if (!gestureActive && !sharpenPending) {
            sharpenPending = true
            postDelayed(sharpenRunnable, 120L)
        }
    }

    private class CachedLayout(
        val text: String,
        val widthWorld: Float,
        val sizeWorld: Float,
        val layout: StaticLayout
    )

    private val textLayoutCache = java.util.WeakHashMap<TextItem, CachedLayout>()

    init {
        setWillNotDraw(false)
    }

    fun setViewportGestureActive(active: Boolean) {
        if (gestureActive == active) return
        gestureActive = active
        removeCallbacks(safetySharpenRunnable)
        if (!active) {
            if (!sharpenPending) {
                sharpenPending = true
                postDelayed(sharpenRunnable, 120L)
            }
        } else {
            if (sharpenPending) {
                removeCallbacks(sharpenRunnable)
                sharpenPending = false
            }
        }
    }

    fun pageToScreen(i: Int, out: Matrix) {
        val s = stack
        val oy = s?.offsetY(i) ?: (i * (pageH + PageStack.GAP))
        out.set(viewport.worldToScreen)
        out.preTranslate(0f, oy)
    }

    fun hitPage(wx: Float, wy: Float, outLocal: FloatArray): Int {
        if (wx < 0f || wx > pageW) return -1
        val s = stack ?: return -1
        val idx = s.pageAt(wy)
        if (idx < 0) return -1
        outLocal[0] = wx
        outLocal[1] = wy - s.offsetY(idx)
        return idx
    }

    fun onStrokeAppended() {
        val p = page ?: return
        if (recPage === p && recRevision == p.revision - 1) {
            recRevision = p.revision
        } else {
            contentDirty = true
        }
        invalidate()
    }

    fun onStrokeAppended(page: PageContent) {
        pageRenderCache.onStrokeAppended(page)
        invalidate()
    }

    fun invalidateContent() {
        contentDirty = true
        invalidate()
    }

    fun invalidateContent(page: PageContent?) {
        if (page != null) {
            pageRenderCache.invalidate(page)
        } else {
            pageRenderCache.discardAll()
        }
        contentDirty = true
        invalidate()
    }

    fun trimCache() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentNode?.discardDisplayList()
        }
        pageRenderCache.discardAll()
        recPage = null
        recRevision = -1
        recCount = 0
        recBounds.setEmpty()
        contentDirty = true
    }

    private fun onViewportChanged() {
        removeCallbacks(safetySharpenRunnable)
        if (!gestureActive) {
            postDelayed(safetySharpenRunnable, 300L)
        }
    }

    fun insidePage(wx: Float, wy: Float): Boolean {
        return wx in 0f..pageW && wy in 0f..pageH
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        contentDirty = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(sharpenRunnable)
        removeCallbacks(safetySharpenRunnable)
        trimCache()
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("Tulis.onDraw")
        try {
            super.onDraw(canvas)

            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            viewport.visibleWorld(w, h, visibleWorldRect)

            if (mode == NoteMode.PAGED) {
                if (stack != null) {
                    drawPagedStack(canvas, w, h)
                } else {
                    drawPaged(canvas, w, h)
                }
            } else {
                drawInfinite(canvas, w, h)
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawInfinite(canvas: Canvas, w: Int, h: Int) {
        bgPaint.color = Palette.CANVAS
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        drawInfinitePattern(canvas)

        drawContent(canvas)
    }

    private fun drawPagedStack(canvas: Canvas, w: Int, h: Int) {
        val s = stack ?: return
        bgPaint.color = Palette.DESK
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val vRange = s.visibleRange(visibleWorldRect.top, visibleWorldRect.bottom)
        if (vRange.isEmpty()) return

        for (i in vRange) {
            val offsetY = s.offsetY(i)
            pagedItemWorldRect.set(0f, offsetY, pageW, offsetY + pageH)
            viewport.worldToScreen.mapRect(pageScreenRect, pagedItemWorldRect)

            pageShadowRect.set(
                pageScreenRect.left + shadowOffsetXDp,
                pageScreenRect.top + shadowOffsetYDp,
                pageScreenRect.right + shadowOffsetXDp,
                pageScreenRect.bottom + shadowOffsetYDp
            )
            canvas.drawRect(pageShadowRect, pageShadowPaint)

            canvas.drawRect(pageScreenRect, pageFillPaint)
            canvas.drawRect(pageScreenRect, pageBorderPaint)

            val saveCount = canvas.save()
            canvas.clipRect(pageScreenRect)

            drawPagedPatternAt(canvas, offsetY)

            val p = s.get(i)
            if (p != null) {
                pageToScreen(i, pageMatrix)
                pageRenderCache.draw(canvas, p, pageMatrix, viewport.scale, gestureActive)

                val texts = p.texts
                val textCount = texts.size
                for (tIdx in 0 until textCount) {
                    val textItem = texts[tIdx]
                    textItem.worldBounds(localTextBoundsRect)
                    textBoundsRect.set(
                        localTextBoundsRect.left,
                        localTextBoundsRect.top + offsetY,
                        localTextBoundsRect.right,
                        localTextBoundsRect.bottom + offsetY
                    )
                    if (!RectF.intersects(textBoundsRect, visibleWorldRect)) {
                        continue
                    }
                    drawTextItemWithMatrix(canvas, textItem, pageMatrix)
                }
            }

            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawPaged(canvas: Canvas, w: Int, h: Int) {
        bgPaint.color = Palette.DESK
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        viewport.worldToScreen.mapRect(pageScreenRect, pageWorldRect)

        pageShadowRect.set(
            pageScreenRect.left + shadowOffsetXDp,
            pageScreenRect.top + shadowOffsetYDp,
            pageScreenRect.right + shadowOffsetXDp,
            pageScreenRect.bottom + shadowOffsetYDp
        )
        canvas.drawRect(pageShadowRect, pageShadowPaint)

        canvas.drawRect(pageScreenRect, pageFillPaint)
        canvas.drawRect(pageScreenRect, pageBorderPaint)

        val saveCount = canvas.save()
        canvas.clipRect(pageScreenRect)

        drawPagedPattern(canvas)

        drawContent(canvas)

        canvas.restoreToCount(saveCount)
    }

    private fun drawInfinitePattern(canvas: Canvas) {
        when (paperStyle) {
            PaperStyle.PLAIN -> return
            PaperStyle.DOTS -> drawInfiniteDots(canvas)
            PaperStyle.GRID -> drawInfiniteLines(canvas, drawVertical = true)
            PaperStyle.LINES -> drawInfiniteLines(canvas, drawVertical = false)
        }
    }

    private fun drawPagedPattern(canvas: Canvas) {
        drawPagedPatternAt(canvas, 0f)
    }

    private fun drawPagedPatternAt(canvas: Canvas, pageOffsetY: Float) {
        when (paperStyle) {
            PaperStyle.PLAIN -> return
            PaperStyle.DOTS -> drawPagedDots(canvas, pageOffsetY)
            PaperStyle.GRID -> drawPagedLines(canvas, drawVertical = true, pageOffsetY = pageOffsetY)
            PaperStyle.LINES -> drawPagedLines(canvas, drawVertical = false, pageOffsetY = pageOffsetY)
        }
    }

    private fun drawInfiniteDots(canvas: Canvas) {
        val baseSpacing = paperSpacing
        var step = baseSpacing
        while (step * viewport.scale < 12f) {
            step *= 2f
        }

        val ox = 10f
        val oy = 14f

        var cols = ceil((visibleWorldRect.right - (floor((visibleWorldRect.left - ox) / step) * step + ox)) / step).toInt() + 1
        var rows = ceil((visibleWorldRect.bottom - (floor((visibleWorldRect.top - oy) / step) * step + oy)) / step).toInt() + 1
        while (cols > 400 || rows > 400) {
            step *= 2f
            cols = ceil((visibleWorldRect.right - (floor((visibleWorldRect.left - ox) / step) * step + ox)) / step).toInt() + 1
            rows = ceil((visibleWorldRect.bottom - (floor((visibleWorldRect.top - oy) / step) * step + oy)) / step).toInt() + 1
        }

        val startX = floor((visibleWorldRect.left - ox) / step) * step + ox
        val endX = visibleWorldRect.right
        val startY = floor((visibleWorldRect.top - oy) / step) * step + oy
        val endY = visibleWorldRect.bottom

        val totalCols = max(0, ceil((endX - startX) / step).toInt() + 1)
        val totalRows = max(0, ceil((endY - startY) / step).toInt() + 1)
        val neededFloats = totalCols * totalRows * 2

        if (dotPoints.size < neededFloats) {
            var newCap = dotPoints.size
            while (newCap < neededFloats) {
                newCap *= 2
            }
            dotPoints = FloatArray(newCap)
        }

        var idx = 0
        var wy = startY
        while (wy <= endY) {
            val sy = wy * viewport.scale + viewport.ty
            var wx = startX
            while (wx <= endX) {
                val sx = wx * viewport.scale + viewport.tx
                dotPoints[idx++] = sx
                dotPoints[idx++] = sy
                wx += step
            }
            wy += step
        }

        if (idx > 0) {
            canvas.drawPoints(dotPoints, 0, idx, dotPaint)
        }
    }

    private fun drawInfiniteLines(canvas: Canvas, drawVertical: Boolean) {
        val baseSpacing = paperSpacing
        var step = baseSpacing
        while (step * viewport.scale < 12f) {
            step *= 2f
        }

        val ox = 10f
        val oy = 14f

        var startX = floor((visibleWorldRect.left - ox) / step) * step + ox
        var startY = floor((visibleWorldRect.top - oy) / step) * step + oy
        var cols = if (drawVertical) ceil((visibleWorldRect.right - startX) / step).toInt() + 1 else 0
        var rows = ceil((visibleWorldRect.bottom - startY) / step).toInt() + 1
        while (cols > 400 || rows > 400) {
            step *= 2f
            startX = floor((visibleWorldRect.left - ox) / step) * step + ox
            startY = floor((visibleWorldRect.top - oy) / step) * step + oy
            cols = if (drawVertical) ceil((visibleWorldRect.right - startX) / step).toInt() + 1 else 0
            rows = ceil((visibleWorldRect.bottom - startY) / step).toInt() + 1
        }

        val endX = visibleWorldRect.right
        val endY = visibleWorldRect.bottom

        val totalCols = if (drawVertical) max(0, ceil((endX - startX) / step).toInt() + 1) else 0
        val totalRows = max(0, ceil((endY - startY) / step).toInt() + 1)
        val neededFloats = (totalCols + totalRows) * 4

        if (linePoints.size < neededFloats) {
            var newCap = linePoints.size
            while (newCap < neededFloats) {
                newCap *= 2
            }
            linePoints = FloatArray(newCap)
        }

        val screenLeft = 0f
        val screenRight = width.toFloat()
        val screenTop = 0f
        val screenBottom = height.toFloat()

        var idx = 0
        var wy = startY
        while (wy <= endY) {
            val sy = wy * viewport.scale + viewport.ty
            linePoints[idx++] = screenLeft
            linePoints[idx++] = sy
            linePoints[idx++] = screenRight
            linePoints[idx++] = sy
            wy += step
        }

        if (drawVertical) {
            var wx = startX
            while (wx <= endX) {
                val sx = wx * viewport.scale + viewport.tx
                linePoints[idx++] = sx
                linePoints[idx++] = screenTop
                linePoints[idx++] = sx
                linePoints[idx++] = screenBottom
                wx += step
            }
        }

        if (idx > 0) {
            canvas.drawLines(linePoints, 0, idx, linePaint)
        }
    }

    private fun drawPagedDots(canvas: Canvas, pageOffsetY: Float = 0f) {
        val baseSpacing = paperSpacing
        var step = baseSpacing
        while (step * viewport.scale < 12f) {
            step *= 2f
        }

        val ox = baseSpacing
        val oy = baseSpacing

        val visibleLeft = max(0f, visibleWorldRect.left)
        val visibleTop = max(0f, visibleWorldRect.top - pageOffsetY)
        val visibleRight = minOf(pageW, visibleWorldRect.right)
        val visibleBottom = minOf(pageH, visibleWorldRect.bottom - pageOffsetY)

        if (visibleLeft > visibleRight || visibleTop > visibleBottom) return

        var startX = if (visibleLeft <= ox) ox else ceil((visibleLeft - ox) / step) * step + ox
        var startY = if (visibleTop <= oy) oy else ceil((visibleTop - oy) / step) * step + oy
        val endX = visibleRight
        val endY = visibleBottom

        var cols = if (endX >= startX) ceil((endX - startX) / step).toInt() + 1 else 0
        var rows = if (endY >= startY) ceil((endY - startY) / step).toInt() + 1 else 0
        while (cols > 400 || rows > 400) {
            step *= 2f
            startX = if (visibleLeft <= ox) ox else ceil((visibleLeft - ox) / step) * step + ox
            startY = if (visibleTop <= oy) oy else ceil((visibleTop - oy) / step) * step + oy
            cols = if (endX >= startX) ceil((endX - startX) / step).toInt() + 1 else 0
            rows = if (endY >= startY) ceil((endY - startY) / step).toInt() + 1 else 0
        }

        val totalCols = max(0, cols)
        val totalRows = max(0, rows)
        val neededFloats = totalCols * totalRows * 2

        if (dotPoints.size < neededFloats) {
            var newCap = dotPoints.size
            while (newCap < neededFloats) {
                newCap *= 2
            }
            dotPoints = FloatArray(newCap)
        }

        var idx = 0
        var wy = startY
        while (wy <= endY) {
            val sy = (wy + pageOffsetY) * viewport.scale + viewport.ty
            var wx = startX
            while (wx <= endX) {
                val sx = wx * viewport.scale + viewport.tx
                dotPoints[idx++] = sx
                dotPoints[idx++] = sy
                wx += step
            }
            wy += step
        }

        if (idx > 0) {
            canvas.drawPoints(dotPoints, 0, idx, dotPaint)
        }
    }

    private fun drawPagedLines(canvas: Canvas, drawVertical: Boolean, pageOffsetY: Float = 0f) {
        val baseSpacing = paperSpacing
        var step = baseSpacing
        while (step * viewport.scale < 12f) {
            step *= 2f
        }

        val ox = baseSpacing
        val oy = baseSpacing

        val visibleLeft = max(0f, visibleWorldRect.left)
        val visibleTop = max(0f, visibleWorldRect.top - pageOffsetY)
        val visibleRight = minOf(pageW, visibleWorldRect.right)
        val visibleBottom = minOf(pageH, visibleWorldRect.bottom - pageOffsetY)

        if (visibleLeft > visibleRight || visibleTop > visibleBottom) return

        var startX = if (visibleLeft <= ox) ox else ceil((visibleLeft - ox) / step) * step + ox
        var startY = if (visibleTop <= oy) oy else ceil((visibleTop - oy) / step) * step + oy
        val endX = visibleRight
        val endY = visibleBottom

        var cols = if (drawVertical && endX >= startX) ceil((endX - startX) / step).toInt() + 1 else 0
        var rows = if (endY >= startY) ceil((endY - startY) / step).toInt() + 1 else 0
        while (cols > 400 || rows > 400) {
            step *= 2f
            startX = if (visibleLeft <= ox) ox else ceil((visibleLeft - ox) / step) * step + ox
            startY = if (visibleTop <= oy) oy else ceil((visibleTop - oy) / step) * step + oy
            cols = if (drawVertical && endX >= startX) ceil((endX - startX) / step).toInt() + 1 else 0
            rows = if (endY >= startY) ceil((endY - startY) / step).toInt() + 1 else 0
        }

        val totalCols = if (drawVertical) max(0, cols) else 0
        val totalRows = max(0, rows)
        val neededFloats = (totalCols + totalRows) * 4

        if (linePoints.size < neededFloats) {
            var newCap = linePoints.size
            while (newCap < neededFloats) {
                newCap *= 2
            }
            linePoints = FloatArray(newCap)
        }

        val pageScreenLeft = pageScreenRect.left
        val pageScreenRight = pageScreenRect.right
        val pageScreenTop = pageScreenRect.top
        val pageScreenBottom = pageScreenRect.bottom

        var idx = 0
        var wy = startY
        while (wy <= endY) {
            val sy = (wy + pageOffsetY) * viewport.scale + viewport.ty
            linePoints[idx++] = pageScreenLeft
            linePoints[idx++] = sy
            linePoints[idx++] = pageScreenRight
            linePoints[idx++] = sy
            wy += step
        }

        if (drawVertical) {
            var wx = startX
            while (wx <= endX) {
                val sx = wx * viewport.scale + viewport.tx
                linePoints[idx++] = sx
                linePoints[idx++] = pageScreenTop
                linePoints[idx++] = sx
                linePoints[idx++] = pageScreenBottom
                wx += step
            }
        }

        if (idx > 0) {
            canvas.drawLines(linePoints, 0, idx, linePaint)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val p = page ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated && contentNode != null) {
            drawStrokesAccelerated(canvas, p, contentNode)
        } else {
            drawStrokesDirect(canvas, p, 0, p.strokes.size)
        }

        val texts = p.texts
        val textCount = texts.size
        for (i in 0 until textCount) {
            val textItem = texts[i]
            textItem.worldBounds(textBoundsRect)
            if (!RectF.intersects(textBoundsRect, visibleWorldRect)) {
                continue
            }
            drawTextItem(canvas, textItem)
        }
    }

    private fun drawStrokesAccelerated(canvas: Canvas, p: PageContent, node: RenderNode) {
        val strokes = p.strokes
        val currentSize = strokes.size

        var needRecord = false
        if (p !== recPage || contentDirty || !node.hasDisplayList() || currentSize < recCount) {
            needRecord = true
        } else if (p.revision != recRevision) {
            needRecord = true
        } else if (!gestureActive && !sharpenPending && viewport.scale != recScale) {
            needRecord = true
        } else if (gestureActive && recScale > 0f) {
            val k = viewport.scale / recScale
            if (k < 0.5f || k > 2.0f) {
                needRecord = true
            }
        }

        if (needRecord) {
            record(p, node)
        }

        val k = if (recScale > 0f) viewport.scale / recScale else 1f
        val saveCount = canvas.save()
        canvas.translate(viewport.tx - k * recTx, viewport.ty - k * recTy)
        canvas.scale(k, k)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(saveCount)

        val pendingCount = currentSize - recCount
        if (pendingCount > 0) {
            drawStrokesDirect(canvas, p, recCount, currentSize)
            if (pendingCount > 32 && !gestureActive) {
                record(p, node)
            }
        }
    }

    private fun record(p: PageContent, node: RenderNode) {
        Trace.beginSection("Tulis.record")
        try {
            recPage = p
            recRevision = p.revision
            recCount = p.strokes.size
            recScale = viewport.scale
            recTx = viewport.tx
            recTy = viewport.ty
            contentDirty = false

            val strokes = p.strokes
            val strokeCount = strokes.size

            if (strokeCount == 0) {
                recBounds.setEmpty()
                node.setPosition(0, 0, 0, 0)
                val renderCanvas = node.beginRecording(1, 1)
                node.endRecording()
                return
            }

            var hasUnion = false
            for (i in 0 until strokeCount) {
                val item = strokes[i]
                if (item.worldBounds(strokeBoundsRect)) {
                    viewport.worldToScreen.mapRect(strokeBoundsRect)
                    if (!hasUnion) {
                        recBounds.set(strokeBoundsRect)
                        hasUnion = true
                    } else {
                        recBounds.union(strokeBoundsRect)
                    }
                }
            }

            if (!hasUnion) {
                recBounds.setEmpty()
                node.setPosition(0, 0, 0, 0)
                val renderCanvas = node.beginRecording(1, 1)
                node.endRecording()
                return
            }

            val margin = 8f * density
            val left = floor(recBounds.left - margin).toInt()
            val top = floor(recBounds.top - margin).toInt()
            val right = ceil(recBounds.right + margin).toInt()
            val bottom = ceil(recBounds.bottom + margin).toInt()

            val nodeWidth = max(1, right - left)
            val nodeHeight = max(1, bottom - top)

            recBounds.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            node.setPosition(left, top, right, bottom)

            val renderCanvas = node.beginRecording(nodeWidth, nodeHeight)
            try {
                renderCanvas.translate(-left.toFloat(), -top.toFloat())
                for (i in 0 until strokeCount) {
                    val strokeItem = strokes[i]
                    tmpItemMatrix.set(viewport.worldToScreen)
                    InkBridge.draw(strokeRenderer, renderCanvas, strokeItem, tmpItemMatrix, tmpMatrix)
                }
            } finally {
                node.endRecording()
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawStrokesDirect(canvas: Canvas, p: PageContent, startIndex: Int, endIndex: Int) {
        val strokes = p.strokes
        val limit = minOf(endIndex, strokes.size)
        for (i in startIndex until limit) {
            val strokeItem = strokes[i]
            if (strokeItem.worldBounds(strokeBoundsRect)) {
                if (!RectF.intersects(strokeBoundsRect, visibleWorldRect)) {
                    continue
                }
            }
            tmpItemMatrix.set(viewport.worldToScreen)
            InkBridge.draw(strokeRenderer, canvas, strokeItem, tmpItemMatrix, tmpMatrix)
        }
    }

    private fun drawTextItem(canvas: Canvas, item: TextItem) {
        val staticLayout = getOrCreateStaticLayout(item)

        val saveCount = canvas.save()

        canvas.translate(
            item.x * viewport.scale + viewport.tx,
            item.y * viewport.scale + viewport.ty
        )
        canvas.scale(viewport.scale, viewport.scale)

        textPaint.color = item.colorArgb
        textPaint.textSize = item.sizeWorld

        staticLayout.draw(canvas)

        canvas.restoreToCount(saveCount)
    }

    private fun drawTextItemWithMatrix(canvas: Canvas, item: TextItem, pageToScreen: Matrix) {
        val staticLayout = getOrCreateStaticLayout(item)

        val saveCount = canvas.save()

        canvas.concat(pageToScreen)
        canvas.translate(item.x, item.y)

        textPaint.color = item.colorArgb
        textPaint.textSize = item.sizeWorld

        staticLayout.draw(canvas)

        canvas.restoreToCount(saveCount)
    }

    private fun getOrCreateStaticLayout(item: TextItem): StaticLayout {
        val cached = textLayoutCache[item]
        if (cached != null &&
            cached.text == item.text &&
            cached.widthWorld == item.widthWorld &&
            cached.sizeWorld == item.sizeWorld
        ) {
            return cached.layout
        }

        textPaint.textSize = item.sizeWorld
        val targetWidth = max(1, item.widthWorld.toInt())

        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            item.text,
            textPaint,
            targetWidth,
            Layout.Alignment.ALIGN_NORMAL,
            1f,
            0f,
            false
        )

        textLayoutCache[item] = CachedLayout(
            item.text,
            item.widthWorld,
            item.sizeWorld,
            layout
        )

        return layout
    }
}
