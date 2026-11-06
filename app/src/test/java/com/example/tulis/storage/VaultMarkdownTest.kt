package com.example.tulis.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class VaultMarkdownTest {
    private val ts = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 7, 0, 30, 0)
    }.timeInMillis
    private val embeds = listOf("![](_assets/nb1/p001.png)", "![](_assets/nb1/p002.png)")

    private fun created(folder: String = "Kuliah/Fisika") =
        VaultMarkdown.create("nb1", "Fisika", ts, 2, folder, "h1", embeds)

    private fun lines(vararg l: String) = l.joinToString("\n")

    private fun text(m: VaultMarkdown.Merge) = (m as VaultMarkdown.Merge.Ok).text

    @Test
    fun fileNameStripsForbiddenAndControlChars() {
        assertEquals("abcd.md", VaultMarkdown.fileName("a/b:c*?\"<>|#^[]d", "nb1", false))
        assertEquals("ab.md", VaultMarkdown.fileName("a\u0001b\u007f", "nb1", false))
    }

    @Test
    fun fileNameCollapsesSpacesAndTrimsDotsAndSpaces() {
        assertEquals("a b.md", VaultMarkdown.fileName("a    b", "nb1", false))
        assertEquals("Fisika.md", VaultMarkdown.fileName("  ..Fisika..  ", "nb1", false))
    }

    @Test
    fun fileNameFallsBackWhenEmpty() {
        assertEquals("Tanpa judul.md", VaultMarkdown.fileName("", "nb1", false))
        assertEquals("Tanpa judul.md", VaultMarkdown.fileName(" ..#/ ", "nb1", false))
    }

    @Test
    fun fileNameLimitsLengthAndTrimsAfterCut() {
        assertEquals("x".repeat(80) + ".md", VaultMarkdown.fileName("x".repeat(120), "nb1", false))
        assertEquals("a".repeat(79) + ".md", VaultMarkdown.fileName("a".repeat(79) + " bcd", "nb1", false))
    }

    @Test
    fun fileNameDisambiguatesWithIdPrefix() {
        assertEquals("Fisika ~abcdef.md", VaultMarkdown.fileName("Fisika", "abcdef123456", true))
    }

    @Test
    fun createWritesFrontmatterAndMarkers() {
        assertEquals(
            lines(
                "---",
                "tulis-id: nb1",
                "tulis-updated: 2026-11-07T00:30:00",
                "tulis-pages: 2",
                "tulis-folder: \"Kuliah/Fisika\"",
                "tulis-hash: h1",
                "---",
                "",
                "%% tulis:begin %%",
                embeds[0],
                embeds[1],
                "%% tulis:end %%",
            ) + "\n",
            created(),
        )
    }

    @Test
    fun createOmitsFolderWhenEmptyAndEscapesQuotes() {
        assertTrue(!created("").contains("tulis-folder"))
        assertTrue(VaultMarkdown.create("nb1", "t", ts, 1, "a\"b\\c", "h", emptyList()).contains("tulis-folder: \"a\\\"b\\\\c\"\n"))
    }

    @Test
    fun ownerIdReadsFrontmatterOnly() {
        assertEquals("nb1", VaultMarkdown.ownerId(created()))
        assertEquals("abc", VaultMarkdown.ownerId("---\ntulis-id: \"abc\"\n---\n"))
        assertNull(VaultMarkdown.ownerId("tulis-id: nb1\n"))
        assertNull(VaultMarkdown.ownerId("---\ntulis-id: nb1\n"))
    }

    @Test
    fun mergeOfCreatedWithSameDataIsIdentical() {
        val c = created()
        assertEquals(c, text(VaultMarkdown.merge(c, "nb1", ts, 2, "Kuliah/Fisika", "h1", embeds)))
    }

    @Test
    fun mergeKeepsUserTextAndOtherFrontmatter() {
        val existing = lines(
            "---",
            "tulis-id: nb1",
            "tulis-updated: 2020-01-01T00:00:00",
            "tulis-pages: 1",
            "tulis-hash: h0",
            "tulis-aliases: [x]",
            "---",
            "",
            "Catatan atas",
            "%% tulis:begin %%",
            "![](_assets/nb1/p001.png)",
            "%% tulis:end %%",
            "Catatan bawah",
        )
        val expected = lines(
            "---",
            "tulis-id: nb1",
            "tulis-updated: 2026-11-07T00:30:00",
            "tulis-pages: 2",
            "tulis-hash: h1",
            "tulis-aliases: [x]",
            "tulis-folder: \"Kuliah\"",
            "---",
            "",
            "Catatan atas",
            "%% tulis:begin %%",
            embeds[0],
            embeds[1],
            "%% tulis:end %%",
            "Catatan bawah",
        )
        assertEquals(expected, text(VaultMarkdown.merge(existing, "nb1", ts, 2, "Kuliah", "h1", embeds)))
    }

    @Test
    fun mergeRemovesFolderWhenEmpty() {
        val existing = created()
        val merged = text(VaultMarkdown.merge(existing, "nb1", ts, 2, "", "h1", embeds))
        assertEquals(existing.replace("tulis-folder: \"Kuliah/Fisika\"\n", ""), merged)
    }

    @Test
    fun mergeReplacesEmbeds() {
        val merged = text(VaultMarkdown.merge(created(), "nb1", ts, 1, "Kuliah/Fisika", "h1", listOf(embeds[1])))
        assertTrue(!merged.contains(embeds[0]))
        assertTrue(merged.contains(embeds[1] + "\n%% tulis:end %%"))
    }

    @Test
    fun mergeReturnsNotOursForForeignOrPlainNotes() {
        assertTrue(VaultMarkdown.merge(created(), "nb2", ts, 2, "", "h1", embeds) is VaultMarkdown.Merge.NotOurs)
        assertTrue(VaultMarkdown.merge("# Catatan biasa\n", "nb1", ts, 2, "", "h1", embeds) is VaultMarkdown.Merge.NotOurs)
    }

    @Test
    fun mergeReportsBrokenMarkers() {
        val noMarkers = lines("---", "tulis-id: nb1", "---", "", "teks")
        val missing = VaultMarkdown.merge(noMarkers, "nb1", ts, 2, "", "h1", embeds) as VaultMarkdown.Merge.Broken
        assertEquals("Penanda tidak ditemukan", missing.reason)

        val doubled = lines("---", "tulis-id: nb1", "---", "%% tulis:begin %%", "%% tulis:end %%", "%% tulis:begin %%", "%% tulis:end %%")
        val twice = VaultMarkdown.merge(doubled, "nb1", ts, 2, "", "h1", embeds) as VaultMarkdown.Merge.Broken
        assertEquals("Penanda rusak", twice.reason)

        val reversed = lines("---", "tulis-id: nb1", "---", "%% tulis:end %%", "%% tulis:begin %%")
        val backwards = VaultMarkdown.merge(reversed, "nb1", ts, 2, "", "h1", embeds) as VaultMarkdown.Merge.Broken
        assertEquals("Penanda rusak", backwards.reason)
    }

    @Test
    fun mergeKeepsCrlfLineEndings() {
        val crlf = created().replace("\n", "\r\n")
        assertEquals(crlf, text(VaultMarkdown.merge(crlf, "nb1", ts, 2, "Kuliah/Fisika", "h1", embeds)))
        val changed = text(VaultMarkdown.merge(crlf, "nb1", ts, 3, "Kuliah/Fisika", "h1", embeds))
        assertTrue(changed.contains("tulis-pages: 3\r\n"))
        assertTrue(changed.endsWith("%% tulis:end %%\r\n"))
    }
}
