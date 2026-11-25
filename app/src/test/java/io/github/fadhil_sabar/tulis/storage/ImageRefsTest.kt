package io.github.fadhil_sabar.tulis.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class ImageRefsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hashName(fill: Char, ext: String = "webp"): String {
        return "img_" + fill.toString().repeat(40) + "." + ext
    }

    private fun writePage(dir: File, index: Int, page: RawPage) {
        FileOutputStream(File(dir, "page_$index.bin")).use { RawPageCodec.write(it, page) }
    }

    @Test
    fun isValidNameAcceptsOnlyCanonicalPattern() {
        assertTrue(ImageRefs.isValidName(hashName('0', "webp")))
        assertTrue(ImageRefs.isValidName(hashName('a', "png")))
        assertTrue(ImageRefs.isValidName(hashName('f', "jpg")))
        assertFalse(ImageRefs.isValidName(hashName('A', "webp")))
        assertFalse(ImageRefs.isValidName("img_" + "0".repeat(39) + ".webp"))
        assertFalse(ImageRefs.isValidName("img_" + "0".repeat(41) + ".webp"))
        assertFalse(ImageRefs.isValidName(hashName('0', "jpeg")))
        assertFalse(ImageRefs.isValidName("../" + hashName('0', "webp")))
        assertFalse(ImageRefs.isValidName("thumb.webp"))
    }

    @Test
    fun sha1HexMatchesKnownVectorsAndStreamingFile() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ImageRefs.sha1Hex(ByteArray(0)))
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            ImageRefs.sha1Hex("abc".toByteArray(Charsets.UTF_8))
        )

        val large = ByteArray(150 * 1024) { i -> (i * 31 + 7).toByte() }
        val file = File(tmp.root, "large.bin")
        file.writeBytes(large)
        val expected = ImageRefs.sha1Hex(large)
        assertEquals(40, expected.length)
        assertEquals(expected, ImageRefs.sha1Hex(file))
    }

    @Test
    fun referencedNamesReadsV3PagesAndSkipsMissing() {
        val dir = tmp.newFolder("nb_refs")
        val name1 = hashName('1', "webp")
        val name2 = hashName('2', "png")
        writePage(
            dir,
            0,
            RawPage(
                images = listOf(
                    RawImage(name1, 10f, 20f, 100f, 80f, 0f),
                    RawImage(name2, 30f, 40f, 50f, 60f, 45f)
                )
            )
        )
        writePage(dir, 2, RawPage())
        writePage(
            dir,
            3,
            RawPage(
                images = listOf(RawImage(name1, 15f, 25f, 100f, 80f, 10f))
            )
        )

        val refs = ImageRefs.referencedNames(dir, 4)
        assertEquals(setOf(name1, name2), refs)
        assertEquals(emptySet<String>(), ImageRefs.referencedNames(dir, 0))
    }

    @Test
    fun referencedNamesReturnsNullOnCorruptPage() {
        val dir = tmp.newFolder("nb_corrupt")
        val name1 = hashName('3', "jpg")
        writePage(dir, 0, RawPage(images = listOf(RawImage(name1, 1f, 2f, 10f, 10f, 0f))))
        File(dir, "page_1.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertNull(ImageRefs.referencedNames(dir, 2))
    }

    @Test
    fun imageFilesFiltersOnlyValidImageFiles() {
        val dir = tmp.newFolder("nb_files")
        val valid1 = File(dir, hashName('b', "png")).apply { writeBytes(byteArrayOf(1)) }
        val valid2 = File(dir, hashName('a', "webp")).apply { writeBytes(byteArrayOf(2)) }
        File(dir, "meta.json").writeText("{}")
        File(dir, "page_0.bin").writeBytes(byteArrayOf(3))
        File(dir, "thumb.webp").writeBytes(byteArrayOf(4))
        File(dir, "img_short.png").writeBytes(byteArrayOf(5))
        File(dir, hashName('c', "jpg")).mkdir()

        val found = ImageRefs.imageFiles(dir)
        assertEquals(listOf(valid2, valid1), found)
    }

    @Test
    fun gcOrphansDeletesOnlyOldUnreferencedImageFiles() {
        val dir = tmp.newFolder("nb_gc")
        val now = 10_000_000L
        val grace = 3_600_000L

        val refOld = File(dir, hashName('1', "webp")).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - grace - 50_000L)
        }
        val orphanInGrace = File(dir, hashName('2', "png")).apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now - grace + 1_000L)
        }
        val orphanBoundary = File(dir, hashName('3', "jpg")).apply {
            writeBytes(byteArrayOf(3))
            setLastModified(now - grace)
        }
        val orphanOld1 = File(dir, hashName('4', "webp")).apply {
            writeBytes(byteArrayOf(4))
            setLastModified(now - grace - 1_000L)
        }
        val orphanOld2 = File(dir, hashName('5', "png")).apply {
            writeBytes(byteArrayOf(5))
            setLastModified(now - grace - 200_000L)
        }
        val nonImgMeta = File(dir, "meta.json").apply {
            writeText("{}")
            setLastModified(now - grace - 500_000L)
        }
        val nonImgThumb = File(dir, "thumb.webp").apply {
            writeBytes(byteArrayOf(6))
            setLastModified(now - grace - 500_000L)
        }
        val nonImgBadName = File(dir, "img_abc.webp").apply {
            writeBytes(byteArrayOf(7))
            setLastModified(now - grace - 500_000L)
        }

        val deleted = ImageRefs.gcOrphans(dir, setOf(refOld.name), now, grace)
        assertEquals(2, deleted)
        assertTrue(refOld.exists())
        assertTrue(orphanInGrace.exists())
        assertTrue(orphanBoundary.exists())
        assertFalse(orphanOld1.exists())
        assertFalse(orphanOld2.exists())
        assertTrue(nonImgMeta.exists())
        assertTrue(nonImgThumb.exists())
        assertTrue(nonImgBadName.exists())
    }
}
