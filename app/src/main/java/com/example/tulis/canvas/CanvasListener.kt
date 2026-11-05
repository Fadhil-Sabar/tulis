package com.example.tulis.canvas

import com.example.tulis.model.TextItem

interface CanvasListener {
    fun onContentChanged()
    fun onRequestTextEdit(item: TextItem?, wx: Float, wy: Float)
    fun onHistoryChanged()
    fun onViewportChanged()
}
