package com.example.tulis.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.tulis.canvas.NoteCanvasView
import com.example.tulis.model.PageContent
import kotlin.math.abs
import kotlin.math.max

class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var canvasView: NoteCanvasView? = null

    private val bgDrawable = SketchDrawable(
        fill = Palette.CARD,
        stroke = Palette.INK,
        strokeWidthDp = 2f,
        cornerDp = 10f
    )

    private val density = resources.displayMetrics.density
    private val padPx = 6f * density

    private val contentBounds = RectF()
    private val strokeBounds = RectF()
    private val worldMapRect = RectF()
    private val cachedWorldMapRect = RectF()
    private val viewRect = RectF()
    private val mapAreaRect = RectF()
    private val viewportMapRect = RectF()

    private val viewportFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Palette.YELLOW and 0x00FFFFFF or 0x28000000
    }

    private val viewportStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Palette.RED
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Palette.INK_SOFT
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Palette.INK_SOFT
    }

    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private var lastRevision = -1
    private var lastPage: PageContent? = null

    init {
        background = bgDrawable
        contentDescription = "Peta mini, ketuk atau seret untuk berpindah"
        isFocusable = false
    }

    fun refresh() {
        val cv = canvasView ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val mapW = w - 2f * padPx
        val mapH = h - 2f * padPx
        if (mapW <= 0f || mapH <= 0f) return

        calculateWorldBounds(cv, worldMapRect)

        val page = cv.getPage()
        val currentRev = page?.revision ?: 0
        val needsRedraw = cachedBitmap == null ||
                page !== lastPage ||
                currentRev != lastRevision ||
                hasWorldBoundsChangedSignificantly(cachedWorldMapRect, worldMapRect)

        if (needsRedraw) {
            updateCachedBitmap(cv, mapW.toInt(), mapH.toInt(), worldMapRect)
            cachedWorldMapRect.set(worldMapRect)
            lastRevision = currentRev
            lastPage = page
        }

        invalidate()
    }

    private fun hasWorldBoundsChangedSignificantly(old: RectF, new: RectF): Boolean {
        if (old.isEmpty || new.isEmpty) return true
        val dw = abs(new.width() - old.width()) / max(1f, old.width())
        val dh = abs(new.height() - old.height()) / max(1f, old.height())
        val dl = abs(new.left - old.left) / max(1f, old.width())
        val dt = abs(new.top - old.top) / max(1f, old.height())
        return dw > 0.02f || dh > 0.02f || dl > 0.02f || dt > 0.02f
    }

    private fun calculateWorldBounds(cv: NoteCanvasView, out: RectF) {
        val cvW = cv.width
        val cvH = cv.height
        if (cvW > 0 && cvH > 0) {
            cv.viewport.visibleWorld(cvW, cvH, viewRect)
        } else {
            viewRect.set(-500f, -500f, 500f, 500f)
        }

        var hasContent = false
        val page = cv.getPage()
        if (page != null) {
            val strokes = page.strokes
            val strokeCount = strokes.size
            for (i in 0 until strokeCount) {
                if (strokes[i].worldBounds(strokeBounds)) {
                    if (!hasContent) {
                        contentBounds.set(strokeBounds)
                        hasContent = true
                    } else {
                        contentBounds.union(strokeBounds)
                    }
                }
            }
            val texts = page.texts
            val textCount = texts.size
            for (i in 0 until textCount) {
                texts[i].worldBounds(strokeBounds)
                if (!hasContent) {
                    contentBounds.set(strokeBounds)
                    hasContent = true
                } else {
                    contentBounds.union(strokeBounds)
                }
            }
        }

        if (hasContent) {
            out.set(contentBounds)
            out.union(viewRect)
        } else {
            out.set(viewRect)
        }

        val padW = out.width() * 0.10f
        val padH = out.height() * 0.10f
        val pad = max(20f, max(padW, padH))
        out.inset(-pad, -pad)
    }

    private fun updateCachedBitmap(cv: NoteCanvasView, bmpW: Int, bmpH: Int, world: RectF) {
        if (bmpW <= 0 || bmpH <= 0) return
        var bmp = cachedBitmap
        if (bmp == null || bmp.width != bmpW || bmp.height != bmpH) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            cachedBitmap = bmp
            cachedCanvas = Canvas(bmp)
        }
        val canvas = cachedCanvas ?: return
        bmp.eraseColor(0)

        val page = cv.getPage() ?: return
        val strokes = page.strokes
        val texts = page.texts
        if (strokes.isEmpty() && texts.isEmpty()) return

        val rw = max(1f, world.width())
        val rh = max(1f, world.height())
        val scale = minOf(bmpW / rw, bmpH / rh)
        val scaledW = rw * scale
        val scaledH = rh * scale
        val ox = (bmpW - scaledW) * 0.5f - world.left * scale
        val oy = (bmpH - scaledH) * 0.5f - world.top * scale

        val strokeCount = strokes.size
        val drawOnlyBounds = strokeCount > 3000

        for (i in 0 until strokeCount) {
            val stroke = strokes[i]
            if (stroke.worldBounds(strokeBounds)) {
                val l = strokeBounds.left * scale + ox
                val t = strokeBounds.top * scale + oy
                val r = strokeBounds.right * scale + ox
                val b = strokeBounds.bottom * scale + oy
                if (drawOnlyBounds) {
                    canvas.drawRect(l, t, r, b, strokePaint)
                } else {
                    val pts = stroke.worldPoints()
                    val ptCount = pts.size
                    if (ptCount >= 4) {
                        var px = pts[0] * scale + ox
                        var py = pts[1] * scale + oy
                        var j = 2
                        while (j < ptCount) {
                            val nx = pts[j] * scale + ox
                            val ny = pts[j + 1] * scale + oy
                            canvas.drawLine(px, py, nx, ny, strokePaint)
                            px = nx
                            py = ny
                            j += 2
                        }
                    } else {
                        canvas.drawRect(l, t, r, b, strokePaint)
                    }
                }
            }
        }

        val textCount = texts.size
        for (i in 0 until textCount) {
            val text = texts[i]
            text.worldBounds(strokeBounds)
            val l = strokeBounds.left * scale + ox
            val t = strokeBounds.top * scale + oy
            val r = strokeBounds.right * scale + ox
            val b = strokeBounds.bottom * scale + oy
            canvas.drawRect(l, t, r, b, textPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refresh()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cv = canvasView ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val mapW = w - 2f * padPx
        val mapH = h - 2f * padPx
        if (mapW <= 0f || mapH <= 0f) return

        cachedBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, padPx, padPx, null)
        }

        val world = cachedWorldMapRect
        if (world.isEmpty) return

        val rw = max(1f, world.width())
        val rh = max(1f, world.height())
        val scale = minOf(mapW / rw, mapH / rh)
        val scaledW = rw * scale
        val scaledH = rh * scale
        val ox = padPx + (mapW - scaledW) * 0.5f - world.left * scale
        val oy = padPx + (mapH - scaledH) * 0.5f - world.top * scale

        val cvW = cv.width
        val cvH = cv.height
        if (cvW > 0 && cvH > 0) {
            cv.viewport.visibleWorld(cvW, cvH, viewRect)
        } else {
            viewRect.set(world)
        }

        val vl = viewRect.left * scale + ox
        val vt = viewRect.top * scale + oy
        val vr = viewRect.right * scale + ox
        val vb = viewRect.bottom * scale + oy
        viewportMapRect.set(vl, vt, vr, vb)

        canvas.drawRect(viewportMapRect, viewportFillPaint)
        canvas.drawRect(viewportMapRect, viewportStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cv = canvasView ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchAt(event.x, event.y, cv)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchAt(x: Float, y: Float, cv: NoteCanvasView) {
        val w = width
        val h = height
        val mapW = w - 2f * padPx
        val mapH = h - 2f * padPx
        if (mapW <= 0f || mapH <= 0f) return

        val world = cachedWorldMapRect
        if (world.isEmpty) return

        val rw = max(1f, world.width())
        val rh = max(1f, world.height())
        val scale = minOf(mapW / rw, mapH / rh)
        if (scale <= 0f) return

        val scaledW = rw * scale
        val scaledH = rh * scale
        val ox = padPx + (mapW - scaledW) * 0.5f - world.left * scale
        val oy = padPx + (mapH - scaledH) * 0.5f - world.top * scale

        val wx = (x - ox) / scale
        val wy = (y - oy) / scale

        cv.centerWorldOn(wx, wy)
    }
}
