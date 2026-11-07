package io.github.fadhil_sabar.tulis

import io.github.fadhil_sabar.tulis.ink.forEachLayered
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawOrderTest {
    private fun order(items: List<String>, from: Int = 0, to: Int = items.size): List<String> {
        val out = ArrayList<String>()
        forEachLayered(items, from, to, { it.startsWith("h") }) { out.add(it) }
        return out
    }

    @Test
    fun bottomItemsComeFirstKeepingRelativeOrder() {
        assertEquals(listOf("h1", "h2", "p1", "p2"), order(listOf("p1", "h1", "p2", "h2")))
    }

    @Test
    fun rangeIsRespected() {
        assertEquals(listOf("h2", "p2"), order(listOf("p1", "h1", "p2", "h2"), 2, 4))
    }

    @Test
    fun endIsClampedToSize() {
        assertEquals(listOf("h1", "p1"), order(listOf("p1", "h1"), 0, 10))
    }

    @Test
    fun emptyListDoesNothing() {
        assertEquals(emptyList<String>(), order(emptyList()))
    }
}
