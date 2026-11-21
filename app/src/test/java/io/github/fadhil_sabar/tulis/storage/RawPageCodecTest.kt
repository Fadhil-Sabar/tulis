package io.github.fadhil_sabar.tulis.storage

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RawPageCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun samplePointsStroke(
        brushOrdinal: Int,
        colorArgb: Int,
        sizePx: Float,
        matrix: FloatArray,
        toolType: Int,
        strokeUnitLengthCm: Float,
        xs: FloatArray,
        ys: FloatArray,
        times: LongArray,
        pressures: FloatArray,
        tilts: FloatArray,
        orientations: FloatArray
    ): RawStroke {
        return RawStroke(
            brushOrdinal = brushOrdinal,
            colorArgb = colorArgb,
            sizePx = sizePx,
            matrix = matrix,
            data = RawStrokeData.Points(
                toolType = toolType,
                strokeUnitLengthCm = strokeUnitLengthCm,
                x = xs,
                y = ys,
                elapsedTimeMillis = times,
                pressure = pressures,
                tiltRadians = tilts,
                orientationRadians = orientations
            )
        )
    }

    private fun createSyntheticPage(): RawPage {
        val s1 = samplePointsStroke(
            brushOrdinal = 0,
            colorArgb = 0xFF112233.toInt(),
            sizePx = 4.5f,
            matrix = floatArrayOf(1f, 0f, 10f, 0f, 1f, 20f),
            toolType = RawPageCodec.TOOL_TYPE_STYLUS.toInt(),
            strokeUnitLengthCm = 0.05f,
            xs = floatArrayOf(10f, 15f, 20f),
            ys = floatArrayOf(20f, 25f, 30f),
            times = longArrayOf(0L, 16L, 32L),
            pressures = floatArrayOf(0.2f, 0.5f, 0.8f),
            tilts = floatArrayOf(0.1f, 0.2f, 0.3f),
            orientations = floatArrayOf(0.4f, 0.5f, 0.6f)
        )

        val s2 = samplePointsStroke(
            brushOrdinal = 1,
            colorArgb = 0xFF00AA55.toInt(),
            sizePx = 12.0f,
            matrix = floatArrayOf(1.5f, 0.1f, 50f, 0.1f, 1.5f, 100f),
            toolType = RawPageCodec.TOOL_TYPE_TOUCH.toInt(),
            strokeUnitLengthCm = RawPageCodec.NO_STROKE_UNIT_LENGTH,
            xs = floatArrayOf(100f, 110f),
            ys = floatArrayOf(200f, 210f),
            times = longArrayOf(100L, 150L),
            pressures = floatArrayOf(RawPageCodec.NO_PRESSURE, RawPageCodec.NO_PRESSURE),
            tilts = floatArrayOf(RawPageCodec.NO_TILT, RawPageCodec.NO_TILT),
            orientations = floatArrayOf(RawPageCodec.NO_ORIENTATION, RawPageCodec.NO_ORIENTATION)
        )

        val s3 = samplePointsStroke(
            brushOrdinal = 2,
            colorArgb = 0x66FFCC00.toInt(),
            sizePx = 24.0f,
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f),
            toolType = RawPageCodec.TOOL_TYPE_MOUSE.toInt(),
            strokeUnitLengthCm = 0.02f,
            xs = floatArrayOf(5f, 50f),
            ys = floatArrayOf(10f, 10f),
            times = longArrayOf(200L, 250L),
            pressures = floatArrayOf(0.9f, 0.9f),
            tilts = floatArrayOf(0.0f, 0.0f),
            orientations = floatArrayOf(0.0f, 0.0f)
        )

        val s4 = samplePointsStroke(
            brushOrdinal = 0,
            colorArgb = 0xFF000000.toInt(),
            sizePx = 2f,
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f),
            toolType = RawPageCodec.TOOL_TYPE_UNKNOWN.toInt(),
            strokeUnitLengthCm = 0f,
            xs = FloatArray(0),
            ys = FloatArray(0),
            times = LongArray(0),
            pressures = FloatArray(0),
            tilts = FloatArray(0),
            orientations = FloatArray(0)
        )

        val t1 = RawText(10f, 20f, 16f, 0xFF333333.toInt(), 200f, "Catatan Tulis v2 \u270d\ufe0f \ud83d\udcdd")
        val t2 = RawText(50f, 100f, 24f, 0xFFE91E63.toInt(), 300f, "E = mc\u00b2 \u2248 8.98755\u00d710\u00b9\u2076 J")

        return RawPage(strokes = listOf(s1, s2, s3, s4), texts = listOf(t1, t2))
    }

    @Test
    fun v2RoundTrip() {
        val original = createSyntheticPage()
        val baos = ByteArrayOutputStream()
        RawPageCodec.write(baos, original)
        val bytes = baos.toByteArray()

        val parsed = RawPageCodec.read(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)

        val baos2 = ByteArrayOutputStream()
        RawPageCodec.write(baos2, parsed)
        assertArrayEquals(bytes, baos2.toByteArray())
    }

    @Test
    fun emptyPageRoundTrip() {
        val emptyPage = RawPage()
        val baos = ByteArrayOutputStream()
        RawPageCodec.write(baos, emptyPage)
        val bytes = baos.toByteArray()

        assertEquals(13, bytes.size) // MAGIC(4) + VERSION(1) + strokeCount(4) + textCount(4)
        val parsed = RawPageCodec.read(ByteArrayInputStream(bytes))
        assertEquals(emptyPage, parsed)

        val baos2 = ByteArrayOutputStream()
        RawPageCodec.write(baos2, parsed)
        assertArrayEquals(bytes, baos2.toByteArray())
    }

    @Test
    fun v1Read() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(RawPageCodec.MAGIC)
        dos.writeByte(RawPageCodec.VERSION_1.toInt())
        dos.writeInt(1) // 1 stroke

        dos.writeByte(0) // PEN
        dos.writeInt(0xFF445566.toInt())
        dos.writeFloat(3.0f)
        for (m in 0 until 6) dos.writeFloat(m.toFloat())

        val opaqueBlob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        dos.writeInt(opaqueBlob.size)
        dos.write(opaqueBlob)

        dos.writeInt(1) // 1 text
        dos.writeFloat(10f)
        dos.writeFloat(20f)
        dos.writeFloat(15f)
        dos.writeInt(0xFF000000.toInt())
        dos.writeFloat(80f)
        val textBytes = "Catatan Lama".toByteArray(Charsets.UTF_8)
        dos.writeInt(textBytes.size)
        dos.write(textBytes)
        dos.flush()

        val page = RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(1, page.strokes.size)
        val stroke = page.strokes[0]
        assertEquals(0, stroke.brushOrdinal)
        assertEquals(0xFF445566.toInt(), stroke.colorArgb)
        assertEquals(3.0f, stroke.sizePx, 0.001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f), stroke.matrix, 0.001f)
        assertTrue(stroke.data is RawStrokeData.V1Encoded)
        assertArrayEquals(opaqueBlob, (stroke.data as RawStrokeData.V1Encoded).bytes)

        assertEquals(1, page.texts.size)
        assertEquals("Catatan Lama", page.texts[0].text)
    }

    @Test(expected = IOException::class)
    fun invalidMagicThrows() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(0x12345678)
            writeByte(RawPageCodec.VERSION_2.toInt())
            writeInt(0)
            writeInt(0)
        }
        RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun unsupportedVersionThrows() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(RawPageCodec.MAGIC)
            writeByte(99)
            writeInt(0)
            writeInt(0)
        }
        RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun negativeStrokeCountThrows() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(RawPageCodec.MAGIC)
            writeByte(RawPageCodec.VERSION_2.toInt())
            writeInt(-1)
            writeInt(0)
        }
        RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun hugeStrokeCountThrows() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(RawPageCodec.MAGIC)
            writeByte(RawPageCodec.VERSION_2.toInt())
            writeInt(2_000_000)
            writeInt(0)
        }
        RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun negativePointCountThrows() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(RawPageCodec.MAGIC)
            writeByte(RawPageCodec.VERSION_2.toInt())
            writeInt(1) // 1 stroke
            writeByte(0)
            writeInt(0)
            writeFloat(1f)
            for (i in 0 until 6) writeFloat(0f)
            writeByte(0)
            writeFloat(0f)
            writeInt(-1) // negative point count
        }
        RawPageCodec.read(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test
    fun hashCompatibilityWithPrecomputedV1() {
        val dir = tmp.newFolder("pinned_v1")
        val meta = JSONObject()
            .put("id", "nb1").put("title", "Fisika").put("mode", "PAGED").put("pageCount", 3)
            .put("updatedAt", 1L).put("lastPage", 0).put("paper", "DOTS").put("paperSpacing", 28.0)
            .put("orientation", "PORTRAIT").put("folderId", "f2")
        File(dir, "meta.json").writeText(meta.toString())
        for (i in 0 until 3) {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use {
                it.writeInt(RawPageCodec.MAGIC)
                it.writeByte(RawPageCodec.VERSION_1.toInt())
                it.writeInt(i)
            }
            File(dir, "page_$i.bin").writeBytes(bos.toByteArray())
        }

        val expectedHash = "v2:a4f052f92d5c77654989609f7abf6d1ddc4b0e3c8018db1df517ff91a08e3931"
        val computedHash = NotebookArchive.contentHash(dir)
        assertEquals(expectedHash, computedHash)

        for (i in 0 until 3) {
            assertTrue(validPageViaReflect(File(dir, "page_$i.bin")))
        }

        val v2PageFile = File(dir, "page_v2.bin")
        val baos = ByteArrayOutputStream()
        RawPageCodec.write(baos, createSyntheticPage())
        v2PageFile.writeBytes(baos.toByteArray())
        assertTrue(validPageViaReflect(v2PageFile))

        val badPageFile = File(dir, "page_bad.bin")
        badPageFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(!validPageViaReflect(badPageFile))
    }

    private fun validPageViaReflect(file: File): Boolean {
        val method = NotebookArchive.javaClass.getDeclaredMethod("validPage", File::class.java)
        method.isAccessible = true
        return method.invoke(NotebookArchive, file) as Boolean
    }

    @Test
    fun determinism() {
        val page = createSyntheticPage()

        val b1 = ByteArrayOutputStream().apply { RawPageCodec.write(this, page) }.toByteArray()
        val b2 = ByteArrayOutputStream().apply { RawPageCodec.write(this, page) }.toByteArray()
        assertArrayEquals(b1, b2)

        val dir = tmp.newFolder("det_test")
        val meta = JSONObject()
            .put("id", "det1").put("title", "Kimia").put("mode", "INFINITE").put("pageCount", 1)
            .put("updatedAt", 100L).put("lastPage", 0).put("paper", "GRID").put("paperSpacing", 32.0)
            .put("orientation", "PORTRAIT").put("folderId", "")
        File(dir, "meta.json").writeText(meta.toString())
        File(dir, "page_0.bin").writeBytes(b1)

        val hash1 = NotebookArchive.contentHash(dir)
        val hash2 = NotebookArchive.contentHash(dir)
        assertEquals(hash1, hash2)
    }

    private fun buildFixtureDirectory(dir: File, id: String, title: String, mode: String, paper: String, spacing: Double): File {
        dir.mkdirs()
        val meta = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("mode", mode)
            .put("pageCount", 1)
            .put("updatedAt", 1700000000000L)
            .put("lastPage", 0)
            .put("paper", paper)
            .put("paperSpacing", spacing)
            .put("orientation", "PORTRAIT")
            .put("folderId", "")
        File(dir, "meta.json").writeText(meta.toString(2))

        val page = createSyntheticPage()
        val pageFile = File(dir, "page_0.bin")
        FileOutputStream(pageFile).use { RawPageCodec.write(it, page) }

        File(dir, "thumb.webp").writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50))
        return dir
    }

    private fun readZipEntries(zipFile: File): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes()
            }
        }
        return entries
    }

    private fun resolveFixturesDir(): File {
        val direct = File("docs/fixtures")
        if (File("docs").isDirectory) return direct
        val parent = File("../docs/fixtures")
        if (File("../docs").isDirectory) return parent
        return direct
    }

    @Test
    fun fixturesGoldenTest() {
        val fixturesDir = resolveFixturesDir()
        val shouldGenerate = !File(fixturesDir, "infinite_v2.tulis").exists() ||
            !File(fixturesDir, "paged_v2.tulis").exists() ||
            System.getProperty("tulis.generateFixtures") == "true"

        if (shouldGenerate) {
            fixturesDir.mkdirs()
            val infStaging = tmp.newFolder("gen_infinite")
            buildFixtureDirectory(infStaging, "fixture-infinite-1", "Catatan Kanvas Tak Hingga", "INFINITE", "DOTS", 28.0)
            NotebookArchive.pack(infStaging, emptyList(), "0.2.0", File(fixturesDir, "infinite_v2.tulis"))

            val pagedStaging = tmp.newFolder("gen_paged")
            buildFixtureDirectory(pagedStaging, "fixture-paged-1", "Catatan Halaman Fisika", "PAGED", "LINES", 32.0)
            NotebookArchive.pack(pagedStaging, listOf("f1" to "Kuliah"), "0.2.0", File(fixturesDir, "paged_v2.tulis"))
        }

        val infiniteFile = File(fixturesDir, "infinite_v2.tulis")
        val pagedFile = File(fixturesDir, "paged_v2.tulis")
        assertTrue(infiniteFile.isFile)
        assertTrue(pagedFile.isFile)

        // Verify unpacking committed fixtures
        val unpInfinite = tmp.newFolder("unpack_inf")
        val resInf = NotebookArchive.unpack(infiniteFile, unpInfinite)
        assertTrue(resInf is NotebookArchive.UnpackResult.Ok)
        val okInf = resInf as NotebookArchive.UnpackResult.Ok
        assertEquals("fixture-infinite-1", okInf.id)
        assertEquals("Catatan Kanvas Tak Hingga", okInf.title)
        assertEquals(1, okInf.pageCount)

        // Read page_0.bin from unpacked infinite fixture
        val infPage = RawPageCodec.read(FileInputStream(File(unpInfinite, "page_0.bin")))
        assertEquals(4, infPage.strokes.size)
        assertEquals(2, infPage.texts.size)

        val unpPaged = tmp.newFolder("unpack_paged")
        val resPaged = NotebookArchive.unpack(pagedFile, unpPaged)
        assertTrue(resPaged is NotebookArchive.UnpackResult.Ok)
        val okPaged = resPaged as NotebookArchive.UnpackResult.Ok
        assertEquals("fixture-paged-1", okPaged.id)
        assertEquals("Catatan Halaman Fisika", okPaged.title)
        assertEquals(1, okPaged.pageCount)

        val pagedPage = RawPageCodec.read(FileInputStream(File(unpPaged, "page_0.bin")))
        assertEquals(4, pagedPage.strokes.size)
        assertEquals(2, pagedPage.texts.size)

        // Regenerate in temp and verify entries match
        val reInfDir = tmp.newFolder("re_inf")
        buildFixtureDirectory(reInfDir, "fixture-infinite-1", "Catatan Kanvas Tak Hingga", "INFINITE", "DOTS", 28.0)
        val reInfZip = File(tmp.root, "re_inf.tulis")
        NotebookArchive.pack(reInfDir, emptyList(), "0.2.0", reInfZip)

        val committedInfEntries = readZipEntries(infiniteFile)
        val reInfEntries = readZipEntries(reInfZip)
        assertEquals(committedInfEntries.keys, reInfEntries.keys)
        for ((name, bytes) in committedInfEntries) {
            if (name == "manifest.json") {
                val m1 = JSONObject(String(bytes, Charsets.UTF_8))
                val m2 = JSONObject(String(reInfEntries[name]!!, Charsets.UTF_8))
                assertEquals(m1.getString("hash"), m2.getString("hash"))
                assertEquals(m1.getInt("format"), m2.getInt("format"))
                assertEquals(m1.getInt("pageCodec"), m2.getInt("pageCodec"))
            } else {
                assertArrayEquals("Mismatch in entry $name", bytes, reInfEntries[name])
            }
        }
    }
}
