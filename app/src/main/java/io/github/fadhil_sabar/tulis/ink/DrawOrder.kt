package io.github.fadhil_sabar.tulis.ink

import io.github.fadhil_sabar.tulis.model.BrushKind

inline fun <T> forEachLayered(
    items: List<T>,
    from: Int,
    to: Int,
    isBottom: (T) -> Boolean,
    action: (T) -> Unit
) {
    val end = minOf(to, items.size)
    for (i in from until end) {
        val item = items[i]
        if (isBottom(item)) action(item)
    }
    for (i in from until end) {
        val item = items[i]
        if (!isBottom(item)) action(item)
    }
}

inline fun forEachInDrawOrder(
    strokes: List<StrokeItem>,
    from: Int = 0,
    to: Int = strokes.size,
    action: (StrokeItem) -> Unit
) = forEachLayered(strokes, from, to, { it.brushKind == BrushKind.HIGHLIGHTER }, action)
