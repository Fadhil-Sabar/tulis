package com.example.tulis.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.input.motionprediction.MotionEventPredictor
import com.example.tulis.ink.InkBridge
import com.example.tulis.ink.LiveInk
import com.example.tulis.ink.LiveInkListener
import com.example.tulis.ink.StrokeItem
import com.example.tulis.model.BrushKind
import com.example.tulis.model.NoteMode
import com.example.tulis.model.PageContent
import com.example.tulis.model.PageStack
import com.example.tulis.model.PaperStyle
import com.example.tulis.model.TextItem
import com.example.tulis.model.Tool
import com.example.tulis.model.Viewport
import com.example.tulis.ui.Palette
import kotlin.math.hypot
import kotlin.math.max

class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private enum class GestureState { NONE, DRAW, ERASE, PAN, PINCH }

    var listener: CanvasListener? = null
    var mode: NoteMode
        get() = canvasLayer.mode
        set(value) {
            canvasLayer.mode = value
        }

    var tool: Tool = Tool.PEN
    var colorArgb: Int = Palette.INK
    var widthWorld: Float = 4f
    var fingerDraws: Boolean = true
    var onStylusDetected: (() -> Unit)? = null
    var fitWholePage: Boolean = false

    val viewport: Viewport

    private val canvasLayer: CanvasLayer
    private val liveInk: LiveInk
    private val motionPredictor: MotionEventPredictor

    private var activeStrokeId: Any? = null
    private var activeStrokeMatrix: Matrix? = null
    private val finishedStrokeIds = ArrayList<Any>()

    private var gestureState = GestureState.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isStylusActive = false
    private var stylusReported = false
    private var isTemporaryEraser = false

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f

    private val touchSlop: Int
    private val scroller: OverScroller
    private var velocityTracker: VelocityTracker? = null
    private val scaleDetector: ScaleGestureDetector

    private val thumbnailRenderer = InkBridge.createRenderer()

    private val tmpMatrix = Matrix()
    private val tmpRectF = RectF()
    private val tmpPoints = FloatArray(2)

    private val eraseHitRadiusDp = 12f
    private val eraseBatch = ArrayList<StrokeItem>()
    private val erasePages = ArrayList<PageContent>()
    private val erasePageIndices = ArrayList<Int>()

    private var activeTargetPage: PageContent? = null
    private var textEditPage: PageContent? = null

    private var lastReportedVisiblePage = -1
    private var lastReportedWindowFirst = -1
    private var lastReportedWindowLast = -1

    private val tmpVisibleRect = RectF()
    private val tmpPageMatrix = Matrix()

    private var pendingFit = false
    private var hasUserMovedViewport = false
    val hasMovedViewport: Boolean
        get() = hasUserMovedViewport

    fun clearMovedViewport() {
        hasUserMovedViewport = false
    }

    private var tapCandidate = false
    private var tapStartTime = 0L
    private var tapPointerCount = 0
    private var tapPointersEverExceeded = false
    private val tapDownX = FloatArray(5)
    private val tapDownY = FloatArray(5)
    private val tapMaxDistancePx: Float
        get() = 10f * resources.displayMetrics.density

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val currX = scroller.currX.toFloat()
                val currY = scroller.currY.toFloat()
                val dx = currX - lastTouchX
                val dy = currY - lastTouchY
                lastTouchX = currX
                lastTouchY = currY

                viewport.pan(dx, dy)
                clampViewportIfNeeded()
                notifyViewportChanged()

                if (!scroller.isFinished) {
                    postOnAnimation(this)
                } else {
                    canvasLayer.setViewportGestureActive(false)
                }
            } else {
                canvasLayer.setViewportGestureActive(false)
            }
        }
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val focusX = detector.focusX
            val focusY = detector.focusY
            hasUserMovedViewport = true
            viewport.zoomAt(factor, focusX, focusY)
            clampViewportIfNeeded()
            notifyViewportChanged()
            return true
        }
    }

    private val liveInkListener = object : LiveInkListener {
        override fun onStrokeFinished(
            stroke: com.example.tulis.ink.InkStroke,
            kind: BrushKind,
            colorArgb: Int,
            sizePx: Float
        ) {
            val inv = activeStrokeMatrix ?: Matrix()
            val item = StrokeItem(
                stroke = stroke,
                brushKind = kind,
                colorArgb = colorArgb,
                sizePx = sizePx,
                strokeToWorld = inv
            )
            val s = canvasLayer.stack
            val p = if (s != null) activeTargetPage else canvasLayer.page
            if (p != null) {
                p.addStroke(item)
                if (s != null) {
                    canvasLayer.onStrokeAppended(p)
                } else {
                    canvasLayer.onStrokeAppended()
                }
                listener?.onContentChanged()
                listener?.onHistoryChanged()
            }
            activeTargetPage = null
            if (finishedStrokeIds.isNotEmpty()) {
                liveInk.clearFinished(finishedStrokeIds)
                finishedStrokeIds.clear()
            }
        }
    }

    init {
        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledTouchSlop
        scroller = OverScroller(context)
        scaleDetector = ScaleGestureDetector(context, scaleListener).apply {
            isQuickScaleEnabled = false
        }

        canvasLayer = CanvasLayer(context)
        viewport = canvasLayer.viewport

        liveInk = InkBridge.newLiveInk(context)
        liveInk.listener = liveInkListener

        motionPredictor = MotionEventPredictor.newInstance(this)

        addView(canvasLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(liveInk.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        viewport.onChanged = {
            canvasLayer.invalidate()
        }
    }

    fun setPage(page: PageContent?) {
        canvasLayer.page = page
        canvasLayer.invalidate()
    }

    fun getPage(): PageContent? = canvasLayer.page

    fun setStack(s: PageStack?) {
        canvasLayer.stack = s
        lastReportedVisiblePage = -1
        lastReportedWindowFirst = -1
        lastReportedWindowLast = -1
        canvasLayer.invalidate()
        notifyViewportChanged()
    }

    fun getStack(): PageStack? = canvasLayer.stack

    fun invalidatePages() {
        canvasLayer.invalidate()
    }

    fun scrollToPage(i: Int) {
        val s = canvasLayer.stack ?: return
        if (i < 0 || i >= s.pageCount) return
        val margin = 16f * resources.displayMetrics.density
        val targetOffsetY = s.offsetY(i)
        val scale = viewport.scale
        val topMargin = fitT + margin
        val newTy = topMargin - targetOffsetY * scale
        viewport.set(scale, viewport.tx, newTy)
        hasUserMovedViewport = true
        clampViewportIfNeeded()
        canvasLayer.invalidate()
        notifyViewportChanged()
    }

    fun visiblePageIndex(): Int {
        val s = canvasLayer.stack ?: return 0
        if (s.pageCount <= 0) return 0
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return 0
        viewport.visibleWorld(w, h, tmpVisibleRect)
        val range = s.visibleRange(tmpVisibleRect.top, tmpVisibleRect.bottom)
        if (range.isEmpty()) return 0
        var bestPage = range.first
        var bestOverlap = -1f
        for (i in range) {
            val pTop = s.offsetY(i)
            val pBottom = pTop + s.pageH
            val overlapTop = maxOf(pTop, tmpVisibleRect.top)
            val overlapBottom = minOf(pBottom, tmpVisibleRect.bottom)
            val overlap = overlapBottom - overlapTop
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestPage = i
            }
        }
        return bestPage
    }

    fun canUndo(): Boolean {
        val s = canvasLayer.stack
        return if (s != null) {
            s.canUndo()
        } else {
            canvasLayer.page?.canUndo() == true
        }
    }

    fun canRedo(): Boolean {
        val s = canvasLayer.stack
        return if (s != null) {
            s.canRedo()
        } else {
            canvasLayer.page?.canRedo() == true
        }
    }

    private fun notifyViewportChanged() {
        val s = canvasLayer.stack
        if (s != null) {
            val visIdx = visiblePageIndex()
            if (visIdx != lastReportedVisiblePage) {
                lastReportedVisiblePage = visIdx
                listener?.onVisiblePageChanged(visIdx)
            }
            val w = width
            val h = height
            if (w > 0 && h > 0 && s.pageCount > 0) {
                viewport.visibleWorld(w, h, tmpVisibleRect)
                val range = s.visibleRange(tmpVisibleRect.top, tmpVisibleRect.bottom)
                val first = if (range.isEmpty()) 0 else (range.first - 1).coerceAtLeast(0)
                val last = if (range.isEmpty()) 0 else (range.last + 1).coerceAtMost(s.pageCount - 1)
                if (first != lastReportedWindowFirst || last != lastReportedWindowLast) {
                    lastReportedWindowFirst = first
                    lastReportedWindowLast = last
                    listener?.onPageWindowChanged(first, last)
                }
            }
        }
        listener?.onViewportChanged()
    }

    fun setPaper(style: PaperStyle, spacing: Float) {
        canvasLayer.setPaper(style, spacing)
    }

    fun centerWorldOn(wx: Float, wy: Float) {
        if (mode == NoteMode.INFINITE) {
            val scale = viewport.scale
            val tx = width / 2f - wx * scale
            val ty = height / 2f - wy * scale
            viewport.set(scale, tx, ty)
            hasUserMovedViewport = true
            canvasLayer.invalidate()
            notifyViewportChanged()
        }
    }

    fun applyTextEdit(item: TextItem?, wx: Float, wy: Float, text: String) {
        val s = canvasLayer.stack
        val p = if (s != null) (textEditPage ?: return) else (canvasLayer.page ?: return)
        if (item != null) {
            if (text.isEmpty()) {
                p.removeText(item)
            } else {
                p.editText(item, text)
            }
        } else {
            if (text.isNotEmpty()) {
                val newItem = TextItem(
                    x = wx,
                    y = wy,
                    sizeWorld = 28f,
                    colorArgb = colorArgb,
                    widthWorld = 400f,
                    text = text
                )
                p.addText(newItem)
            }
        }
        textEditPage = null
        listener?.onContentChanged()
        listener?.onHistoryChanged()
        if (s != null) {
            canvasLayer.invalidateContent(p)
        } else {
            canvasLayer.invalidate()
        }
    }

    fun undo() {
        val s = canvasLayer.stack
        if (s != null) {
            val p = s.undo() ?: return
            canvasLayer.invalidateContent(p)
            listener?.onContentChanged()
            listener?.onHistoryChanged()
        } else {
            val p = canvasLayer.page ?: return
            if (p.undo()) {
                canvasLayer.invalidateContent()
                listener?.onContentChanged()
                listener?.onHistoryChanged()
            }
        }
    }

    fun redo() {
        val s = canvasLayer.stack
        if (s != null) {
            val p = s.redo() ?: return
            canvasLayer.invalidateContent(p)
            listener?.onContentChanged()
            listener?.onHistoryChanged()
        } else {
            val p = canvasLayer.page ?: return
            if (p.redo()) {
                canvasLayer.invalidateContent()
                listener?.onContentChanged()
                listener?.onHistoryChanged()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (pendingFit) {
                pendingFit = false
                fitPage()
            } else if (mode == NoteMode.PAGED && !hasUserMovedViewport && (oldw != w || oldh != h)) {
                fitPage()
            } else if (canvasLayer.stack != null) {
                notifyViewportChanged()
            }
        }
    }

    fun toggleFitMode() {
        if (mode == NoteMode.PAGED) {
            fitWholePage = !fitWholePage
            fitPage()
        } else {
            viewport.resetCenter(width, height)
            hasUserMovedViewport = false
            canvasLayer.invalidate()
            notifyViewportChanged()
        }
    }

    private var fitL = 0
    private var fitT = 0
    private var fitR = 0
    private var fitB = 0

    fun setFitInsets(l: Int, t: Int, r: Int, b: Int) {
        fitL = l
        fitT = t
        fitR = r
        fitB = b
    }

    fun fitPage() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            pendingFit = true
            return
        }
        pendingFit = false
        val s = canvasLayer.stack
        if (mode == NoteMode.PAGED) {
            val margin = 16f * resources.displayMetrics.density
            val areaW = (w - fitL - fitR).toFloat()
            val areaH = (h - fitT - fitB).toFloat()
            if (fitWholePage) {
                val availW = (areaW - 2f * margin).coerceAtLeast(1f)
                val availH = (areaH - 2f * margin).coerceAtLeast(1f)
                val newScale = minOf(availW / canvasLayer.pageW, availH / canvasLayer.pageH).coerceIn(0.08f, 16f)
                val newTx = fitL + (areaW - canvasLayer.pageW * newScale) * 0.5f
                val newTy = if (s != null) {
                    val visIdx = visiblePageIndex()
                    val pTop = s.offsetY(visIdx)
                    fitT + (areaH - canvasLayer.pageH * newScale) * 0.5f - pTop * newScale
                } else {
                    fitT + (areaH - canvasLayer.pageH * newScale) * 0.5f
                }
                viewport.set(newScale, newTx, newTy)
                clampViewportIfNeeded()
            } else {
                val targetPageWidth = (areaW - 2f * margin).coerceAtLeast(1f)
                val newScale = (targetPageWidth / canvasLayer.pageW).coerceIn(0.08f, 16f)
                val newTx = fitL + (areaW - canvasLayer.pageW * newScale) * 0.5f
                val topMargin = if (fitT > 0) fitT + margin else 130f * resources.displayMetrics.density
                val newTy = if (s != null) {
                    val visIdx = visiblePageIndex()
                    val pTop = s.offsetY(visIdx)
                    topMargin - pTop * newScale
                } else {
                    val pageHeightPx = canvasLayer.pageH * newScale
                    if (pageHeightPx + topMargin <= h) topMargin else 0f
                }
                viewport.set(newScale, newTx, newTy)
                clampViewportIfNeeded()
            }
        } else {
            viewport.resetCenter(w, h)
        }
        hasUserMovedViewport = false
        canvasLayer.invalidate()
        notifyViewportChanged()
    }

    fun renderPageThumbnail(page: PageContent, sizePx: Int): Bitmap {
        val targetH = sizePx * 130 / 170
        val bmp = Bitmap.createBitmap(sizePx, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Palette.KRAFT)

        val sheetH = (targetH - 16).coerceAtLeast(1).toFloat()
        val sheetW = sheetH * (canvasLayer.pageW / canvasLayer.pageH)
        val left = (sizePx - sheetW) / 2f
        val top = (targetH - sheetH) / 2f
        val pageRect = RectF(left, top, left + sheetW, top + sheetH)

        paint.style = Paint.Style.FILL
        paint.color = Palette.SHEET
        canvas.drawRect(pageRect, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = Palette.INK
        canvas.drawRect(pageRect, paint)

        if (page.strokes.isNotEmpty() || page.texts.isNotEmpty()) {
            val scale = sheetW / canvasLayer.pageW
            val drawMatrix = Matrix()
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(left, top)

            val saveCount = canvas.save()
            canvas.clipRect(pageRect)

            val tempMat = Matrix()
            for (stroke in page.strokes) {
                InkBridge.draw(thumbnailRenderer, canvas, stroke, drawMatrix, tempMat)
            }

            paint.style = Paint.Style.FILL
            for (text in page.texts) {
                paint.color = text.colorArgb
                paint.textSize = text.sizeWorld * scale
                val tx = left + text.x * scale
                val ty = top + text.y * scale + paint.textSize
                canvas.drawText(text.text, tx, ty, paint)
            }

            canvas.restoreToCount(saveCount)
        }

        return bmp
    }

    fun renderThumbnail(sizePx: Int): Bitmap {
        val targetH = sizePx * 130 / 170
        val bmp = Bitmap.createBitmap(sizePx, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = canvasLayer.page

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (mode == NoteMode.PAGED) {
            canvas.drawColor(Palette.KRAFT)

            val sheetH = (targetH - 16).coerceAtLeast(1).toFloat()
            val sheetW = sheetH * (canvasLayer.pageW / canvasLayer.pageH)
            val left = (sizePx - sheetW) / 2f
            val top = (targetH - sheetH) / 2f
            val pageRect = RectF(left, top, left + sheetW, top + sheetH)

            paint.style = Paint.Style.FILL
            paint.color = Palette.SHEET
            canvas.drawRect(pageRect, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Palette.INK
            canvas.drawRect(pageRect, paint)

            if (p != null && (p.strokes.isNotEmpty() || p.texts.isNotEmpty())) {
                val scale = sheetW / canvasLayer.pageW
                val drawMatrix = Matrix()
                drawMatrix.setScale(scale, scale)
                drawMatrix.postTranslate(left, top)

                val saveCount = canvas.save()
                canvas.clipRect(pageRect)

                val tempMat = Matrix()
                for (stroke in p.strokes) {
                    InkBridge.draw(thumbnailRenderer, canvas, stroke, drawMatrix, tempMat)
                }

                paint.style = Paint.Style.FILL
                for (text in p.texts) {
                    paint.color = text.colorArgb
                    paint.textSize = text.sizeWorld * scale
                    val tx = left + text.x * scale
                    val ty = top + text.y * scale + paint.textSize
                    canvas.drawText(text.text, tx, ty, paint)
                }

                canvas.restoreToCount(saveCount)
            }
        } else {
            canvas.drawColor(Palette.CARD)

            paint.style = Paint.Style.FILL
            paint.color = Palette.DOT_CARD
            val dotSpacing = 14f
            val dotRadius = 1.2f
            var dx = dotSpacing / 2f
            while (dx < sizePx) {
                var dy = dotSpacing / 2f
                while (dy < targetH) {
                    canvas.drawCircle(dx, dy, dotRadius, paint)
                    dy += dotSpacing
                }
                dx += dotSpacing
            }

            if (p != null) {
                var hasContent = false
                val contentRect = RectF()
                val bounds = RectF()

                for (stroke in p.strokes) {
                    if (stroke.worldBounds(bounds)) {
                        if (!hasContent) {
                            contentRect.set(bounds)
                            hasContent = true
                        } else {
                            contentRect.union(bounds)
                        }
                    }
                }
                for (text in p.texts) {
                    text.worldBounds(bounds)
                    if (!hasContent) {
                        contentRect.set(bounds)
                        hasContent = true
                    } else {
                        contentRect.union(bounds)
                    }
                }

                if (hasContent) {
                    val rw = max(1f, contentRect.width())
                    val rh = max(1f, contentRect.height())
                    val margin = 0.10f
                    val availW = sizePx * (1f - 2f * margin)
                    val availH = targetH * (1f - 2f * margin)
                    val scale = minOf(availW / rw, availH / rh)

                    val scaledW = rw * scale
                    val scaledH = rh * scale
                    val tx = (sizePx - scaledW) / 2f - contentRect.left * scale
                    val ty = (targetH - scaledH) / 2f - contentRect.top * scale

                    val drawMatrix = Matrix()
                    drawMatrix.setScale(scale, scale)
                    drawMatrix.postTranslate(tx, ty)

                    val tempMat = Matrix()
                    for (stroke in p.strokes) {
                        InkBridge.draw(thumbnailRenderer, canvas, stroke, drawMatrix, tempMat)
                    }

                    paint.style = Paint.Style.FILL
                    for (text in p.texts) {
                        paint.color = text.colorArgb
                        paint.textSize = text.sizeWorld * scale
                        val cx = text.x * scale + tx
                        val cy = text.y * scale + ty + paint.textSize
                        canvas.drawText(text.text, cx, cy, paint)
                    }
                }
            }
        }

        return bmp
    }

    fun release() {
        cancelFling()
        recycleVelocityTracker()
        activeStrokeId?.let { id ->
            liveInk.cancel(null, id)
            activeStrokeId = null
        }
        finishedStrokeIds.clear()
        canvasLayer.trimCache()
        canvasLayer.page = null
        canvasLayer.stack = null
        activeTargetPage = null
        textEditPage = null
        erasePages.clear()
        erasePageIndices.clear()
        listener = null
    }

    private fun checkStylusDetected(toolType: Int) {
        if (!stylusReported && (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER)) {
            stylusReported = true
            onStylusDetected?.invoke()
        }
    }

    private fun isStylusTool(toolType: Int): Boolean {
        return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isPalmTouch(event: MotionEvent, pointerIndex: Int): Boolean {
        val toolType = event.getToolType(pointerIndex)
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            val touchMajor = event.getTouchMajor(pointerIndex)
            val thresholdPx = 40f * resources.displayMetrics.density
            if (touchMajor > thresholdPx) {
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        for (i in 0 until event.pointerCount) {
            checkStylusDetected(event.getToolType(i))
        }

        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                if (action == MotionEvent.ACTION_CANCEL || (activePointerId != MotionEvent.INVALID_POINTER_ID && event.getPointerId(actionIndex) == activePointerId)) {
                    handleTouchCancel(event)
                    endGesture()
                    return true
                }
            }
        }

        if (isStylusActive) {
            val activeIndex = event.findPointerIndex(activePointerId)
            if (activeIndex >= 0) {
                val activeToolType = event.getToolType(activeIndex)
                if (!isStylusTool(activeToolType)) {
                    isStylusActive = false
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isStylusActive = false
            }
        }

        if (!isStylusActive) {
            scaleDetector.onTouchEvent(event)
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                tapCandidate = false
                tapPointersEverExceeded = false
                tapPointerCount = 0
                if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    tapCandidate = true
                    tapStartTime = event.eventTime
                    tapPointerCount = 1
                    tapDownX[0] = event.x
                    tapDownY[0] = event.y
                }

                cancelFling()
                if (isPalmTouch(event, 0)) {
                    tapCandidate = false
                    gestureState = GestureState.NONE
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    isStylusActive = false
                    return true
                }

                val toolType = event.getToolType(0)
                val isStylus = isStylusTool(toolType)
                if (isStylus) {
                    tapCandidate = false
                }
                activePointerId = event.getPointerId(0)
                isStylusActive = isStylus
                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y

                initOrResetVelocityTracker()
                velocityTracker?.addMovement(event)

                handleTouchDown(event, isStylus, toolType, 0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val newPointerIndex = actionIndex
                val newToolType = event.getToolType(newPointerIndex)
                val newIsStylus = isStylusTool(newToolType)

                if (newIsStylus) {
                    tapCandidate = false
                    if (!isStylusActive) {
                        handleTouchCancel(event)
                        endGesture()
                    }
                    cancelFling()
                    activePointerId = event.getPointerId(newPointerIndex)
                    isStylusActive = true
                    lastTouchX = event.getX(newPointerIndex)
                    lastTouchY = event.getY(newPointerIndex)
                    downTouchX = lastTouchX
                    downTouchY = lastTouchY

                    initOrResetVelocityTracker()
                    velocityTracker?.addMovement(event)

                    handleTouchDown(event, true, newToolType, newPointerIndex)
                } else {
                    if (isStylusActive) {
                        tapCandidate = false
                    } else {
                        if (tapCandidate) {
                            val count = event.pointerCount
                            if (count in 2..3 && !tapPointersEverExceeded) {
                                tapPointerCount = maxOf(tapPointerCount, count)
                                val maxIdx = minOf(count - 1, 4)
                                for (i in 0..maxIdx) {
                                    tapDownX[i] = event.getX(i)
                                    tapDownY[i] = event.getY(i)
                                }
                            } else {
                                tapCandidate = false
                                tapPointersEverExceeded = true
                            }
                        }
                        if (!isPalmTouch(event, newPointerIndex) && event.pointerCount >= 2) {
                            switchToPinch(event)
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tapCandidate) {
                    val count = event.pointerCount
                    if (count > 3 || tapPointersEverExceeded) {
                        tapCandidate = false
                    } else {
                        val maxDist = tapMaxDistancePx
                        val maxIdx = minOf(count - 1, 4)
                        for (i in 0..maxIdx) {
                            val dx = event.getX(i) - tapDownX[i]
                            val dy = event.getY(i) - tapDownY[i]
                            if (hypot(dx.toDouble(), dy.toDouble()) > maxDist) {
                                tapCandidate = false
                                break
                            }
                        }
                    }
                }
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val toolType = event.getToolType(pointerIndex)
                        if (isStylusTool(toolType)) {
                            val buttonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                            val targetEraser = buttonPressed || toolType == MotionEvent.TOOL_TYPE_ERASER || tool == Tool.ERASER
                            val sx = event.getX(pointerIndex)
                            val sy = event.getY(pointerIndex)
                            screenToWorld(sx, sy, tmpPoints)
                            val wx = tmpPoints[0]
                            val wy = tmpPoints[1]

                            if (targetEraser && gestureState == GestureState.DRAW) {
                                motionPredictor.record(event)
                                activeStrokeId?.let { id ->
                                    liveInk.cancel(event, id)
                                    activeStrokeId = null
                                }
                                startErasing(wx, wy)
                                isTemporaryEraser = buttonPressed
                            } else if (!targetEraser && gestureState == GestureState.ERASE && isTemporaryEraser) {
                                eraseBatch.clear()
                                endEraseGroups()
                                isTemporaryEraser = false
                                if (tool == Tool.PEN || tool == Tool.MARKER || tool == Tool.HIGHLIGHTER) {
                                    startDrawing(event, pointerIndex, wx, wy)
                                } else {
                                    gestureState = GestureState.NONE
                                }
                            }
                        }
                    }
                }
                velocityTracker?.addMovement(event)
                handleTouchMove(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = actionIndex
                val upPointerId = event.getPointerId(upIndex)

                if (tapCandidate) {
                    val duration = event.eventTime - tapStartTime
                    if (duration < 250L && (tapPointerCount == 2 || tapPointerCount == 3) && !tapPointersEverExceeded) {
                        val maxDist = tapMaxDistancePx
                        var ok = true
                        val maxIdx = minOf(event.pointerCount - 1, 4)
                        for (i in 0..maxIdx) {
                            val dx = event.getX(i) - tapDownX[i]
                            val dy = event.getY(i) - tapDownY[i]
                            if (hypot(dx.toDouble(), dy.toDouble()) > maxDist) {
                                ok = false
                                break
                            }
                        }
                        if (ok) {
                            val triggeredCount = tapPointerCount
                            tapCandidate = false
                            if (gestureState == GestureState.DRAW) {
                                motionPredictor.record(event)
                                activeStrokeId?.let { id ->
                                    liveInk.cancel(event, id)
                                    activeStrokeId = null
                                }
                            } else if (gestureState == GestureState.ERASE) {
                                eraseBatch.clear()
                                endEraseGroups()
                            }
                            canvasLayer.setViewportGestureActive(false)
                            endGesture()
                            if (triggeredCount == 2) {
                                undo()
                            } else {
                                redo()
                            }
                            return true
                        }
                    }
                    tapCandidate = false
                }

                if (gestureState == GestureState.PINCH) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    if (event.pointerCount <= 2) {
                        activePointerId = event.getPointerId(newIndex)
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                } else if (upPointerId == activePointerId) {
                    if (gestureState == GestureState.DRAW || gestureState == GestureState.ERASE) {
                        handleTouchUp(event)
                        endGesture()
                    } else if (gestureState == GestureState.PAN) {
                        var nextIndex = -1
                        for (i in 0 until event.pointerCount) {
                            if (i != upIndex) {
                                nextIndex = i
                                break
                            }
                        }
                        if (nextIndex >= 0) {
                            activePointerId = event.getPointerId(nextIndex)
                            lastTouchX = event.getX(nextIndex)
                            lastTouchY = event.getY(nextIndex)
                        } else {
                            canvasLayer.setViewportGestureActive(false)
                            endGesture()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                tapCandidate = false
                velocityTracker?.addMovement(event)
                handleTouchUp(event)
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                tapCandidate = false
                handleTouchCancel(event)
                endGesture()
            }
        }

        return true
    }

    private fun handleTouchDown(event: MotionEvent, isStylus: Boolean, toolType: Int, pointerIndex: Int) {
        val sx = event.getX(pointerIndex)
        val sy = event.getY(pointerIndex)
        screenToWorld(sx, sy, tmpPoints)
        val wx = tmpPoints[0]
        val wy = tmpPoints[1]

        if (isStylus) {
            val buttonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            if (toolType == MotionEvent.TOOL_TYPE_ERASER || buttonPressed) {
                isTemporaryEraser = buttonPressed
                startErasing(wx, wy)
            } else {
                when (tool) {
                    Tool.ERASER -> startErasing(wx, wy)
                    Tool.TEXT -> {
                        gestureState = GestureState.NONE
                    }
                    Tool.PAN -> {
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                    Tool.PEN, Tool.MARKER, Tool.HIGHLIGHTER -> {
                        startDrawing(event, pointerIndex, wx, wy)
                    }
                }
            }
        } else {
            when (tool) {
                Tool.PAN -> {
                    gestureState = GestureState.PAN
                    canvasLayer.setViewportGestureActive(true)
                }
                Tool.TEXT -> {
                    gestureState = GestureState.NONE
                }
                Tool.ERASER -> {
                    if (fingerDraws) {
                        startErasing(wx, wy)
                    } else {
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                }
                Tool.PEN, Tool.MARKER, Tool.HIGHLIGHTER -> {
                    if (fingerDraws) {
                        startDrawing(event, pointerIndex, wx, wy)
                    } else {
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                }
            }
        }
    }

    private fun startDrawing(event: MotionEvent, pointerIndex: Int, wx: Float, wy: Float) {
        val s = canvasLayer.stack
        val inv = Matrix()
        if (s != null) {
            val hitIdx = canvasLayer.hitPage(wx, wy, tmpPoints)
            if (hitIdx < 0) {
                gestureState = GestureState.NONE
                return
            }
            val targetPage = s.get(hitIdx)
            if (targetPage == null) {
                gestureState = GestureState.NONE
                return
            }
            activeTargetPage = targetPage
            canvasLayer.pageToScreen(hitIdx, tmpPageMatrix)
            if (!tmpPageMatrix.invert(inv)) {
                inv.reset()
            }
        } else {
            if (mode == NoteMode.PAGED && !canvasLayer.insidePage(wx, wy)) {
                gestureState = GestureState.NONE
                return
            }
            activeTargetPage = null
            viewport.worldToScreen.invert(inv)
        }

        val brushKind = when (tool) {
            Tool.MARKER -> BrushKind.MARKER
            Tool.HIGHLIGHTER -> BrushKind.HIGHLIGHTER
            else -> BrushKind.PEN
        }
        val currentScale = viewport.scale
        val strokeSizePx = widthWorld * currentScale

        activeStrokeMatrix = inv

        motionPredictor.record(event)

        val id = liveInk.start(
            event = event,
            pointerId = event.getPointerId(pointerIndex),
            kind = brushKind,
            colorArgb = colorArgb,
            sizePx = strokeSizePx
        )
        activeStrokeId = id
        gestureState = GestureState.DRAW
    }

    private fun startErasing(wx: Float, wy: Float) {
        val s = canvasLayer.stack
        if (s != null) {
            eraseBatch.clear()
            erasePages.clear()
            erasePageIndices.clear()
            gestureState = GestureState.ERASE
            performEraseAt(wx, wy)
        } else {
            val p = canvasLayer.page
            if (p != null) {
                p.beginEraseGroup()
                eraseBatch.clear()
                gestureState = GestureState.ERASE
                performEraseAt(wx, wy)
            } else {
                gestureState = GestureState.NONE
            }
        }
    }

    private fun switchToPinch(event: MotionEvent) {
        hasUserMovedViewport = true
        if (gestureState == GestureState.DRAW) {
            motionPredictor.record(event)
            activeStrokeId?.let { id ->
                liveInk.cancel(event, id)
                activeStrokeId = null
            }
        } else if (gestureState == GestureState.ERASE) {
            eraseBatch.clear()
            endEraseGroups()
        }
        gestureState = GestureState.PINCH
        canvasLayer.setViewportGestureActive(true)
    }

    private fun handleTouchMove(event: MotionEvent) {
        when (gestureState) {
            GestureState.DRAW -> {
                val id = activeStrokeId
                if (id != null) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        motionPredictor.record(event)
                        val predicted = motionPredictor.predict()
                        liveInk.add(event, activePointerId, id, predicted)
                    }
                }
            }

            GestureState.ERASE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    val sx = event.getX(pointerIndex)
                    val sy = event.getY(pointerIndex)
                    screenToWorld(sx, sy, tmpPoints)
                    performEraseAt(tmpPoints[0], tmpPoints[1])
                }
            }

            GestureState.PAN -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    val sx = event.getX(pointerIndex)
                    val sy = event.getY(pointerIndex)
                    val dx = sx - lastTouchX
                    val dy = sy - lastTouchY
                    lastTouchX = sx
                    lastTouchY = sy

                    hasUserMovedViewport = true
                    viewport.pan(dx, dy)
                    clampViewportIfNeeded()
                    notifyViewportChanged()
                }
            }

            GestureState.PINCH -> {
            }

            GestureState.NONE -> {
                if (tool == Tool.TEXT) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val sx = event.getX(pointerIndex)
                        val sy = event.getY(pointerIndex)
                        if (hypot((sx - downTouchX).toDouble(), (sy - downTouchY).toDouble()) > touchSlop) {
                        }
                    }
                }
            }
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
        when (gestureState) {
            GestureState.DRAW -> {
                val id = activeStrokeId
                if (id != null) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        motionPredictor.record(event)
                        finishedStrokeIds.add(id)
                        liveInk.finish(event, activePointerId, id)
                    } else {
                        liveInk.cancel(event, id)
                    }
                    activeStrokeId = null
                }
            }

            GestureState.ERASE -> {
                eraseBatch.clear()
                endEraseGroups()
            }

            GestureState.PAN -> {
                val vt = velocityTracker
                if (vt != null) {
                    vt.computeCurrentVelocity(1000)
                    val vx = vt.xVelocity.toInt()
                    val vy = vt.yVelocity.toInt()
                    startFling(vx, vy)
                }
                if (scroller.isFinished) {
                    canvasLayer.setViewportGestureActive(false)
                }
            }

            GestureState.NONE -> {
                if (tool == Tool.TEXT) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val sx = event.getX(pointerIndex)
                        val sy = event.getY(pointerIndex)
                        val dist = hypot((sx - downTouchX).toDouble(), (sy - downTouchY).toDouble())
                        if (dist <= touchSlop) {
                            screenToWorld(sx, sy, tmpPoints)
                            val wx = tmpPoints[0]
                            val wy = tmpPoints[1]
                            val s = canvasLayer.stack
                            if (s != null) {
                                val hitIdx = canvasLayer.hitPage(wx, wy, tmpPoints)
                                if (hitIdx >= 0) {
                                    val targetPage = s.get(hitIdx)
                                    if (targetPage != null) {
                                        val lx = tmpPoints[0]
                                        val ly = tmpPoints[1]
                                        val hitItem = hitTestTextInPage(targetPage, lx, ly)
                                        textEditPage = targetPage
                                        listener?.onRequestTextEdit(hitItem, lx, ly)
                                    }
                                }
                            } else {
                                textEditPage = null
                                val hitItem = hitTestText(wx, wy)
                                listener?.onRequestTextEdit(hitItem, wx, wy)
                            }
                        }
                    }
                }
            }

            GestureState.PINCH -> {
            }
        }
    }

    private fun handleTouchCancel(event: MotionEvent) {
        when (gestureState) {
            GestureState.DRAW -> {
                motionPredictor.record(event)
                activeStrokeId?.let { id ->
                    liveInk.cancel(event, id)
                    activeStrokeId = null
                }
            }

            GestureState.ERASE -> {
                eraseBatch.clear()
                endEraseGroups()
            }

            GestureState.PAN, GestureState.PINCH, GestureState.NONE -> {
                canvasLayer.setViewportGestureActive(false)
            }
        }
    }

    private fun endGesture() {
        tapCandidate = false
        gestureState = GestureState.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isStylusActive = false
        isTemporaryEraser = false
        recycleVelocityTracker()
    }

    private fun endEraseGroups() {
        val s = canvasLayer.stack
        if (s != null) {
            val count = erasePages.size
            if (count > 0) {
                for (i in 0 until count) {
                    erasePages[i].endEraseGroup()
                }
                erasePages.clear()
                erasePageIndices.clear()
                listener?.onHistoryChanged()
            }
        } else {
            val p = canvasLayer.page
            if (p != null) {
                p.endEraseGroup()
                listener?.onHistoryChanged()
            }
        }
    }

    private fun performEraseAt(wx: Float, wy: Float) {
        val s = canvasLayer.stack
        val p: PageContent
        val localX: Float
        val localY: Float
        if (s != null) {
            val hitIdx = canvasLayer.hitPage(wx, wy, tmpPoints)
            if (hitIdx < 0) return
            val page = s.get(hitIdx) ?: return
            p = page
            localX = tmpPoints[0]
            localY = tmpPoints[1]
            if (!erasePageIndices.contains(hitIdx)) {
                erasePageIndices.add(hitIdx)
                erasePages.add(p)
                p.beginEraseGroup()
            }
        } else {
            val page = canvasLayer.page ?: return
            p = page
            localX = wx
            localY = wy
        }

        val currentScale = viewport.scale
        val radiusWorld = (eraseHitRadiusDp * resources.displayMetrics.density) / currentScale

        eraseBatch.clear()
        val strokes = p.strokes
        val count = strokes.size
        for (i in 0 until count) {
            val stroke = strokes[i]
            if (stroke.hitTest(localX, localY, radiusWorld)) {
                eraseBatch.add(stroke)
            }
        }

        if (eraseBatch.isNotEmpty()) {
            p.removeStrokes(eraseBatch)
            eraseBatch.clear()
            if (s != null) {
                canvasLayer.invalidateContent(p)
            } else {
                canvasLayer.invalidateContent()
            }
            listener?.onContentChanged()
        }
    }

    private fun hitTestText(wx: Float, wy: Float): TextItem? {
        val p = canvasLayer.page ?: return null
        return hitTestTextInPage(p, wx, wy)
    }

    private fun hitTestTextInPage(page: PageContent, lx: Float, ly: Float): TextItem? {
        val bounds = tmpRectF
        for (i in page.texts.indices.reversed()) {
            val text = page.texts[i]
            text.worldBounds(bounds)
            if (bounds.contains(lx, ly)) {
                return text
            }
        }
        return null
    }

    private fun screenToWorld(sx: Float, sy: Float, out: FloatArray) {
        out[0] = sx
        out[1] = sy
        viewport.screenToWorld.mapPoints(out)
    }

    private fun clampViewportIfNeeded() {
        if (mode == NoteMode.PAGED) {
            val s = canvasLayer.stack
            val totalH = s?.totalHeight() ?: canvasLayer.pageH
            viewport.clampToPage(width, height, canvasLayer.pageW, totalH)
        }
    }

    private fun startFling(vx: Int, vy: Int) {
        cancelFling()
        lastTouchX = 0f
        lastTouchY = 0f
        scroller.fling(0, 0, vx, vy, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        if (!scroller.isFinished) {
            canvasLayer.setViewportGestureActive(true)
            postOnAnimation(flingRunnable)
        }
    }

    private fun cancelFling() {
        if (!scroller.isFinished) {
            scroller.abortAnimation()
            canvasLayer.setViewportGestureActive(false)
        }
        removeCallbacks(flingRunnable)
    }

    private fun initOrResetVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        } else {
            velocityTracker?.clear()
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
