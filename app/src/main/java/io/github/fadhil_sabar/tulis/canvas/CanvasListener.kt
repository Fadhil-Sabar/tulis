package io.github.fadhil_sabar.tulis.canvas

import io.github.fadhil_sabar.tulis.model.TextItem

interface CanvasListener {
    fun onContentChanged()
    fun onRequestTextEdit(item: TextItem?, wx: Float, wy: Float)
    fun onHistoryChanged()
    fun onViewportChanged()
    fun onVisiblePageChanged(index: Int) {}
    fun onPageWindowChanged(first: Int, last: Int) {}
}
