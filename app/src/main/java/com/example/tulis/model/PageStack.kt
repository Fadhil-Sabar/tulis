package com.example.tulis.model

class PageStack(var pageCount: Int, val pageW: Float, val pageH: Float, val gap: Float) {
    companion object {
        const val GAP = 48f
        const val MAX_RESIDENT = 12
    }

    var generation: Int = 0

    private val residentMap = HashMap<Int, PageContent>()
    private val savedRevisions = HashMap<PageContent, Int>()
    private val order = ArrayList<PageContent>()
    private val redoOrder = ArrayList<PageContent>()

    fun offsetY(i: Int): Float = i * (pageH + gap)

    fun totalHeight(): Float = if (pageCount <= 0) 0f else pageCount * pageH + (pageCount - 1) * gap

    fun pageAt(worldY: Float): Int {
        if (worldY < 0f || pageCount <= 0) return -1
        val stride = pageH + gap
        val idx = (worldY / stride).toInt()
        if (idx < 0 || idx >= pageCount) return -1
        val top = offsetY(idx)
        val bottom = top + pageH
        return if (worldY in top..bottom) idx else -1
    }

    fun visibleRange(top: Float, bottom: Float): IntRange {
        if (pageCount <= 0 || top > bottom) return IntRange.EMPTY
        val stride = pageH + gap
        val firstCandidate = (top / stride).toInt().coerceAtLeast(0)
        val lastCandidate = (bottom / stride).toInt().coerceAtMost(pageCount - 1)
        if (firstCandidate > lastCandidate) return IntRange.EMPTY

        var first = -1
        var last = -1
        for (i in firstCandidate..lastCandidate) {
            val pTop = offsetY(i)
            val pBottom = pTop + pageH
            if (pBottom >= top && pTop <= bottom) {
                if (first == -1) first = i
                last = i
            }
        }
        return if (first != -1 && last != -1) first..last else IntRange.EMPTY
    }

    fun get(i: Int): PageContent? = residentMap[i]

    fun attach(i: Int, p: PageContent) {
        val existing = residentMap[i]
        if (existing != null && existing !== p) {
            existing.onActionPushed = null
            savedRevisions.remove(existing)
            cleanHistoryFor(existing)
        }
        residentMap[i] = p
        savedRevisions[p] = p.revision
        p.onActionPushed = { onActionPushed(it) }
    }

    fun evict(i: Int): PageContent? {
        val p = residentMap.remove(i) ?: return null
        p.onActionPushed = null
        savedRevisions.remove(p)
        cleanHistoryFor(p)
        return p
    }

    fun residentIndices(): List<Int> = ArrayList(residentMap.keys)

    fun dirtyPages(): List<Pair<Int, PageContent>> {
        val list = ArrayList<Pair<Int, PageContent>>()
        for ((idx, page) in residentMap) {
            val savedRev = savedRevisions[page]
            if (savedRev == null || page.revision != savedRev) {
                list.add(Pair(idx, page))
            }
        }
        return list
    }

    fun markSaved(p: PageContent) {
        savedRevisions[p] = p.revision
    }

    fun insertAt(i: Int, p: PageContent) {
        val shifted = ArrayList<Pair<Int, PageContent>>()
        val it = residentMap.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key >= i) {
                shifted.add(Pair(entry.key + 1, entry.value))
                it.remove()
            }
        }
        for (pair in shifted) {
            residentMap[pair.first] = pair.second
        }
        pageCount++
        generation++
        attach(i, p)
    }

    fun removeAt(i: Int) {
        val removed = residentMap.remove(i)
        if (removed != null) {
            removed.onActionPushed = null
            savedRevisions.remove(removed)
            cleanHistoryFor(removed)
        }
        val shifted = ArrayList<Pair<Int, PageContent>>()
        val it = residentMap.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key > i) {
                shifted.add(Pair(entry.key - 1, entry.value))
                it.remove()
            }
        }
        for (pair in shifted) {
            residentMap[pair.first] = pair.second
        }
        pageCount--
        generation++
    }

    fun indexOf(p: PageContent): Int {
        for ((idx, page) in residentMap) {
            if (page === p) return idx
        }
        return -1
    }

    fun undo(): PageContent? {
        while (order.isNotEmpty()) {
            val p = order.removeAt(order.size - 1)
            if (indexOf(p) == -1) continue
            if (p.undo()) {
                redoOrder.add(p)
                return p
            }
        }
        return null
    }

    fun redo(): PageContent? {
        while (redoOrder.isNotEmpty()) {
            val p = redoOrder.removeAt(redoOrder.size - 1)
            if (indexOf(p) == -1) continue
            if (p.redo()) {
                order.add(p)
                if (order.size > 500) {
                    order.removeAt(0)
                }
                return p
            }
        }
        return null
    }

    fun canUndo(): Boolean {
        for (i in order.indices.reversed()) {
            val p = order[i]
            if (indexOf(p) != -1 && p.canUndo()) return true
        }
        return false
    }

    fun canRedo(): Boolean {
        for (i in redoOrder.indices.reversed()) {
            val p = redoOrder[i]
            if (indexOf(p) != -1 && p.canRedo()) return true
        }
        return false
    }

    private fun onActionPushed(p: PageContent) {
        order.add(p)
        if (order.size > 500) {
            order.removeAt(0)
        }
        val seen = HashSet<PageContent>()
        for (i in 0 until redoOrder.size) {
            val page = redoOrder[i]
            if (seen.add(page)) {
                page.clearRedo()
            }
        }
        redoOrder.clear()
    }

    private fun cleanHistoryFor(p: PageContent) {
        val itOrder = order.iterator()
        while (itOrder.hasNext()) {
            if (itOrder.next() === p) {
                itOrder.remove()
            }
        }
        val itRedo = redoOrder.iterator()
        while (itRedo.hasNext()) {
            if (itRedo.next() === p) {
                itRedo.remove()
            }
        }
    }
}
