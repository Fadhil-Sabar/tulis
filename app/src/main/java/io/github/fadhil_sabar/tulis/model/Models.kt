package io.github.fadhil_sabar.tulis.model

import android.graphics.RectF
import io.github.fadhil_sabar.tulis.ink.StrokeItem

enum class NoteMode { INFINITE, PAGED }
enum class PaperStyle { PLAIN, DOTS, GRID, LINES }
enum class Tool { PEN, MARKER, HIGHLIGHTER, ERASER, TEXT, PAN, SHAPE, SELECT }
enum class BrushKind { PEN, MARKER, HIGHLIGHTER }

enum class PageOrientation { PORTRAIT, LANDSCAPE }
val PageOrientation.pageW: Float
    get() = if (this == PageOrientation.LANDSCAPE) 1414f else 1000f
val PageOrientation.pageH: Float
    get() = if (this == PageOrientation.LANDSCAPE) 1000f else 1414f

data class NotebookMeta(
    val id: String, var title: String, val mode: NoteMode,
    var pageCount: Int, var updatedAt: Long,
    var vpScale: Float = 1f, var vpTx: Float = 0f, var vpTy: Float = 0f,
    var lastPage: Int = 0,
    var paperStyle: PaperStyle = PaperStyle.DOTS,
    var paperSpacing: Float = 28f,
    var orientation: PageOrientation = PageOrientation.PORTRAIT,
    var folderId: String? = null
)

data class Folder(val id: String, var name: String, var parentId: String?, val createdAt: Long)

class TextItem(
    var x: Float,
    var y: Float,
    var sizeWorld: Float,
    var colorArgb: Int,
    var widthWorld: Float,
    var text: String
) {
    fun worldBounds(out: RectF) {
        var lines = 1
        var i = 0
        val len = text.length
        while (i < len) {
            if (text[i] == '\n') {
                lines++
            }
            i++
        }
        val lineHeight = sizeWorld * 1.3f
        val heightWorld = lines * lineHeight
        out.set(x, y, x + widthWorld, y + heightWorld)
    }
}

class PageContent {
    val strokes: MutableList<StrokeItem> = ArrayList()
    val texts: MutableList<TextItem> = ArrayList()
    var revision: Int = 0
    var onActionPushed: ((PageContent) -> Unit)? = null

    private val undoStack: MutableList<Action> = ArrayList()
    private val redoStack: MutableList<Action> = ArrayList()
    private var activeEraseGroup: EraseGroupAction? = null

    private interface Action {
        fun undo()
        fun redo()
    }

    private inner class AddStrokeAction(val stroke: StrokeItem) : Action {
        override fun undo() {
            strokes.remove(stroke)
        }
        override fun redo() {
            strokes.add(stroke)
        }
    }

    private inner class RemoveStrokesAction(val removed: List<StrokeItem>) : Action {
        override fun undo() {
            strokes.addAll(removed)
        }
        override fun redo() {
            strokes.removeAll(removed)
        }
    }

    private inner class EraseGroupAction : Action {
        val allRemoved: MutableList<StrokeItem> = ArrayList()

        fun addAll(items: List<StrokeItem>) {
            allRemoved.addAll(items)
        }

        override fun undo() {
            strokes.addAll(allRemoved)
        }
        override fun redo() {
            strokes.removeAll(allRemoved)
        }
    }

    private inner class AddTextAction(val text: TextItem) : Action {
        override fun undo() {
            texts.remove(text)
        }
        override fun redo() {
            texts.add(text)
        }
    }

    private inner class RemoveTextAction(val text: TextItem) : Action {
        override fun undo() {
            texts.add(text)
        }
        override fun redo() {
            texts.remove(text)
        }
    }

    private inner class EditTextAction(val text: TextItem, val oldVal: String, val newVal: String) : Action {
        override fun undo() {
            text.text = oldVal
        }
        override fun redo() {
            text.text = newVal
        }
    }

    private class TextMoveEntry(
        val item: TextItem,
        val oldX: Float,
        val oldY: Float,
        val newX: Float,
        val newY: Float,
        val hasSize: Boolean = false,
        val oldSize: Float = 0f,
        val oldWidth: Float = 0f,
        val newSize: Float = 0f,
        val newWidth: Float = 0f
    )

    private inner class RemoveItemsAction(
        val strokeEntries: List<Pair<Int, StrokeItem>>,
        val textEntries: List<Pair<Int, TextItem>>
    ) : Action {
        override fun undo() {
            for (i in 0 until strokeEntries.size) {
                val entry = strokeEntries[i]
                strokes.add(entry.first.coerceIn(0, strokes.size), entry.second)
            }
            for (i in 0 until textEntries.size) {
                val entry = textEntries[i]
                texts.add(entry.first.coerceIn(0, texts.size), entry.second)
            }
        }

        override fun redo() {
            for (i in strokeEntries.indices.reversed()) {
                val idx = strokeEntries[i].first
                if (idx in 0 until strokes.size) {
                    strokes.removeAt(idx)
                }
            }
            for (i in textEntries.indices.reversed()) {
                val idx = textEntries[i].first
                if (idx in 0 until texts.size) {
                    texts.removeAt(idx)
                }
            }
        }
    }

    private inner class ReplaceItemsAction(
        val strokePairs: List<Pair<StrokeItem, StrokeItem>>,
        val textEntries: List<TextMoveEntry>
    ) : Action {
        override fun undo() {
            for (i in 0 until strokePairs.size) {
                val pair = strokePairs[i]
                val idx = strokes.indexOf(pair.second)
                if (idx >= 0) {
                    strokes[idx] = pair.first
                }
            }
            for (i in 0 until textEntries.size) {
                val e = textEntries[i]
                e.item.x = e.oldX
                e.item.y = e.oldY
                if (e.hasSize) {
                    e.item.sizeWorld = e.oldSize
                    e.item.widthWorld = e.oldWidth
                }
            }
        }

        override fun redo() {
            for (i in 0 until strokePairs.size) {
                val pair = strokePairs[i]
                val idx = strokes.indexOf(pair.first)
                if (idx >= 0) {
                    strokes[idx] = pair.second
                }
            }
            for (i in 0 until textEntries.size) {
                val e = textEntries[i]
                e.item.x = e.newX
                e.item.y = e.newY
                if (e.hasSize) {
                    e.item.sizeWorld = e.newSize
                    e.item.widthWorld = e.newWidth
                }
            }
        }
    }

    private fun pushAction(action: Action) {
        if (undoStack.size >= 100) {
            undoStack.removeAt(0)
        }
        undoStack.add(action)
        redoStack.clear()
        revision++
        onActionPushed?.invoke(this)
    }

    fun addStroke(s: StrokeItem) {
        strokes.add(s)
        pushAction(AddStrokeAction(s))
    }

    fun removeStrokes(list: List<StrokeItem>) {
        if (list.isEmpty()) return
        strokes.removeAll(list)
        val group = activeEraseGroup
        if (group != null) {
            group.addAll(list)
            revision++
        } else {
            pushAction(RemoveStrokesAction(ArrayList(list)))
        }
    }

    fun addText(t: TextItem) {
        texts.add(t)
        pushAction(AddTextAction(t))
    }

    fun removeText(t: TextItem) {
        if (texts.remove(t)) {
            pushAction(RemoveTextAction(t))
        }
    }

    fun editText(t: TextItem, newText: String) {
        val old = t.text
        if (old == newText) return
        t.text = newText
        pushAction(EditTextAction(t, old, newText))
    }

    fun removeItems(strokes: List<StrokeItem>, texts: List<TextItem>) {
        val strokeEntries = ArrayList<Pair<Int, StrokeItem>>()
        val seenStrokeIdx = HashSet<Int>()
        for (i in 0 until strokes.size) {
            val s = strokes[i]
            val idx = this.strokes.indexOf(s)
            if (idx >= 0 && seenStrokeIdx.add(idx)) {
                strokeEntries.add(Pair(idx, s))
            }
        }
        strokeEntries.sortBy { it.first }

        val textEntries = ArrayList<Pair<Int, TextItem>>()
        val seenTextIdx = HashSet<Int>()
        for (i in 0 until texts.size) {
            val t = texts[i]
            val idx = this.texts.indexOf(t)
            if (idx >= 0 && seenTextIdx.add(idx)) {
                textEntries.add(Pair(idx, t))
            }
        }
        textEntries.sortBy { it.first }

        if (strokeEntries.isEmpty() && textEntries.isEmpty()) return
        val action = RemoveItemsAction(strokeEntries, textEntries)
        action.redo()
        pushAction(action)
    }

    fun replaceItems(
        oldStrokes: List<StrokeItem>,
        newStrokes: List<StrokeItem>,
        texts: List<TextItem>,
        oldTextXY: List<FloatArray>,
        newTextXY: List<FloatArray>
    ) {
        val strokePairs = ArrayList<Pair<StrokeItem, StrokeItem>>()
        val strokeCount = minOf(oldStrokes.size, newStrokes.size)
        for (i in 0 until strokeCount) {
            val oldS = oldStrokes[i]
            val newS = newStrokes[i]
            if (oldS !== newS && this.strokes.indexOf(oldS) >= 0) {
                strokePairs.add(Pair(oldS, newS))
            }
        }

        val textEntries = ArrayList<TextMoveEntry>()
        val textCount = minOf(texts.size, oldTextXY.size, newTextXY.size)
        for (i in 0 until textCount) {
            val oldXY = oldTextXY[i]
            val newXY = newTextXY[i]
            if (oldXY.size >= 2 && newXY.size >= 2) {
                val hasSize = oldXY.size >= 4 && newXY.size >= 4
                val changed = oldXY[0] != newXY[0] || oldXY[1] != newXY[1] ||
                    (hasSize && (oldXY[2] != newXY[2] || oldXY[3] != newXY[3]))
                if (changed) {
                    textEntries.add(
                        if (hasSize) {
                            TextMoveEntry(
                                texts[i],
                                oldXY[0], oldXY[1], newXY[0], newXY[1],
                                true, oldXY[2], oldXY[3], newXY[2], newXY[3]
                            )
                        } else {
                            TextMoveEntry(texts[i], oldXY[0], oldXY[1], newXY[0], newXY[1])
                        }
                    )
                }
            }
        }

        if (strokePairs.isEmpty() && textEntries.isEmpty()) return
        val action = ReplaceItemsAction(strokePairs, textEntries)
        action.redo()
        pushAction(action)
    }

    fun beginEraseGroup() {
        if (activeEraseGroup == null) {
            activeEraseGroup = EraseGroupAction()
        }
    }

    fun endEraseGroup() {
        val group = activeEraseGroup ?: return
        activeEraseGroup = null
        if (group.allRemoved.isNotEmpty()) {
            if (undoStack.size >= 100) {
                undoStack.removeAt(0)
            }
            undoStack.add(group)
            redoStack.clear()
            onActionPushed?.invoke(this)
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val action = undoStack.removeAt(undoStack.size - 1)
        action.undo()
        redoStack.add(action)
        revision++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val action = redoStack.removeAt(redoStack.size - 1)
        action.redo()
        undoStack.add(action)
        revision++
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clearRedo() {
        redoStack.clear()
    }

    fun snapshot(): PageContent {
        val copy = PageContent()
        copy.strokes.addAll(strokes)
        for (i in 0 until texts.size) {
            val t = texts[i]
            copy.texts.add(TextItem(t.x, t.y, t.sizeWorld, t.colorArgb, t.widthWorld, t.text))
        }
        copy.revision = revision
        return copy
    }
}
