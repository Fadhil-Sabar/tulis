package com.example.tulis.model

import android.graphics.Matrix
import android.graphics.RectF

class Viewport {
    var scale: Float = 1f
        private set
    var tx: Float = 0f
        private set
    var ty: Float = 0f
        private set

    val worldToScreen: Matrix = Matrix()
    val screenToWorld: Matrix = Matrix()

    var onChanged: (() -> Unit)? = null

    private val pts = FloatArray(4)

    init {
        updateMatrices()
    }

    private fun updateMatrices() {
        worldToScreen.setScale(scale, scale)
        worldToScreen.postTranslate(tx, ty)
        worldToScreen.invert(screenToWorld)
    }

    fun set(scale: Float, tx: Float, ty: Float) {
        val clampedScale = scale.coerceIn(0.08f, 16f)
        if (this.scale == clampedScale && this.tx == tx && this.ty == ty) return
        this.scale = clampedScale
        this.tx = tx
        this.ty = ty
        updateMatrices()
        onChanged?.invoke()
    }

    fun pan(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        this.tx += dx
        this.ty += dy
        updateMatrices()
        onChanged?.invoke()
    }

    fun zoomAt(factor: Float, fx: Float, fy: Float) {
        if (factor == 1f) return
        val oldScale = scale
        val targetScale = (oldScale * factor).coerceIn(0.08f, 16f)
        if (targetScale == oldScale) return
        val effectiveFactor = targetScale / oldScale
        val newTx = fx - (fx - tx) * effectiveFactor
        val newTy = fy - (fy - ty) * effectiveFactor
        this.scale = targetScale
        this.tx = newTx
        this.ty = newTy
        updateMatrices()
        onChanged?.invoke()
    }

    fun visibleWorld(viewW: Int, viewH: Int, out: RectF) {
        pts[0] = 0f
        pts[1] = 0f
        pts[2] = viewW.toFloat()
        pts[3] = viewH.toFloat()
        screenToWorld.mapPoints(pts)
        val left = minOf(pts[0], pts[2])
        val right = maxOf(pts[0], pts[2])
        val top = minOf(pts[1], pts[3])
        val bottom = maxOf(pts[1], pts[3])
        out.set(left, top, right, bottom)
    }

    fun clampToPage(viewW: Int, viewH: Int, pageW: Float, pageH: Float, minVisiblePx: Float = 48f) {
        val sW = pageW * scale
        val sH = pageH * scale
        val minTx = minVisiblePx - sW
        val maxTx = viewW - minVisiblePx
        val minTy = minVisiblePx - sH
        val maxTy = viewH - minVisiblePx

        val clampedTx = if (minTx > maxTx) (viewW - sW) * 0.5f else tx.coerceIn(minTx, maxTx)
        val clampedTy = if (minTy > maxTy) (viewH - sH) * 0.5f else ty.coerceIn(minTy, maxTy)

        if (clampedTx != tx || clampedTy != ty) {
            tx = clampedTx
            ty = clampedTy
            updateMatrices()
            onChanged?.invoke()
        }
    }

    fun fitWidth(viewW: Int, viewH: Int, pageW: Float) {
        if (viewW <= 0 || pageW <= 0f) return
        val newScale = (viewW.toFloat() / pageW).coerceIn(0.08f, 16f)
        val newTx = (viewW - pageW * newScale) * 0.5f
        val newTy = 0f
        scale = newScale
        tx = newTx
        ty = newTy
        updateMatrices()
        onChanged?.invoke()
    }

    fun resetCenter(viewW: Int, viewH: Int) {
        scale = 1f
        tx = viewW * 0.5f
        ty = viewH * 0.5f
        updateMatrices()
        onChanged?.invoke()
    }
}
