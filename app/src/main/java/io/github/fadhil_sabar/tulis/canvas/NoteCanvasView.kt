package io.github.fadhil_sabar.tulis.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.input.motionprediction.MotionEventPredictor
import io.github.fadhil_sabar.tulis.ink.InkBridge
import io.github.fadhil_sabar.tulis.ink.InkStroke
import io.github.fadhil_sabar.tulis.ink.forEachInDrawOrder
import io.github.fadhil_sabar.tulis.ink.LiveInk
import io.github.fadhil_sabar.tulis.ink.LiveInkListener
import io.github.fadhil_sabar.tulis.ink.StrokeItem
import io.github.fadhil_sabar.tulis.model.BrushKind
import io.github.fadhil_sabar.tulis.model.NoteMode
import io.github.fadhil_sabar.tulis.model.PageContent
import io.github.fadhil_sabar.tulis.model.PageStack
import io.github.fadhil_sabar.tulis.model.PaperStyle
import io.github.fadhil_sabar.tulis.model.TextItem
import io.github.fadhil_sabar.tulis.model.Tool
import io.github.fadhil_sabar.tulis.model.Viewport
import io.github.fadhil_sabar.tulis.select.Lasso
import io.github.fadhil_sabar.tulis.select.SelectionMath
import io.github.fadhil_sabar.tulis.shape.ShapeKind
import io.github.fadhil_sabar.tulis.shape.ShapeOutline
import io.github.fadhil_sabar.tulis.shape.ShapeRecognizer
import io.github.fadhil_sabar.tulis.shape.ShapeSpec
import io.github.fadhil_sabar.tulis.shape.SnapAdjust
import io.github.fadhil_sabar.tulis.ui.Icon
import io.github.fadhil_sabar.tulis.ui.Icons
import io.github.fadhil_sabar.tulis.ui.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private enum class GestureState {
        NONE, DRAW, ERASE, PAN, PINCH, SHAPE_HOLD, SHAPE_DRAG, SHAPE_EDIT_MOVE, SHAPE_EDIT_RESIZE, SHAPE_EDIT_ROTATE, SELECT_LASSO, SELECT_MOVE, SELECT_RESIZE, SELECT_ROTATE
    }

    private enum class SelectionEditMode {
        MOVE, RESIZE, ROTATE
    }

    private class PendingShape(
        val brushKind: BrushKind,
        val colorArgb: Int,
        val sizePx: Float,
        val pressure: Float,
        val inv: Matrix,
        val page: PageContent?,
        var spec: ShapeSpec,
        var rotated: Boolean = false,
        var rotationDeg: Float = 0f
    ) {
        var preview: InkStroke = build()

        fun update(next: ShapeSpec) {
            spec = next
            preview = build()
        }

        private fun build(): InkStroke =
            InkBridge.strokeFromPoints(brushKind, colorArgb, sizePx, ShapeOutline.outline(spec), pressure)
    }

    var listener: CanvasListener? = null
    var mode: NoteMode
        get() = canvasLayer.mode
        set(value) {
            canvasLayer.mode = value
        }

    var tool: Tool = Tool.PEN
        set(value) {
            if (value != field) {
                commitPendingShape()
                clearSelection()
            }
            field = value
        }
    var colorArgb: Int = Palette.INK
    var widthWorld: Float = 4f
    var fingerDraws: Boolean = true
    var onStylusDetected: (() -> Unit)? = null
    var fitWholePage: Boolean = false
    var shapeKind: ShapeKind = ShapeKind.RECT
    var snapShapes: Boolean = true

    val viewport: Viewport

    private val canvasLayer: CanvasLayer
    private val liveInk: LiveInk
    private val motionPredictor: MotionEventPredictor

    private var activeStrokeId: Any? = null
    private class StrokeCtx(val matrix: Matrix, val page: PageContent?)
    private val strokeCtx = HashMap<Any, StrokeCtx>()
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

    private var pending: PendingShape? = null
    private var snapAdjust: SnapAdjust? = null
    private var holdBuffer = FloatArray(256)
    private var holdCount = 0
    private var holdPressureSum = 0f
    private var holdPressureCount = 0
    private var holdRestX = 0f
    private var holdRestY = 0f
    private var drawBrushKind = BrushKind.PEN
    private var drawColorArgb = Palette.INK
    private var drawSizePx = 0f
    private val holdRunnable = Runnable { onHoldFired() }
    private val holdDelayMs = 500L
    private val holdMoveDp = 6f
    private val snapToleranceDp = 12f
    private val dragPressure = 0.5f
    private val shapeInv = Matrix()
    private var shapeTargetPage: PageContent? = null
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapeLastX = 0f
    private var shapeLastY = 0f
    private var editStartSpec: ShapeSpec? = null
    private var editStartRotated = false
    private var editStartRotationDeg = 0f
    private var editRotateStarted = false
    private var editRotateBaseAngle: Float? = null
    private var editRotateSnapKind = 0
    private var editRotateLastDeg = 0f
    private var editAnchorX = 0f
    private var editAnchorY = 0f
    private var editRefX = 0f
    private var editRefY = 0f
    private var editDownX = 0f
    private var editDownY = 0f
    private val editPadDp = 6f
    private val handleSizeDp = 10f
    private val handleHitDp = 20f
    private val minShapeScale = 0.05f
    private val tmpBounds = FloatArray(4)
    private val shapeFrame = RectF()
    private val shapeRotateCenter = FloatArray(2)
    private val shapeRotateIconRect = RectF()
    private var shapeRotateOnLeft = false
    private val identityMatrix = Matrix()
    private val shapeRenderer = InkBridge.createRenderer()
    private val density = resources.displayMetrics.density
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.INK_SOFT
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.SHEET
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.INK_SOFT
        strokeWidth = 1.5f * density
    }

    private var selPage: PageContent? = null
    private val selStrokes = ArrayList<StrokeItem>()
    private val selTexts = ArrayList<TextItem>()
    private val selScreenRect = RectF()
    private val tmpUnionRect = RectF()
    private val selDeleteCenter = FloatArray(2)
    private val selDeleteIconRect = RectF()
    private val selRotateCenter = FloatArray(2)
    private val selRotateIconRect = RectF()
    private var selRotateOnLeft = false
    private var pendingDeleteTap = false
    private val deleteBtnVisualSizeDp = 36f
    private val deleteBtnIconSizeDp = 20f
    private val deleteBtnHitHalfDp = 20f
    private val deleteBtnGapDp = 10f
    private val deleteBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.RED
        strokeWidth = 2f * density
    }
    private val rotateBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.INK
        strokeWidth = 2f * density
    }

    private var lassoPage: PageContent? = null
    private var lassoPageOffsetY = 0f
    private var lassoStartSx = 0f
    private var lassoStartSy = 0f
    private var lassoLastSx = 0f
    private var lassoLastSy = 0f
    private var lassoMaxDist = 0f
    private var lassoBuffer = FloatArray(256)
    private var lassoCount = 0
    private val lassoPath = Path()
    private val lassoPointStepDp = 3f
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = (Palette.INK and 0x00FFFFFF) or (0x99 shl 24)
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var movePage: PageContent? = null
    private var moveDownSx = 0f
    private var moveDownSy = 0f
    private var moveStarted = false
    private var moveLastDx = 0f
    private var moveLastDy = 0f
    private var resizeAnchorX = 0f
    private var resizeAnchorY = 0f
    private var resizeRefX = 0f
    private var resizeRefY = 0f
    private var resizeLastScale = 1f
    private var rotateCenterX = 0f
    private var rotateCenterY = 0f
    private var rotatePageOffsetY = 0f
    private var rotateStartX = 0f
    private var rotateStartY = 0f
    private var rotateLastDeg = 0f
    private var rotateClampDx = 0f
    private var rotateClampDy = 0f
    private var rotateBaseAngle: Float? = null
    private var rotateSnapKind = 0
    private var moveUnionBox: FloatArray? = null
    private val origStrokes = ArrayList<Pair<Int, StrokeItem>>()
    private val origTexts = ArrayList<TextItem>()
    private val origTextXY = ArrayList<FloatArray>()

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
    private val tapMinSeparationPx: Float
        get() = 40f * resources.displayMetrics.density
    private var lastStylusEventTime = Long.MIN_VALUE / 2

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
            id: Any,
            stroke: io.github.fadhil_sabar.tulis.ink.InkStroke,
            kind: BrushKind,
            colorArgb: Int,
            sizePx: Float
        ) {
            val ctx = strokeCtx.remove(id)
            val inv = ctx?.matrix ?: Matrix()
            val item = StrokeItem(
                stroke = stroke,
                brushKind = kind,
                colorArgb = colorArgb,
                sizePx = sizePx,
                strokeToWorld = inv
            )
            commitItem(item, ctx?.page)
            if (finishedStrokeIds.remove(id)) {
                liveInk.clearFinished(listOf(id))
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
        setWillNotDraw(false)
    }

    fun setPage(page: PageContent?) {
        commitPendingShape()
        clearSelection()
        canvasLayer.page = page
        canvasLayer.invalidate()
    }

    fun getPage(): PageContent? = canvasLayer.page

    fun setStack(s: PageStack?) {
        commitPendingShape()
        clearSelection()
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

    fun setPageSize(w: Float, h: Float) {
        canvasLayer.setPageSize(w, h)
        invalidatePages()
    }

    fun scrollToPage(i: Int) {
        commitPendingShape()
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

    fun hasPendingShape(): Boolean = pending != null

    fun commitPendingShape() {
        val p = pending ?: return
        pending = null
        editRotateSnapKind = 0
        val item = StrokeItem(
            stroke = p.preview,
            brushKind = p.brushKind,
            colorArgb = p.colorArgb,
            sizePx = p.sizePx,
            strokeToWorld = Matrix(p.inv)
        )
        commitItem(item, p.page)
        invalidate()
    }

    private fun discardPendingShape() {
        if (pending == null) return
        pending = null
        editRotateSnapKind = 0
        invalidate()
    }

    private fun commitItem(item: StrokeItem, ctxPage: PageContent?) {
        clearSelection()
        val s = canvasLayer.stack
        val p = if (s != null) ctxPage else canvasLayer.page
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
    }

    fun canUndo(): Boolean {
        if (pending != null) return true
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
                    clearSelection()
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
        commitPendingShape()
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

    private fun cancelLive(event: MotionEvent?, id: Any) {
        strokeCtx.remove(id)
        liveInk.cancel(event, id)
    }

    fun undo() {
        clearSelection()
        if (pending != null) {
            discardPendingShape()
            listener?.onHistoryChanged()
            return
        }
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
        commitPendingShape()
        clearSelection()
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
        commitPendingShape()
        clearSelection()
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
        commitPendingShape()
        clearSelection()
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
            forEachInDrawOrder(page.strokes) { stroke ->
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
                forEachInDrawOrder(p.strokes) { stroke ->
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
                    forEachInDrawOrder(p.strokes) { stroke ->
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
        commitPendingShape()
        clearSelection()
        cancelHold()
        cancelFling()
        recycleVelocityTracker()
        activeStrokeId?.let { id ->
            cancelLive(null, id)
            activeStrokeId = null
        }
        finishedStrokeIds.clear()
        strokeCtx.clear()
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

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 0 && isStylusTool(event.getToolType(0))) {
            lastStylusEventTime = event.eventTime
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        for (i in 0 until event.pointerCount) {
            val tt = event.getToolType(i)
            checkStylusDetected(tt)
            if (isStylusTool(tt)) lastStylusEventTime = event.eventTime
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
                if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                    event.eventTime - lastStylusEventTime > 500L
                ) {
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
                                if (isPalmTouch(event, newPointerIndex) ||
                                    (count == 2 && hypot(
                                        (event.getX(1) - event.getX(0)).toDouble(),
                                        (event.getY(1) - event.getY(0)).toDouble()
                                    ) < tapMinSeparationPx)
                                ) {
                                    tapCandidate = false
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

                            if (targetEraser && (gestureState == GestureState.DRAW || gestureState == GestureState.SELECT_LASSO || gestureState == GestureState.SELECT_MOVE || gestureState == GestureState.SELECT_RESIZE || gestureState == GestureState.SELECT_ROTATE)) {
                                if (gestureState == GestureState.DRAW) {
                                    motionPredictor.record(event)
                                    activeStrokeId?.let { id ->
                                        cancelLive(event, id)
                                        activeStrokeId = null
                                    }
                                } else {
                                    handleTouchCancel(event)
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
                                    cancelLive(event, id)
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
                    if (gestureState == GestureState.DRAW || gestureState == GestureState.ERASE ||
                        gestureState == GestureState.SELECT_LASSO || gestureState == GestureState.SELECT_MOVE ||
                        gestureState == GestureState.SELECT_RESIZE || gestureState == GestureState.SELECT_ROTATE
                    ) {
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

        if (pending != null) {
            if (beginEditGesture(sx, sy)) return
            commitPendingShape()
        }

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
                    Tool.SHAPE -> startShape(wx, wy, sx, sy)
                    Tool.SELECT -> startSelectTouch(sx, sy, wx, wy)
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
                Tool.SHAPE -> {
                    if (fingerDraws) {
                        startShape(wx, wy, sx, sy)
                    } else {
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                }
                Tool.SELECT -> {
                    if (fingerDraws) {
                        startSelectTouch(sx, sy, wx, wy)
                    } else {
                        gestureState = GestureState.PAN
                        canvasLayer.setViewportGestureActive(true)
                    }
                }
            }
        }
    }

    private fun resolveDrawTarget(wx: Float, wy: Float, inv: Matrix): Boolean {
        val s = canvasLayer.stack
        if (s != null) {
            val hitIdx = canvasLayer.hitPage(wx, wy, tmpPoints)
            if (hitIdx < 0) return false
            val targetPage = s.get(hitIdx) ?: return false
            activeTargetPage = targetPage
            canvasLayer.pageToScreen(hitIdx, tmpPageMatrix)
            if (!tmpPageMatrix.invert(inv)) {
                inv.reset()
            }
        } else {
            if (mode == NoteMode.PAGED && !canvasLayer.insidePage(wx, wy)) return false
            activeTargetPage = null
            viewport.worldToScreen.invert(inv)
        }
        return true
    }

    private fun startShape(wx: Float, wy: Float, sx: Float, sy: Float) {
        if (!resolveDrawTarget(wx, wy, shapeInv)) {
            gestureState = GestureState.NONE
            return
        }
        shapeTargetPage = activeTargetPage
        shapeStartX = sx
        shapeStartY = sy
        shapeLastX = sx
        shapeLastY = sy
        gestureState = GestureState.SHAPE_DRAG
    }

    private fun startDrawing(event: MotionEvent, pointerIndex: Int, wx: Float, wy: Float) {
        val inv = Matrix()
        if (!resolveDrawTarget(wx, wy, inv)) {
            gestureState = GestureState.NONE
            return
        }

        val brushKind = when (tool) {
            Tool.MARKER -> BrushKind.MARKER
            Tool.HIGHLIGHTER -> BrushKind.HIGHLIGHTER
            else -> BrushKind.PEN
        }
        val currentScale = viewport.scale
        val strokeSizePx = widthWorld * currentScale

        motionPredictor.record(event)

        val id = liveInk.start(
            event = event,
            pointerId = event.getPointerId(pointerIndex),
            kind = brushKind,
            colorArgb = colorArgb,
            sizePx = strokeSizePx
        )
        strokeCtx[id] = StrokeCtx(inv, activeTargetPage)
        activeStrokeId = id
        drawBrushKind = brushKind
        drawColorArgb = colorArgb
        drawSizePx = strokeSizePx
        if (snapShapes) beginHold(event, pointerIndex)
        gestureState = GestureState.DRAW
    }

    private fun beginHold(event: MotionEvent, pointerIndex: Int) {
        holdCount = 0
        holdPressureSum = 0f
        holdPressureCount = 0
        holdRestX = event.getX(pointerIndex)
        holdRestY = event.getY(pointerIndex)
        recordHoldSamples(event, pointerIndex)
        scheduleHold()
    }

    private fun trackHold(event: MotionEvent, pointerIndex: Int) {
        recordHoldSamples(event, pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (hypot((x - holdRestX).toDouble(), (y - holdRestY).toDouble()) > holdMoveDp * density) {
            holdRestX = x
            holdRestY = y
            scheduleHold()
        }
    }

    private fun recordHoldSamples(event: MotionEvent, pointerIndex: Int) {
        for (h in 0 until event.historySize) {
            appendHoldPoint(event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h))
        }
        appendHoldPoint(event.getX(pointerIndex), event.getY(pointerIndex))
        holdPressureSum += event.getPressure(pointerIndex)
        holdPressureCount++
    }

    private fun appendHoldPoint(x: Float, y: Float) {
        if (holdCount + 2 > holdBuffer.size) {
            holdBuffer = holdBuffer.copyOf(holdBuffer.size * 2)
        }
        holdBuffer[holdCount] = x
        holdBuffer[holdCount + 1] = y
        holdCount += 2
    }

    private fun scheduleHold() {
        removeCallbacks(holdRunnable)
        postDelayed(holdRunnable, holdDelayMs)
    }

    private fun cancelHold() {
        removeCallbacks(holdRunnable)
    }

    private fun onHoldFired() {
        val id = activeStrokeId ?: return
        if (gestureState != GestureState.DRAW) return
        val ctx = strokeCtx[id] ?: return
        val points = ShapeRecognizer.trimTrailingRest(holdBuffer.copyOf(holdCount), holdRestX, holdRestY, 2f * holdMoveDp * density)
        val spec = ShapeRecognizer.recognize(points, snapToleranceDp * density) ?: return
        cancelLive(null, id)
        activeStrokeId = null
        snapAdjust = SnapAdjust(spec, points[points.size - 2], points[points.size - 1], holdMoveDp * density)
        val pressure = if (holdPressureCount > 0) holdPressureSum / holdPressureCount else dragPressure
        pending = PendingShape(drawBrushKind, drawColorArgb, drawSizePx, pressure, Matrix(ctx.matrix), ctx.page, spec)
        gestureState = GestureState.SHAPE_HOLD
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        listener?.onHistoryChanged()
    }

    private fun updateShapeGesture(x: Float, y: Float) {
        when (gestureState) {
            GestureState.SHAPE_HOLD -> updateSnapShape(x, y)
            GestureState.SHAPE_DRAG -> updateDragShape(x, y)
            GestureState.SHAPE_EDIT_MOVE -> updateEditMove(x, y)
            GestureState.SHAPE_EDIT_RESIZE -> updateEditResize(x, y)
            GestureState.SHAPE_EDIT_ROTATE -> updateEditRotate(x, y)
            else -> {}
        }
    }

    private fun updateSnapShape(x: Float, y: Float) {
        val p = pending ?: return
        val adjust = snapAdjust ?: return
        applyPendingSpec(p, adjust.at(x, y))
    }

    private fun updateDragShape(x: Float, y: Float) {
        shapeLastX = x
        shapeLastY = y
        val spec = ShapeSpec.fromDrag(shapeKind, shapeStartX, shapeStartY, x, y)
        val p = pending
        if (p != null) {
            applyPendingSpec(p, spec)
        } else if (isVisibleSpec(spec)) {
            pending = PendingShape(
                BrushKind.PEN,
                colorArgb,
                widthWorld * viewport.scale,
                dragPressure,
                Matrix(shapeInv),
                shapeTargetPage,
                spec
            )
            invalidate()
            listener?.onHistoryChanged()
        }
    }

    private fun beginEditGesture(x: Float, y: Float): Boolean {
        val p = pending ?: return false
        if (gestureState != GestureState.NONE) return false
        val pad = p.sizePx / 2f + editPadDp * density
        p.spec.bounds(tmpBounds)
        val left = tmpBounds[0] - pad
        val top = tmpBounds[1] - pad
        val right = tmpBounds[2] + pad
        val bottom = tmpBounds[3] + pad
        shapeFrame.set(left, top, right, bottom)
        computeRotateButtonCenter(shapeFrame, false, shapeRotateCenter)
        val btnHitHalf = deleteBtnHitHalfDp * density
        if (abs(x - shapeRotateCenter[0]) <= btnHitHalf && abs(y - shapeRotateCenter[1]) <= btnHitHalf) {
            editStartSpec = p.spec
            editStartRotated = p.rotated
            editStartRotationDeg = p.rotationDeg
            editAnchorX = (tmpBounds[0] + tmpBounds[2]) * 0.5f
            editAnchorY = (tmpBounds[1] + tmpBounds[3]) * 0.5f
            editDownX = x
            editDownY = y
            editRotateStarted = false
            editRotateBaseAngle = SelectionMath.dominantAngle(ShapeOutline.outline(p.spec))
            editRotateSnapKind = 0
            editRotateLastDeg = 0f
            gestureState = GestureState.SHAPE_EDIT_ROTATE
            return true
        }
        val hit = handleHitDp * density
        for (k in 0 until 4) {
            val cx = cornerX(k, left, right)
            val cy = cornerY(k, top, bottom)
            if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= hit) {
                val opposite = (k + 2) % 4
                editStartSpec = p.spec
                editStartRotated = p.rotated
                editStartRotationDeg = p.rotationDeg
                editAnchorX = cornerX(opposite, tmpBounds[0], tmpBounds[2])
                editAnchorY = cornerY(opposite, tmpBounds[1], tmpBounds[3])
                editRefX = cornerX(k, tmpBounds[0], tmpBounds[2])
                editRefY = cornerY(k, tmpBounds[1], tmpBounds[3])
                editDownX = x
                editDownY = y
                gestureState = GestureState.SHAPE_EDIT_RESIZE
                return true
            }
        }
        if (x in left..right && y in top..bottom) {
            editStartSpec = p.spec
            editStartRotated = p.rotated
            editStartRotationDeg = p.rotationDeg
            editDownX = x
            editDownY = y
            gestureState = GestureState.SHAPE_EDIT_MOVE
            return true
        }
        return false
    }

    private fun updateEditMove(x: Float, y: Float) {
        val p = pending ?: return
        val start = editStartSpec ?: return
        applyPendingSpec(p, start.translated(x - editDownX, y - editDownY))
    }

    private fun updateEditResize(x: Float, y: Float) {
        val p = pending ?: return
        val start = editStartSpec ?: return
        if (p.rotated) {
            val px = editRefX + (x - editDownX)
            val py = editRefY + (y - editDownY)
            val s = SelectionMath.uniformScale(editAnchorX, editAnchorY, editRefX, editRefY, px, py)
            applyPendingSpec(p, start.transformed(editAnchorX, editAnchorY, s, 0f, 0f, 0f))
        } else {
            val sx = scaleFactor(editRefX + (x - editDownX), editAnchorX, editRefX)
            val sy = scaleFactor(editRefY + (y - editDownY), editAnchorY, editRefY)
            applyPendingSpec(p, start.scaledXY(editAnchorX, editAnchorY, sx, sy))
        }
    }

    private fun updateEditRotate(x: Float, y: Float) {
        val p = pending ?: return
        val start = editStartSpec ?: return
        if (!editRotateStarted) {
            if (hypot((x - editDownX).toDouble(), (y - editDownY).toDouble()) <= touchSlop) {
                return
            }
            editRotateStarted = true
        }
        val rawDeg = SelectionMath.rotationDegrees(editAnchorX, editAnchorY, editDownX, editDownY, x, y)
        val snap = SelectionMath.snapRotation(rawDeg, editRotateBaseAngle)
        val deg = snap[0]
        val snapKind = snap[1].toInt()
        if (snapKind != 0 && editRotateSnapKind == 0) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        editRotateSnapKind = snapKind
        editRotateLastDeg = deg
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        if (applyPendingSpec(p, start.transformed(editAnchorX, editAnchorY, 1f, rad, 0f, 0f))) {
            var total = (editStartRotationDeg + deg) % 360f
            if (total <= -180f) total += 360f else if (total > 180f) total -= 360f
            p.rotationDeg = total
            p.rotated = abs(total) > 1e-3f
        }
    }

    private fun scaleFactor(target: Float, anchor: Float, ref: Float): Float {
        val d = ref - anchor
        if (abs(d) < 1e-3f) return 1f
        val s = (target - anchor) / d
        return if (abs(s) < minShapeScale) {
            if (s < 0f) -minShapeScale else minShapeScale
        } else {
            s
        }
    }

    private fun applyPendingSpec(p: PendingShape, spec: ShapeSpec): Boolean {
        if (!isVisibleSpec(spec)) return false
        p.update(spec)
        invalidate()
        return true
    }

    private fun isVisibleSpec(spec: ShapeSpec): Boolean {
        spec.bounds(tmpBounds)
        return tmpBounds[2] - tmpBounds[0] + tmpBounds[3] - tmpBounds[1] >= 2f
    }

    private fun cornerX(k: Int, left: Float, right: Float): Float = if (k == 0 || k == 3) left else right

    private fun cornerY(k: Int, top: Float, bottom: Float): Float = if (k < 2) top else bottom

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = pending
        if (p != null) {
            shapeRenderer.draw(canvas, p.preview, identityMatrix)
            if (gestureState != GestureState.SHAPE_HOLD && gestureState != GestureState.SHAPE_DRAG) {
                drawShapeFrame(canvas, p)
            }
        }
        drawSelectionOverlay(canvas)
        drawLassoOverlay(canvas)
    }

    private fun drawShapeFrame(canvas: Canvas, p: PendingShape) {
        val pad = p.sizePx / 2f + editPadDp * density
        p.spec.bounds(tmpBounds)
        shapeFrame.set(tmpBounds[0] - pad, tmpBounds[1] - pad, tmpBounds[2] + pad, tmpBounds[3] + pad)
        shapeRotateOnLeft = computeRotateButtonCenter(shapeFrame, false, shapeRotateCenter)
        drawRotateConnector(canvas, shapeFrame, shapeRotateCenter, shapeRotateOnLeft)
        drawFrameWithCornerHandles(canvas, shapeFrame)
        drawRotateButton(canvas, shapeRotateCenter, shapeRotateIconRect)
        if (gestureState == GestureState.SHAPE_EDIT_ROTATE && editRotateSnapKind == 2) {
            drawSnapGuideLine(canvas, shapeFrame, editAnchorX, editAnchorY, (editRotateBaseAngle ?: 0f) + editRotateLastDeg)
        }
    }

    private fun startErasing(wx: Float, wy: Float) {
        clearSelection()
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
        pendingDeleteTap = false
        cancelHold()
        when (gestureState) {
            GestureState.SHAPE_HOLD, GestureState.SHAPE_DRAG -> discardPendingShape()
            GestureState.DRAW -> {
                commitPendingShape()
                motionPredictor.record(event)
                activeStrokeId?.let { id ->
                    cancelLive(event, id)
                    activeStrokeId = null
                }
            }
            GestureState.ERASE -> {
                commitPendingShape()
                eraseBatch.clear()
                endEraseGroups()
            }
            GestureState.SELECT_LASSO -> {
                commitPendingShape()
                cancelLasso()
            }
            GestureState.SELECT_MOVE, GestureState.SELECT_RESIZE, GestureState.SELECT_ROTATE,
            GestureState.NONE, GestureState.PAN, GestureState.PINCH,
            GestureState.SHAPE_EDIT_MOVE, GestureState.SHAPE_EDIT_RESIZE, GestureState.SHAPE_EDIT_ROTATE -> {
                commitPendingShape()
            }
        }
        clearSelection()
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
                        if (snapShapes) trackHold(event, pointerIndex)
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

            GestureState.SHAPE_HOLD, GestureState.SHAPE_DRAG,
            GestureState.SHAPE_EDIT_MOVE, GestureState.SHAPE_EDIT_RESIZE, GestureState.SHAPE_EDIT_ROTATE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateShapeGesture(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }

            GestureState.SELECT_LASSO -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateLasso(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }

            GestureState.SELECT_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateSelectMove(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }

            GestureState.SELECT_RESIZE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateSelectResize(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }

            GestureState.SELECT_ROTATE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateSelectRotate(event.getX(pointerIndex), event.getY(pointerIndex))
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
                        cancelLive(event, id)
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
                if (pendingDeleteTap) {
                    pendingDeleteTap = false
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val sx = event.getX(pointerIndex)
                        val sy = event.getY(pointerIndex)
                        if (hypot((sx - downTouchX).toDouble(), (sy - downTouchY).toDouble()) <= touchSlop) {
                            deleteSelection()
                        }
                    }
                } else if (tool == Tool.TEXT) {
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

            GestureState.SHAPE_DRAG -> {
                val dx = (shapeLastX - shapeStartX).toDouble()
                val dy = (shapeLastY - shapeStartY).toDouble()
                if (hypot(dx, dy) < touchSlop) {
                    discardPendingShape()
                }
            }

            GestureState.SHAPE_HOLD, GestureState.SHAPE_EDIT_MOVE, GestureState.SHAPE_EDIT_RESIZE -> {
            }

            GestureState.SHAPE_EDIT_ROTATE -> {
                if (editRotateSnapKind == 2) {
                    editRotateSnapKind = 0
                    invalidate()
                }
            }

            GestureState.SELECT_LASSO -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    finishLasso(event.getX(pointerIndex), event.getY(pointerIndex))
                } else {
                    cancelLasso()
                }
            }

            GestureState.SELECT_MOVE -> {
                finishSelectionEdit(SelectionEditMode.MOVE)
            }

            GestureState.SELECT_RESIZE -> {
                finishSelectionEdit(SelectionEditMode.RESIZE)
            }

            GestureState.SELECT_ROTATE -> {
                finishSelectionEdit(SelectionEditMode.ROTATE)
            }
        }
    }

    private fun handleTouchCancel(event: MotionEvent) {
        pendingDeleteTap = false
        when (gestureState) {
            GestureState.DRAW -> {
                motionPredictor.record(event)
                activeStrokeId?.let { id ->
                    cancelLive(event, id)
                    activeStrokeId = null
                }
            }

            GestureState.ERASE -> {
                eraseBatch.clear()
                endEraseGroups()
            }

            GestureState.SHAPE_HOLD, GestureState.SHAPE_DRAG -> {
                discardPendingShape()
            }

            GestureState.SHAPE_EDIT_MOVE, GestureState.SHAPE_EDIT_RESIZE, GestureState.SHAPE_EDIT_ROTATE -> {
                editRotateSnapKind = 0
                val start = editStartSpec
                val p = pending
                if (start != null && p != null) {
                    p.update(start)
                    p.rotated = editStartRotated
                    p.rotationDeg = editStartRotationDeg
                    invalidate()
                }
            }

            GestureState.SELECT_LASSO -> {
                cancelLasso()
            }

            GestureState.SELECT_MOVE, GestureState.SELECT_RESIZE, GestureState.SELECT_ROTATE -> {
                revertSelectionPreview()
                invalidate()
            }

            GestureState.PAN, GestureState.PINCH, GestureState.NONE -> {
                canvasLayer.setViewportGestureActive(false)
            }
        }
    }

    private fun endGesture() {
        tapCandidate = false
        pendingDeleteTap = false
        editRotateSnapKind = 0
        cancelHold()
        gestureState = GestureState.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isStylusActive = false
        isTemporaryEraser = false
        recycleVelocityTracker()
        if (pending != null) invalidate()
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

    private fun startSelectTouch(sx: Float, sy: Float, wx: Float, wy: Float) {
        pendingDeleteTap = false
        val activePage = validateAndComputeSelectionOverlay(selScreenRect, selDeleteCenter, selRotateCenter)
        if (activePage != null) {
            val hitHalf = deleteBtnHitHalfDp * density
            if (abs(sx - selDeleteCenter[0]) <= hitHalf && abs(sy - selDeleteCenter[1]) <= hitHalf) {
                pendingDeleteTap = true
                gestureState = GestureState.NONE
                return
            }
            if (abs(sx - selRotateCenter[0]) <= hitHalf && abs(sy - selRotateCenter[1]) <= hitHalf) {
                beginSelectRotate(activePage, sx, sy)
                return
            }
            val handleHit = handleHitDp * density
            for (k in 0 until 4) {
                val cx = cornerX(k, selScreenRect.left, selScreenRect.right)
                val cy = cornerY(k, selScreenRect.top, selScreenRect.bottom)
                if (hypot((sx - cx).toDouble(), (sy - cy).toDouble()) <= handleHit) {
                    beginSelectResize(activePage, k, sx, sy)
                    return
                }
            }
            if (selScreenRect.contains(sx, sy)) {
                beginSelectMove(activePage, sx, sy)
                return
            }
        }
        clearSelection()
        startLasso(sx, sy, wx, wy)
    }

    private fun startLasso(sx: Float, sy: Float, wx: Float, wy: Float) {
        val s = canvasLayer.stack
        val page: PageContent
        val offsetY: Float
        if (s != null) {
            val hitIdx = canvasLayer.hitPage(wx, wy, tmpPoints)
            if (hitIdx < 0) {
                gestureState = GestureState.NONE
                return
            }
            page = s.get(hitIdx) ?: run {
                gestureState = GestureState.NONE
                return
            }
            offsetY = s.offsetY(hitIdx)
        } else {
            page = canvasLayer.page ?: run {
                gestureState = GestureState.NONE
                return
            }
            offsetY = 0f
        }
        lassoPage = page
        lassoPageOffsetY = offsetY
        lassoStartSx = sx
        lassoStartSy = sy
        lassoLastSx = sx
        lassoLastSy = sy
        lassoMaxDist = 0f
        lassoCount = 0
        appendLassoPoint(wx, wy - offsetY)
        gestureState = GestureState.SELECT_LASSO
        invalidate()
    }

    private fun updateLasso(sx: Float, sy: Float) {
        val page = lassoPage ?: return
        val s = canvasLayer.stack
        if (s != null) {
            if (s.indexOf(page) < 0) {
                cancelLasso()
                return
            }
        } else {
            if (page !== canvasLayer.page) {
                cancelLasso()
                return
            }
        }
        val distFromStart = hypot((sx - lassoStartSx).toDouble(), (sy - lassoStartSy).toDouble()).toFloat()
        if (distFromStart > lassoMaxDist) {
            lassoMaxDist = distFromStart
        }
        if (hypot((sx - lassoLastSx).toDouble(), (sy - lassoLastSy).toDouble()) > lassoPointStepDp * density) {
            lassoLastSx = sx
            lassoLastSy = sy
            screenToWorld(sx, sy, tmpPoints)
            appendLassoPoint(tmpPoints[0], tmpPoints[1] - lassoPageOffsetY)
            invalidate()
        }
    }

    private fun finishLasso(sx: Float, sy: Float) {
        val page = lassoPage
        if (page == null) {
            cancelLasso()
            return
        }
        val s = canvasLayer.stack
        if (s != null) {
            if (s.indexOf(page) < 0) {
                cancelLasso()
                return
            }
        } else {
            if (page !== canvasLayer.page) {
                cancelLasso()
                return
            }
        }

        val distFromStart = hypot((sx - lassoStartSx).toDouble(), (sy - lassoStartSy).toDouble()).toFloat()
        if (distFromStart > lassoMaxDist) {
            lassoMaxDist = distFromStart
        }

        selPage = null
        selStrokes.clear()
        selTexts.clear()

        if (lassoMaxDist < touchSlop) {
            screenToWorld(sx, sy, tmpPoints)
            val lx = tmpPoints[0]
            val ly = tmpPoints[1] - lassoPageOffsetY
            val radiusWorld = (eraseHitRadiusDp * density) / viewport.scale
            val hitText = hitTestTextInPage(page, lx, ly)
            if (hitText != null) {
                selPage = page
                selTexts.add(hitText)
            } else {
                var hitStroke: StrokeItem? = null
                val strokes = page.strokes
                for (i in strokes.indices.reversed()) {
                    val item = strokes[i]
                    if (item.brushKind != BrushKind.HIGHLIGHTER && item.hitTest(lx, ly, radiusWorld)) {
                        hitStroke = item
                        break
                    }
                }
                if (hitStroke == null) {
                    for (i in strokes.indices.reversed()) {
                        val item = strokes[i]
                        if (item.brushKind == BrushKind.HIGHLIGHTER && item.hitTest(lx, ly, radiusWorld)) {
                            hitStroke = item
                            break
                        }
                    }
                }
                if (hitStroke != null) {
                    selPage = page
                    selStrokes.add(hitStroke)
                }
            }
        } else {
            if (hypot((sx - lassoLastSx).toDouble(), (sy - lassoLastSy).toDouble()) > lassoPointStepDp * density) {
                screenToWorld(sx, sy, tmpPoints)
                appendLassoPoint(tmpPoints[0], tmpPoints[1] - lassoPageOffsetY)
            }
            if (lassoCount >= 6) {
                val poly = lassoBuffer.copyOf(lassoCount)
                val strokes = page.strokes
                for (i in 0 until strokes.size) {
                    val item = strokes[i]
                    if (Lasso.selects(poly, item.worldPoints(), 0.6f)) {
                        selStrokes.add(item)
                    }
                }
                val texts = page.texts
                for (i in 0 until texts.size) {
                    val text = texts[i]
                    text.worldBounds(tmpRectF)
                    if (Lasso.contains(poly, tmpRectF.centerX(), tmpRectF.centerY())) {
                        selTexts.add(text)
                    }
                }
                if (selStrokes.isNotEmpty() || selTexts.isNotEmpty()) {
                    selPage = page
                }
            }
        }

        lassoPage = null
        lassoCount = 0
        lassoMaxDist = 0f
        invalidate()
    }

    private fun cancelLasso() {
        val hadLasso = lassoCount > 0 || lassoPage != null
        lassoPage = null
        lassoCount = 0
        lassoMaxDist = 0f
        if (hadLasso) invalidate()
    }

    private fun appendLassoPoint(x: Float, y: Float) {
        if (lassoCount + 2 > lassoBuffer.size) {
            lassoBuffer = lassoBuffer.copyOf(lassoBuffer.size * 2)
        }
        lassoBuffer[lassoCount] = x
        lassoBuffer[lassoCount + 1] = y
        lassoCount += 2
    }

    private fun isPageActive(page: PageContent): Boolean {
        val s = canvasLayer.stack
        return if (s != null) s.indexOf(page) >= 0 else page === canvasLayer.page
    }

    private fun snapshotSelectionForEdit(page: PageContent, sx: Float, sy: Float, includeTextSize: Boolean): FloatArray? {
        movePage = page
        moveDownSx = sx
        moveDownSy = sy
        moveStarted = false
        moveLastDx = 0f
        moveLastDy = 0f
        resizeLastScale = 1f
        rotateLastDeg = 0f
        rotateClampDx = 0f
        rotateClampDy = 0f
        rotateBaseAngle = null
        rotateSnapKind = 0
        origStrokes.clear()
        origTexts.clear()
        origTextXY.clear()

        val boxes = ArrayList<FloatArray>(selStrokes.size + selTexts.size)
        for (i in 0 until selStrokes.size) {
            val stroke = selStrokes[i]
            val idx = page.strokes.indexOf(stroke)
            if (idx >= 0) {
                origStrokes.add(Pair(idx, stroke))
                if (stroke.worldBounds(tmpRectF)) {
                    boxes.add(floatArrayOf(tmpRectF.left, tmpRectF.top, tmpRectF.right, tmpRectF.bottom))
                }
            }
        }
        for (i in 0 until selTexts.size) {
            val text = selTexts[i]
            if (page.texts.contains(text)) {
                origTexts.add(text)
                origTextXY.add(
                    if (includeTextSize) {
                        floatArrayOf(text.x, text.y, text.sizeWorld, text.widthWorld)
                    } else {
                        floatArrayOf(text.x, text.y)
                    }
                )
                text.worldBounds(tmpRectF)
                boxes.add(floatArrayOf(tmpRectF.left, tmpRectF.top, tmpRectF.right, tmpRectF.bottom))
            }
        }
        val box = SelectionMath.union(boxes)
        moveUnionBox = box
        return box
    }

    private fun beginSelectMove(page: PageContent, sx: Float, sy: Float) {
        snapshotSelectionForEdit(page, sx, sy, false)
        gestureState = GestureState.SELECT_MOVE
    }

    private fun beginSelectResize(page: PageContent, cornerIndex: Int, sx: Float, sy: Float) {
        val box = snapshotSelectionForEdit(page, sx, sy, true)
        if (box == null) {
            revertSelectionPreview()
            gestureState = GestureState.NONE
            return
        }
        val opposite = (cornerIndex + 2) % 4
        resizeAnchorX = cornerX(opposite, box[0], box[2])
        resizeAnchorY = cornerY(opposite, box[1], box[3])
        resizeRefX = cornerX(cornerIndex, box[0], box[2])
        resizeRefY = cornerY(cornerIndex, box[1], box[3])
        gestureState = GestureState.SELECT_RESIZE
    }

    private fun beginSelectRotate(page: PageContent, sx: Float, sy: Float) {
        val box = snapshotSelectionForEdit(page, sx, sy, false)
        if (box == null) {
            revertSelectionPreview()
            gestureState = GestureState.NONE
            return
        }
        val s = canvasLayer.stack
        val offsetY = if (s != null) {
            val idx = s.indexOf(page)
            if (idx < 0) {
                revertSelectionPreview()
                gestureState = GestureState.NONE
                return
            }
            s.offsetY(idx)
        } else {
            0f
        }
        rotateCenterX = (box[0] + box[2]) * 0.5f
        rotateCenterY = (box[1] + box[3]) * 0.5f
        rotatePageOffsetY = offsetY
        screenToWorld(sx, sy, tmpPoints)
        rotateStartX = tmpPoints[0]
        rotateStartY = tmpPoints[1] - offsetY
        rotateLastDeg = 0f
        rotateClampDx = 0f
        rotateClampDy = 0f
        var totalPts = 0
        for (i in 0 until origStrokes.size) {
            totalPts += origStrokes[i].second.worldPoints().size
        }
        val pts = FloatArray(totalPts)
        var pos = 0
        for (i in 0 until origStrokes.size) {
            val wpts = origStrokes[i].second.worldPoints()
            wpts.copyInto(pts, pos)
            pos += wpts.size
        }
        rotateBaseAngle = SelectionMath.dominantAngle(pts)
        rotateSnapKind = 0
        gestureState = GestureState.SELECT_ROTATE
    }

    private fun transformStroke(
        orig: StrokeItem,
        editMode: SelectionEditMode,
        dx: Float,
        dy: Float,
        scale: Float,
        ax: Float,
        ay: Float,
        deg: Float
    ): StrokeItem {
        val m = Matrix(orig.strokeToWorld)
        when (editMode) {
            SelectionEditMode.MOVE -> m.postTranslate(dx, dy)
            SelectionEditMode.RESIZE -> m.postScale(scale, scale, ax, ay)
            SelectionEditMode.ROTATE -> {
                m.postRotate(deg, ax, ay)
                if (dx != 0f || dy != 0f) m.postTranslate(dx, dy)
            }
        }
        return StrokeItem(orig.stroke, orig.brushKind, orig.colorArgb, orig.sizePx, m, orig.encodedInputs)
    }

    private fun computeRotatedTextXY(
        text: TextItem,
        origX: Float,
        origY: Float,
        cx: Float,
        cy: Float,
        deg: Float,
        clampDx: Float,
        clampDy: Float
    ): FloatArray {
        text.worldBounds(tmpRectF)
        val halfW = tmpRectF.width() * 0.5f
        val halfH = tmpRectF.height() * 0.5f
        val rotatedMid = SelectionMath.rotatePoint(origX + halfW, origY + halfH, cx, cy, deg)
        return floatArrayOf(rotatedMid[0] - halfW + clampDx, rotatedMid[1] - halfH + clampDy)
    }

    private fun updateSelectMove(sx: Float, sy: Float) {
        val page = movePage ?: return
        if (!isPageActive(page)) {
            clearSelection()
            return
        }
        if (!moveStarted) {
            if (hypot((sx - moveDownSx).toDouble(), (sy - moveDownSy).toDouble()) <= touchSlop) {
                return
            }
            moveStarted = true
        }

        var dx = (sx - moveDownSx) / viewport.scale
        var dy = (sy - moveDownSy) / viewport.scale
        val box = moveUnionBox
        if (canvasLayer.stack != null && box != null) {
            val clamped = SelectionMath.clampDelta(box, dx, dy, canvasLayer.pageW, canvasLayer.pageH)
            dx = clamped[0]
            dy = clamped[1]
        }
        moveLastDx = dx
        moveLastDy = dy

        selStrokes.clear()
        for (i in 0 until origStrokes.size) {
            val (idx, orig) = origStrokes[i]
            if (idx in 0 until page.strokes.size) {
                val moved = transformStroke(orig, SelectionEditMode.MOVE, dx, dy, 1f, 0f, 0f, 0f)
                page.strokes[idx] = moved
                selStrokes.add(moved)
            }
        }
        for (i in 0 until origTexts.size) {
            val t = origTexts[i]
            val xy = origTextXY[i]
            t.x = xy[0] + dx
            t.y = xy[1] + dy
        }
        invalidatePageContent(page)
        invalidate()
    }

    private fun updateSelectResize(sx: Float, sy: Float) {
        val page = movePage ?: return
        if (!isPageActive(page)) {
            clearSelection()
            return
        }
        if (!moveStarted) {
            if (hypot((sx - moveDownSx).toDouble(), (sy - moveDownSy).toDouble()) <= touchSlop) {
                return
            }
            moveStarted = true
        }

        val invScale = viewport.scale
        val px = resizeRefX + (sx - moveDownSx) / invScale
        val py = resizeRefY + (sy - moveDownSy) / invScale
        var scale = SelectionMath.uniformScale(resizeAnchorX, resizeAnchorY, resizeRefX, resizeRefY, px, py)
        val box = moveUnionBox
        if (canvasLayer.stack != null && box != null) {
            scale = minOf(scale, SelectionMath.maxScaleInside(resizeAnchorX, resizeAnchorY, box, canvasLayer.pageW, canvasLayer.pageH))
        }
        resizeLastScale = scale

        selStrokes.clear()
        for (i in 0 until origStrokes.size) {
            val (idx, orig) = origStrokes[i]
            if (idx in 0 until page.strokes.size) {
                val scaled = transformStroke(orig, SelectionEditMode.RESIZE, 0f, 0f, scale, resizeAnchorX, resizeAnchorY, 0f)
                page.strokes[idx] = scaled
                selStrokes.add(scaled)
            }
        }
        for (i in 0 until origTexts.size) {
            val t = origTexts[i]
            val st = origTextXY[i]
            t.x = resizeAnchorX + (st[0] - resizeAnchorX) * scale
            t.y = resizeAnchorY + (st[1] - resizeAnchorY) * scale
            t.sizeWorld = st[2] * scale
            t.widthWorld = st[3] * scale
        }
        invalidatePageContent(page)
        invalidate()
    }

    private fun updateSelectRotate(sx: Float, sy: Float) {
        val page = movePage ?: return
        if (!isPageActive(page)) {
            clearSelection()
            return
        }
        if (!moveStarted) {
            if (hypot((sx - moveDownSx).toDouble(), (sy - moveDownSy).toDouble()) <= touchSlop) {
                return
            }
            moveStarted = true
        }

        screenToWorld(sx, sy, tmpPoints)
        val px = tmpPoints[0]
        val py = tmpPoints[1] - rotatePageOffsetY
        val rawDeg = SelectionMath.rotationDegrees(rotateCenterX, rotateCenterY, rotateStartX, rotateStartY, px, py)
        val snap = SelectionMath.snapRotation(rawDeg, rotateBaseAngle)
        val deg = snap[0]
        val snapKind = snap[1].toInt()
        if (snapKind != 0 && rotateSnapKind == 0) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        rotateSnapKind = snapKind
        var clampDx = 0f
        var clampDy = 0f
        val box = moveUnionBox
        if (canvasLayer.stack != null && box != null) {
            val rotBox = SelectionMath.rotatedBounds(box, rotateCenterX, rotateCenterY, deg)
            val clamped = SelectionMath.clampDelta(rotBox, 0f, 0f, canvasLayer.pageW, canvasLayer.pageH)
            clampDx = clamped[0]
            clampDy = clamped[1]
        }
        rotateLastDeg = deg
        rotateClampDx = clampDx
        rotateClampDy = clampDy

        selStrokes.clear()
        for (i in 0 until origStrokes.size) {
            val (idx, orig) = origStrokes[i]
            if (idx in 0 until page.strokes.size) {
                val rotated = transformStroke(orig, SelectionEditMode.ROTATE, clampDx, clampDy, 1f, rotateCenterX, rotateCenterY, deg)
                page.strokes[idx] = rotated
                selStrokes.add(rotated)
            }
        }
        for (i in 0 until origTexts.size) {
            val t = origTexts[i]
            val st = origTextXY[i]
            val xy = computeRotatedTextXY(t, st[0], st[1], rotateCenterX, rotateCenterY, deg, clampDx, clampDy)
            t.x = xy[0]
            t.y = xy[1]
        }
        invalidatePageContent(page)
        invalidate()
    }

    private fun finishSelectionEdit(editMode: SelectionEditMode) {
        val page = movePage ?: return
        val pageValid = isPageActive(page)
        val dx = if (editMode == SelectionEditMode.ROTATE) rotateClampDx else moveLastDx
        val dy = if (editMode == SelectionEditMode.ROTATE) rotateClampDy else moveLastDy
        val scale = resizeLastScale
        val deg = rotateLastDeg
        val ax = if (editMode == SelectionEditMode.ROTATE) rotateCenterX else resizeAnchorX
        val ay = if (editMode == SelectionEditMode.ROTATE) rotateCenterY else resizeAnchorY
        val didChange = moveStarted && when (editMode) {
            SelectionEditMode.MOVE -> dx != 0f || dy != 0f
            SelectionEditMode.RESIZE -> scale != 1f
            SelectionEditMode.ROTATE -> deg != 0f || dx != 0f || dy != 0f
        }

        val savedStrokes = ArrayList<Pair<Int, StrokeItem>>(origStrokes)
        val savedTexts = ArrayList<TextItem>(origTexts)
        val savedTextXY = ArrayList<FloatArray>(origTextXY)

        revertSelectionPreview()

        if (!pageValid) {
            clearSelection()
            return
        }

        if (!didChange) {
            invalidate()
            return
        }

        val oldStrokeList = ArrayList<StrokeItem>(savedStrokes.size)
        val newStrokeList = ArrayList<StrokeItem>(savedStrokes.size)
        for (i in 0 until savedStrokes.size) {
            val orig = savedStrokes[i].second
            val transformed = transformStroke(orig, editMode, dx, dy, scale, ax, ay, deg)
            oldStrokeList.add(orig)
            newStrokeList.add(transformed)
        }

        val newTextXY = ArrayList<FloatArray>(savedTexts.size)
        for (i in 0 until savedTextXY.size) {
            val st = savedTextXY[i]
            newTextXY.add(
                when (editMode) {
                    SelectionEditMode.MOVE -> floatArrayOf(st[0] + dx, st[1] + dy)
                    SelectionEditMode.RESIZE -> floatArrayOf(
                        ax + (st[0] - ax) * scale,
                        ay + (st[1] - ay) * scale,
                        st[2] * scale,
                        st[3] * scale
                    )
                    SelectionEditMode.ROTATE -> computeRotatedTextXY(savedTexts[i], st[0], st[1], ax, ay, deg, dx, dy)
                }
            )
        }

        page.replaceItems(oldStrokeList, newStrokeList, savedTexts, savedTextXY, newTextXY)

        selStrokes.clear()
        for (i in 0 until newStrokeList.size) {
            if (page.strokes.contains(newStrokeList[i])) {
                selStrokes.add(newStrokeList[i])
            }
        }
        selTexts.clear()
        for (i in 0 until savedTexts.size) {
            if (page.texts.contains(savedTexts[i])) {
                selTexts.add(savedTexts[i])
            }
        }
        selPage = if (selStrokes.isEmpty() && selTexts.isEmpty()) null else page

        invalidatePageContent(page)
        invalidate()
        listener?.onContentChanged()
        listener?.onHistoryChanged()
    }

    private fun revertSelectionPreview() {
        val page = movePage ?: return
        for (i in 0 until origStrokes.size) {
            val (idx, orig) = origStrokes[i]
            if (idx in 0 until page.strokes.size) {
                page.strokes[idx] = orig
            }
        }
        for (i in 0 until origTexts.size) {
            val t = origTexts[i]
            val st = origTextXY[i]
            t.x = st[0]
            t.y = st[1]
            if (st.size >= 4) {
                t.sizeWorld = st[2]
                t.widthWorld = st[3]
            }
        }
        if (moveStarted) {
            selStrokes.clear()
            for (i in 0 until origStrokes.size) {
                selStrokes.add(origStrokes[i].second)
            }
            invalidatePageContent(page)
        }
        origStrokes.clear()
        origTexts.clear()
        origTextXY.clear()
        moveUnionBox = null
        movePage = null
        moveStarted = false
        moveLastDx = 0f
        moveLastDy = 0f
        resizeLastScale = 1f
        rotateLastDeg = 0f
        rotateClampDx = 0f
        rotateClampDy = 0f
        rotateBaseAngle = null
        rotateSnapKind = 0
    }

    private fun deleteSelection() {
        val page = selPage ?: return
        val s = canvasLayer.stack
        if (s != null) {
            if (s.indexOf(page) < 0) {
                clearSelection()
                return
            }
        } else {
            if (page !== canvasLayer.page) {
                clearSelection()
                return
            }
        }
        for (i in selStrokes.indices.reversed()) {
            if (!page.strokes.contains(selStrokes[i])) selStrokes.removeAt(i)
        }
        for (i in selTexts.indices.reversed()) {
            if (!page.texts.contains(selTexts[i])) selTexts.removeAt(i)
        }
        if (selStrokes.isEmpty() && selTexts.isEmpty()) {
            clearSelection()
            return
        }
        page.removeItems(selStrokes, selTexts)
        clearSelection()
        invalidatePageContent(page)
        listener?.onContentChanged()
        listener?.onHistoryChanged()
    }

    private fun clearSelection() {
        revertSelectionPreview()
        val hadSelection = selPage != null || selStrokes.isNotEmpty() || selTexts.isNotEmpty() || lassoCount > 0 || lassoPage != null
        selPage = null
        selStrokes.clear()
        selTexts.clear()
        lassoPage = null
        lassoCount = 0
        lassoMaxDist = 0f
        pendingDeleteTap = false
        if (hadSelection) invalidate()
    }

    private fun invalidatePageContent(page: PageContent) {
        if (canvasLayer.stack != null) {
            canvasLayer.invalidateContent(page)
        } else {
            canvasLayer.invalidateContent()
        }
    }

    private fun validateAndComputeSelectionOverlay(
        outBox: RectF,
        outDeleteCenter: FloatArray,
        outRotateCenter: FloatArray
    ): PageContent? {
        val page = selPage ?: return null
        val s = canvasLayer.stack
        val pageToScreen = tmpPageMatrix
        if (s != null) {
            val idx = s.indexOf(page)
            if (idx < 0) {
                selPage = null
                selStrokes.clear()
                selTexts.clear()
                return null
            }
            canvasLayer.pageToScreen(idx, pageToScreen)
        } else {
            if (page !== canvasLayer.page) {
                selPage = null
                selStrokes.clear()
                selTexts.clear()
                return null
            }
            pageToScreen.set(viewport.worldToScreen)
        }

        for (i in selStrokes.indices.reversed()) {
            if (!page.strokes.contains(selStrokes[i])) {
                selStrokes.removeAt(i)
            }
        }
        for (i in selTexts.indices.reversed()) {
            if (!page.texts.contains(selTexts[i])) {
                selTexts.removeAt(i)
            }
        }
        if (selStrokes.isEmpty() && selTexts.isEmpty()) {
            selPage = null
            return null
        }

        var hasUnion = false
        val unionRect = tmpUnionRect
        for (i in 0 until selStrokes.size) {
            if (selStrokes[i].worldBounds(tmpRectF)) {
                if (!hasUnion) {
                    unionRect.set(tmpRectF)
                    hasUnion = true
                } else {
                    if (tmpRectF.left < unionRect.left) unionRect.left = tmpRectF.left
                    if (tmpRectF.top < unionRect.top) unionRect.top = tmpRectF.top
                    if (tmpRectF.right > unionRect.right) unionRect.right = tmpRectF.right
                    if (tmpRectF.bottom > unionRect.bottom) unionRect.bottom = tmpRectF.bottom
                }
            }
        }
        for (i in 0 until selTexts.size) {
            selTexts[i].worldBounds(tmpRectF)
            if (!hasUnion) {
                unionRect.set(tmpRectF)
                hasUnion = true
            } else {
                if (tmpRectF.left < unionRect.left) unionRect.left = tmpRectF.left
                if (tmpRectF.top < unionRect.top) unionRect.top = tmpRectF.top
                if (tmpRectF.right > unionRect.right) unionRect.right = tmpRectF.right
                if (tmpRectF.bottom > unionRect.bottom) unionRect.bottom = tmpRectF.bottom
            }
        }
        if (!hasUnion) {
            selPage = null
            selStrokes.clear()
            selTexts.clear()
            return null
        }

        pageToScreen.mapRect(outBox, unionRect)
        val pad = editPadDp * density
        outBox.inset(-pad, -pad)

        val margin = 4f * density
        val halfBtn = deleteBtnVisualSizeDp * density / 2f
        val gap = deleteBtnGapDp * density
        val minX = halfBtn + margin
        val maxX = (width - halfBtn - margin).coerceAtLeast(minX)
        val cx = outBox.centerX().coerceIn(minX, maxX)
        val aboveY = outBox.top - gap - halfBtn
        val deleteIsBelow = aboveY - halfBtn < margin
        val preferredY = if (!deleteIsBelow) aboveY else outBox.bottom + gap + halfBtn
        val minY = halfBtn + margin
        val maxY = (height - halfBtn - margin).coerceAtLeast(minY)
        val cy = preferredY.coerceIn(minY, maxY)
        outDeleteCenter[0] = cx
        outDeleteCenter[1] = cy

        selRotateOnLeft = computeRotateButtonCenter(outBox, deleteIsBelow, outRotateCenter)
        return page
    }

    private fun computeRotateButtonCenter(
        box: RectF,
        avoidBelow: Boolean,
        outCenter: FloatArray
    ): Boolean {
        val margin = 4f * density
        val halfBtn = deleteBtnVisualSizeDp * density / 2f
        val gap = deleteBtnGapDp * density
        val minX = halfBtn + margin
        val maxX = (width - halfBtn - margin).coerceAtLeast(minX)
        val minY = halfBtn + margin
        val maxY = (height - halfBtn - margin).coerceAtLeast(minY)
        val belowY = box.bottom + gap + halfBtn
        val fitsBelow = !avoidBelow && belowY + halfBtn <= height - margin
        if (fitsBelow) {
            outCenter[0] = box.centerX().coerceIn(minX, maxX)
            outCenter[1] = belowY.coerceIn(minY, maxY)
            return false
        }
        val leftX = box.left - gap - halfBtn
        outCenter[0] = leftX.coerceIn(minX, maxX)
        outCenter[1] = box.centerY().coerceIn(minY, maxY)
        return true
    }

    private fun drawRotateConnector(canvas: Canvas, box: RectF, center: FloatArray, onLeft: Boolean) {
        val rotCx = center[0]
        val rotCy = center[1]
        if (onLeft) {
            if (rotCx < box.left) {
                canvas.drawLine(box.left, rotCy.coerceIn(box.top, box.bottom), rotCx, rotCy, framePaint)
            }
        } else {
            if (rotCy > box.bottom) {
                canvas.drawLine(rotCx.coerceIn(box.left, box.right), box.bottom, rotCx, rotCy, framePaint)
            }
        }
    }

    private fun drawFrameWithCornerHandles(canvas: Canvas, box: RectF) {
        canvas.drawRect(box, framePaint)
        val half = handleSizeDp * density / 2f
        for (k in 0 until 4) {
            val cx = cornerX(k, box.left, box.right)
            val cy = cornerY(k, box.top, box.bottom)
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, handleFillPaint)
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, handleBorderPaint)
        }
    }

    private fun drawRotateButton(canvas: Canvas, center: FloatArray, iconRect: RectF) {
        val rotCx = center[0]
        val rotCy = center[1]
        val btnRadius = deleteBtnVisualSizeDp * density / 2f
        val iconHalf = deleteBtnIconSizeDp * density / 2f
        canvas.drawCircle(rotCx, rotCy, btnRadius, handleFillPaint)
        canvas.drawCircle(rotCx, rotCy, btnRadius, handleBorderPaint)
        iconRect.set(rotCx - iconHalf, rotCy - iconHalf, rotCx + iconHalf, rotCy + iconHalf)
        Icons.draw(canvas, Icon.ROTATE, iconRect, rotateBtnPaint)
    }

    private fun drawSnapGuideLine(canvas: Canvas, box: RectF, cx: Float, cy: Float, totalDeg: Float) {
        val ext = 24f * density
        val isHorizontal = abs(round(totalDeg / 90f).toInt()) % 2 == 0
        if (isHorizontal) {
            canvas.drawLine(box.left - ext, cy, box.right + ext, cy, framePaint)
        } else {
            canvas.drawLine(cx, box.top - ext, cx, box.bottom + ext, framePaint)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        validateAndComputeSelectionOverlay(selScreenRect, selDeleteCenter, selRotateCenter) ?: return
        drawRotateConnector(canvas, selScreenRect, selRotateCenter, selRotateOnLeft)
        drawFrameWithCornerHandles(canvas, selScreenRect)
        val btnCx = selDeleteCenter[0]
        val btnCy = selDeleteCenter[1]
        val btnRadius = deleteBtnVisualSizeDp * density / 2f
        canvas.drawCircle(btnCx, btnCy, btnRadius, handleFillPaint)
        canvas.drawCircle(btnCx, btnCy, btnRadius, handleBorderPaint)
        val iconHalf = deleteBtnIconSizeDp * density / 2f
        selDeleteIconRect.set(btnCx - iconHalf, btnCy - iconHalf, btnCx + iconHalf, btnCy + iconHalf)
        Icons.draw(canvas, Icon.TRASH, selDeleteIconRect, deleteBtnPaint)

        drawRotateButton(canvas, selRotateCenter, selRotateIconRect)

        if (gestureState == GestureState.SELECT_ROTATE && rotateSnapKind == 2) {
            tmpPoints[0] = rotateCenterX + rotateClampDx
            tmpPoints[1] = rotateCenterY + rotateClampDy
            tmpPageMatrix.mapPoints(tmpPoints)
            drawSnapGuideLine(canvas, selScreenRect, tmpPoints[0], tmpPoints[1], (rotateBaseAngle ?: 0f) + rotateLastDeg)
        }
    }

    private fun drawLassoOverlay(canvas: Canvas) {
        if (gestureState != GestureState.SELECT_LASSO || lassoCount < 4) return
        val page = lassoPage ?: return
        val s = canvasLayer.stack
        if (s != null) {
            val idx = s.indexOf(page)
            if (idx < 0) return
            canvasLayer.pageToScreen(idx, tmpPageMatrix)
        } else {
            if (page !== canvasLayer.page) return
            tmpPageMatrix.set(viewport.worldToScreen)
        }
        lassoPath.reset()
        tmpPoints[0] = lassoBuffer[0]
        tmpPoints[1] = lassoBuffer[1]
        tmpPageMatrix.mapPoints(tmpPoints)
        lassoPath.moveTo(tmpPoints[0], tmpPoints[1])
        var i = 2
        while (i < lassoCount) {
            tmpPoints[0] = lassoBuffer[i]
            tmpPoints[1] = lassoBuffer[i + 1]
            tmpPageMatrix.mapPoints(tmpPoints)
            lassoPath.lineTo(tmpPoints[0], tmpPoints[1])
            i += 2
        }
        canvas.drawPath(lassoPath, lassoPaint)
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
        commitPendingShape()
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
