package com.example.tulis.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.example.tulis.model.BrushKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

interface LiveInkListener {
    fun onStrokeFinished(stroke: Stroke, kind: BrushKind, colorArgb: Int, sizePx: Float)
}

class LiveInk(val view: View) {
    private val inProgressView = view as InProgressStrokesView
    var listener: LiveInkListener? = null

    private data class ActiveStroke(
        val kind: BrushKind,
        val colorArgb: Int,
        val sizePx: Float
    )

    private val activeStrokes = HashMap<InProgressStrokeId, ActiveStroke>()

    init {
        inProgressView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                val currentListener = listener
                for ((id, stroke) in strokes) {
                    val active = activeStrokes.remove(id)
                    val kind = active?.kind ?: BrushKind.PEN
                    val colorArgb = active?.colorArgb ?: stroke.brush.colorLong.toInt()
                    val sizePx = active?.sizePx ?: stroke.brush.size
                    currentListener?.onStrokeFinished(stroke, kind, colorArgb, sizePx)
                }
            }
        })
    }

    fun start(event: MotionEvent, pointerId: Int, kind: BrushKind, colorArgb: Int, sizePx: Float): Any {
        val brush = InkBridge.brushFor(kind, colorArgb, sizePx)
        val id = inProgressView.startStroke(event, pointerId, brush)
        activeStrokes[id] = ActiveStroke(kind, colorArgb, sizePx)
        return id
    }

    fun add(event: MotionEvent, pointerId: Int, id: Any, predicted: MotionEvent? = null) {
        if (id is InProgressStrokeId) {
            inProgressView.addToStroke(event, pointerId, id, predicted)
        }
    }

    fun finish(event: MotionEvent, pointerId: Int, id: Any) {
        if (id is InProgressStrokeId) {
            inProgressView.finishStroke(event, pointerId, id)
        }
    }

    fun cancel(event: MotionEvent?, id: Any) {
        if (id is InProgressStrokeId) {
            activeStrokes.remove(id)
            inProgressView.cancelStroke(id, event)
        }
    }

    fun clearFinished(ids: Collection<Any>) {
        val strokeIds = HashSet<InProgressStrokeId>(ids.size)
        for (id in ids) {
            if (id is InProgressStrokeId) {
                strokeIds.add(id)
            }
        }
        if (strokeIds.isNotEmpty()) {
            inProgressView.removeFinishedStrokes(strokeIds)
        }
    }
}

object InkBridge {
    private val penFamily: BrushFamily by lazy { StockBrushes.pressurePen() }
    private val markerFamily: BrushFamily by lazy { StockBrushes.marker() }
    private val highlighterFamily: BrushFamily by lazy { StockBrushes.highlighter() }

    fun createRenderer(): CanvasStrokeRenderer = CanvasStrokeRenderer.create()

    fun draw(
        renderer: CanvasStrokeRenderer,
        canvas: Canvas,
        item: StrokeItem,
        worldToScreen: Matrix,
        tmp: Matrix
    ) {
        tmp.set(item.strokeToWorld)
        tmp.postConcat(worldToScreen)
        canvas.save()
        canvas.concat(tmp)
        renderer.draw(canvas, item.stroke, tmp)
        canvas.restore()
    }

    fun brushFor(kind: BrushKind, colorArgb: Int, sizePx: Float): Brush {
        return when (kind) {
            BrushKind.PEN -> Brush.createWithColorIntArgb(penFamily, colorArgb, sizePx, 0.1f)
            BrushKind.MARKER -> Brush.createWithColorIntArgb(markerFamily, colorArgb, sizePx, 0.1f)
            BrushKind.HIGHLIGHTER -> {
                val alpha = (colorArgb ushr 24) and 0xFF
                val highColor = if (alpha == 0xFF || alpha == 0) {
                    (colorArgb and 0x00FFFFFF) or 0x66000000
                } else {
                    colorArgb
                }
                Brush.createWithColorIntArgb(highlighterFamily, highColor, sizePx, 0.1f)
            }
        }
    }

    fun encodeInputs(item: StrokeItem): ByteArray {
        val cached = item.encodedInputs
        if (cached != null) return cached
        val baos = ByteArrayOutputStream()
        item.stroke.inputs.encode(baos)
        val bytes = baos.toByteArray()
        item.encodedInputs = bytes
        return bytes
    }

    fun decodeStroke(
        kind: BrushKind,
        colorArgb: Int,
        sizePx: Float,
        matrix: Matrix,
        inputs: ByteArray
    ): StrokeItem? {
        return try {
            val bais = ByteArrayInputStream(inputs)
            val batch = StrokeInputBatch.decode(bais)
            val brush = brushFor(kind, colorArgb, sizePx)
            val stroke = Stroke(brush, batch)
            val matCopy = Matrix(matrix)
            StrokeItem(stroke, kind, colorArgb, sizePx, matCopy, inputs)
        } catch (t: Throwable) {
            null
        }
    }

    fun newLiveInk(context: Context): LiveInk {
        val view = InProgressStrokesView(context)
        return LiveInk(view)
    }
}
