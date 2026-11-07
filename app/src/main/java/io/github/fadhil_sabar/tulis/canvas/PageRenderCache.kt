package io.github.fadhil_sabar.tulis.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.Trace
import io.github.fadhil_sabar.tulis.ink.InkBridge
import io.github.fadhil_sabar.tulis.ink.InkRenderer
import io.github.fadhil_sabar.tulis.model.PageContent
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class PageRenderCache(context: Context) {
    private val density: Float = context.resources.displayMetrics.density
    private val strokeRenderer: InkRenderer = InkBridge.createRenderer()

    private val tmpMatrix = Matrix()
    private val tmpItemMatrix = Matrix()
    private val strokeBoundsRect = RectF()
    private val screenBoundsRect = RectF()
    private val matrixValues = FloatArray(9)

    private class PageNode(val node: RenderNode) {
        var recRevision: Int = -1
        var recCount: Int = 0
        var recScale: Float = 1f
        var recTx: Float = 0f
        var recTy: Float = 0f
        val recBounds = RectF()
        var dirty: Boolean = true
    }

    private val nodes = IdentityHashMap<PageContent, PageNode>()

    fun draw(
        canvas: Canvas,
        page: PageContent,
        pageToScreen: Matrix,
        scale: Float,
        gestureActive: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
            drawStrokesAccelerated(canvas, page, pageToScreen, scale, gestureActive)
        } else {
            drawStrokesDirect(canvas, page, pageToScreen, 0, page.strokes.size)
        }
    }

    private fun drawStrokesAccelerated(
        canvas: Canvas,
        page: PageContent,
        pageToScreen: Matrix,
        scale: Float,
        gestureActive: Boolean
    ) {
        var entry = nodes[page]
        if (entry == null) {
            val node = RenderNode("PageRenderCacheNode").apply {
                setClipToBounds(false)
            }
            entry = PageNode(node)
            nodes[page] = entry
        }

        pageToScreen.getValues(matrixValues)
        val curTx = matrixValues[Matrix.MTRANS_X]
        val curTy = matrixValues[Matrix.MTRANS_Y]

        val strokes = page.strokes
        val currentSize = strokes.size
        val node = entry.node

        var needRecord = false
        if (entry.dirty || !node.hasDisplayList() || currentSize < entry.recCount) {
            needRecord = true
        } else if (page.revision != entry.recRevision) {
            needRecord = true
        } else if (!gestureActive && scale != entry.recScale) {
            needRecord = true
        } else if (gestureActive && entry.recScale > 0f) {
            val k = scale / entry.recScale
            if (k < 0.5f || k > 2.0f) {
                needRecord = true
            }
        }

        if (needRecord) {
            record(entry, page, pageToScreen, scale, curTx, curTy)
        }

        val k = if (entry.recScale > 0f) scale / entry.recScale else 1f
        val saveCount = canvas.save()
        canvas.translate(curTx - k * entry.recTx, curTy - k * entry.recTy)
        canvas.scale(k, k)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(saveCount)

        val pendingCount = currentSize - entry.recCount
        if (pendingCount > 0) {
            drawStrokesDirect(canvas, page, pageToScreen, entry.recCount, currentSize)
            if (pendingCount > 32 && !gestureActive) {
                record(entry, page, pageToScreen, scale, curTx, curTy)
            }
        }
    }

    private fun record(
        entry: PageNode,
        page: PageContent,
        pageToScreen: Matrix,
        scale: Float,
        curTx: Float,
        curTy: Float
    ) {
        Trace.beginSection("PageRenderCache.record")
        try {
            entry.recRevision = page.revision
            entry.recCount = page.strokes.size
            entry.recScale = scale
            entry.recTx = curTx
            entry.recTy = curTy
            entry.dirty = false

            val node = entry.node
            val strokes = page.strokes
            val strokeCount = strokes.size

            if (strokeCount == 0) {
                entry.recBounds.setEmpty()
                node.setPosition(0, 0, 0, 0)
                node.beginRecording(1, 1)
                node.endRecording()
                return
            }

            var hasUnion = false
            for (i in 0 until strokeCount) {
                val item = strokes[i]
                if (item.worldBounds(strokeBoundsRect)) {
                    pageToScreen.mapRect(strokeBoundsRect)
                    if (!hasUnion) {
                        entry.recBounds.set(strokeBoundsRect)
                        hasUnion = true
                    } else {
                        entry.recBounds.union(strokeBoundsRect)
                    }
                }
            }

            if (!hasUnion) {
                entry.recBounds.setEmpty()
                node.setPosition(0, 0, 0, 0)
                node.beginRecording(1, 1)
                node.endRecording()
                return
            }

            val margin = 8f * density
            val left = floor(entry.recBounds.left - margin).toInt()
            val top = floor(entry.recBounds.top - margin).toInt()
            val right = ceil(entry.recBounds.right + margin).toInt()
            val bottom = ceil(entry.recBounds.bottom + margin).toInt()

            val nodeWidth = max(1, right - left)
            val nodeHeight = max(1, bottom - top)

            entry.recBounds.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            node.setPosition(left, top, right, bottom)

            val renderCanvas = node.beginRecording(nodeWidth, nodeHeight)
            try {
                renderCanvas.translate(-left.toFloat(), -top.toFloat())
                for (i in 0 until strokeCount) {
                    val strokeItem = strokes[i]
                    tmpItemMatrix.set(pageToScreen)
                    InkBridge.draw(strokeRenderer, renderCanvas, strokeItem, tmpItemMatrix, tmpMatrix)
                }
            } finally {
                node.endRecording()
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawStrokesDirect(
        canvas: Canvas,
        page: PageContent,
        pageToScreen: Matrix,
        startIndex: Int,
        endIndex: Int
    ) {
        val strokes = page.strokes
        val limit = minOf(endIndex, strokes.size)
        screenBoundsRect.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())

        for (i in startIndex until limit) {
            val strokeItem = strokes[i]
            if (strokeItem.worldBounds(strokeBoundsRect)) {
                pageToScreen.mapRect(strokeBoundsRect)
                if (!RectF.intersects(strokeBoundsRect, screenBoundsRect)) {
                    continue
                }
            }
            tmpItemMatrix.set(pageToScreen)
            InkBridge.draw(strokeRenderer, canvas, strokeItem, tmpItemMatrix, tmpMatrix)
        }
    }

    fun onStrokeAppended(page: PageContent) {
        val entry = nodes[page] ?: return
        if (entry.recRevision == page.revision - 1) {
            entry.recRevision = page.revision
        } else {
            entry.dirty = true
        }
    }

    fun invalidate(page: PageContent) {
        val entry = nodes[page] ?: return
        entry.dirty = true
    }

    fun discard(page: PageContent) {
        val entry = nodes.remove(page) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            entry.node.discardDisplayList()
        }
    }

    fun discardAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (entry in nodes.values) {
                entry.node.discardDisplayList()
            }
        }
        nodes.clear()
    }
}
