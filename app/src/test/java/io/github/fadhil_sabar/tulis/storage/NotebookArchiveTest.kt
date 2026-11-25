package io.github.fadhil_sabar.tulis.storage

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NotebookArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeNotebook(id: String, pages: Int): File {
        val dir = tmp.newFolder(id)
        val meta = JSONObject()
            .put("id", id).put("title", "Fisika").put("mode", "PAGED").put("pageCount", pages)
            .put("updatedAt", 1L).put("lastPage", 0).put("paper", "DOTS").put("paperSpacing", 28.0)
            .put("orientation", "PORTRAIT").put("folderId", "f2")
        File(dir, "meta.json").writeText(meta.toString())
        for (i in 0 until pages) {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { it.writeInt(0x494E4B50); it.writeByte(1); it.writeInt(i) }
            File(dir, "page_$i.bin").writeBytes(bos.toByteArray())
        }
        File(dir, "thumb.webp").writeBytes(byteArrayOf(1, 2, 3))
        return dir
    }

    private val path = listOf("f1" to "Kuliah", "f2" to "Fisika")

    @Test
    fun roundTripKeepsHashAndFiles() {
        val dir = makeNotebook("nb1", 3)
        val zip = File(tmp.root, "nb1.tulis")
        NotebookArchive.pack(dir, path, "1.0", zip)
        val staging = File(tmp.root, "staging")
        val r = NotebookArchive.unpack(zip, staging)
        assertTrue(r is NotebookArchive.UnpackResult.Ok)
        r as NotebookArchive.UnpackResult.Ok
        assertEquals("nb1", r.id)
        assertEquals(3, r.pageCount)
        assertEquals(path, r.folderPath)
        assertEquals(NotebookArchive.contentHash(dir), r.hash)
        for (i in 0 until 3) assertEquals(File(dir, "page_$i.bin").readBytes().toList(), File(staging, "page_$i.bin").readBytes().toList())
        assertTrue(File(staging, "thumb.webp").isFile)
        assertEquals(r.hash, NotebookArchive.readManifest(zip)?.hash)
    }

    @Test
    fun roundTripWithMissingEmptyPage() {
        val dir = makeNotebook("nb5", 3)
        File(dir, "page_2.bin").delete()
        val zip = File(tmp.root, "nb5.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)
        val r = NotebookArchive.unpack(zip, File(tmp.root, "s5"))
        assertTrue(r is NotebookArchive.UnpackResult.Ok)
        assertTrue(!File(tmp.root, "s5/page_2.bin").exists())
        assertEquals(NotebookArchive.contentHash(dir), (r as NotebookArchive.UnpackResult.Ok).hash)
    }

    @Test
    fun legacyMetaHashesSameAsNormalized() {
        val legacy = makeNotebook("nb6", 1)
        val legacyMeta = JSONObject(File(legacy, "meta.json").readText())
        legacyMeta.remove("paper"); legacyMeta.remove("paperSpacing"); legacyMeta.remove("orientation")
        File(legacy, "meta.json").writeText(legacyMeta.toString())
        val full = tmp.newFolder("nb7")
        File(legacy, "page_0.bin").copyTo(File(full, "page_0.bin"))
        val fullMeta = JSONObject(legacyMeta.toString()).put("paper", "PLAIN").put("paperSpacing", 28.0).put("orientation", "PORTRAIT")
        File(full, "meta.json").writeText(fullMeta.toString())
        assertEquals(NotebookArchive.contentHash(legacy), NotebookArchive.contentHash(full))
        assertTrue(NotebookArchive.contentHash(full).startsWith("v2:"))
    }

    @Test
    fun hashIgnoresViewportAndChangesWithPages() {
        val dir = makeNotebook("nb2", 2)
        val h1 = NotebookArchive.contentHash(dir)
        val meta = JSONObject(File(dir, "meta.json").readText()).put("lastPage", 1).put("updatedAt", 99L)
        File(dir, "meta.json").writeText(meta.toString())
        assertEquals(h1, NotebookArchive.contentHash(dir))
        File(dir, "page_1.bin").appendBytes(byteArrayOf(9))
        assertTrue(h1 != NotebookArchive.contentHash(dir))
    }

    @Test
    fun rejectsZipSlipAndTamperedPage() {
        val evil = File(tmp.root, "evil.tulis")
        ZipOutputStream(evil.outputStream()).use {
            it.putNextEntry(ZipEntry("../x.bin")); it.write(1); it.closeEntry()
        }
        assertTrue(NotebookArchive.unpack(evil, File(tmp.root, "s1")) is NotebookArchive.UnpackResult.Fail)

        val dir = makeNotebook("nb3", 1)
        val zip = File(tmp.root, "nb3.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)
        File(dir, "page_0.bin").appendBytes(byteArrayOf(7))
        val tampered = File(tmp.root, "tampered.tulis")
        ZipOutputStream(tampered.outputStream()).use { out ->
            java.util.zip.ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == "page_0.bin") out.write(File(dir, "page_0.bin").readBytes()) else z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val r = NotebookArchive.unpack(tampered, File(tmp.root, "s2"))
        assertTrue(r is NotebookArchive.UnpackResult.Fail)
        assertTrue(!File(tmp.root, "s2").exists())
    }

    @Test
    fun rejectsNewerFormat() {
        val dir = makeNotebook("nb4", 1)
        val zip = File(tmp.root, "nb4.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)
        val newer = File(tmp.root, "newer.tulis")
        ZipOutputStream(newer.outputStream()).use { out ->
            java.util.zip.ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == "manifest.json") {
                        out.write(JSONObject(z.getInputStream(e).readBytes().toString(Charsets.UTF_8)).put("format", NotebookArchive.FORMAT + 1).toString().toByteArray())
                    } else z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val r = NotebookArchive.unpack(newer, File(tmp.root, "s3"))
        assertTrue(r is NotebookArchive.UnpackResult.Fail && r.reason == "Perbarui Tulis")
    }

    @Test
    fun acceptsFormat1Archive() {
        val dir = makeNotebook("nbFormat1", 1)
        val zip = File(tmp.root, "nbFormat1.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)
        val format1Zip = File(tmp.root, "format1.tulis")
        ZipOutputStream(format1Zip.outputStream()).use { out ->
            java.util.zip.ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == "manifest.json") {
                        out.write(JSONObject(z.getInputStream(e).readBytes().toString(Charsets.UTF_8)).put("format", 1).toString().toByteArray())
                    } else z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val r = NotebookArchive.unpack(format1Zip, File(tmp.root, "sFormat1"))
        assertTrue(r is NotebookArchive.UnpackResult.Ok)
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun makeMeta(dir: File, id: String, pageCount: Int) {
        val meta = JSONObject()
            .put("id", id).put("title", "Fisika").put("mode", "PAGED").put("pageCount", pageCount)
            .put("updatedAt", 1L).put("lastPage", 0).put("paper", "DOTS").put("paperSpacing", 28.0)
            .put("orientation", "PORTRAIT").put("folderId", "f2")
        File(dir, "meta.json").writeText(meta.toString())
    }

    private fun readManifestJson(zip: File): JSONObject {
        ZipFile(zip).use { z ->
            val entry = z.getEntry("manifest.json")
            return JSONObject(z.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
        }
    }

    private fun zipEntryNames(zip: File): List<String> {
        ZipFile(zip).use { z ->
            return z.entries().toList().map { it.name }
        }
    }

    @Test
    fun roundTripV3WithImagesAndSkipsOrphan() {
        val dir = tmp.newFolder("nbV3")
        makeMeta(dir, "nbV3", 2)
        val bytesA = byteArrayOf(10, 20, 30, 40)
        val bytesB = byteArrayOf(50, 60, 70)
        val orphanBytes = byteArrayOf(1, 1, 1)
        val nameA = "img_${sha1Hex(bytesA)}.webp"
        val nameB = "img_${sha1Hex(bytesB)}.jpg"
        val orphanName = "img_${sha1Hex(orphanBytes)}.png"
        File(dir, nameA).writeBytes(bytesA)
        File(dir, nameB).writeBytes(bytesB)
        File(dir, orphanName).writeBytes(orphanBytes)

        val imgA = RawImage(nameA, 100f, 200f, 300f, 150f, 0f)
        val imgB = RawImage(nameB, 400f, 500f, 120f, 80f, 45f)
        FileOutputStream(File(dir, "page_0.bin")).use { RawPageCodec.write(it, RawPage(images = listOf(imgB, imgA))) }
        FileOutputStream(File(dir, "page_1.bin")).use { RawPageCodec.write(it, RawPage()) }

        val zip = File(tmp.root, "nbV3.tulis")
        NotebookArchive.pack(dir, path, "1.0", zip)

        val manifest = readManifestJson(zip)
        assertEquals(3, manifest.getInt("format"))
        assertEquals(3, manifest.getInt("pageCodec"))

        val entries = zipEntryNames(zip)
        assertTrue(nameA in entries)
        assertTrue(nameB in entries)
        assertFalse(orphanName in entries)
        val imgEntries = entries.filter { it.startsWith("img_") }
        assertEquals(listOf(nameA, nameB).sorted(), imgEntries)

        val staging = File(tmp.root, "stagingV3")
        val res = NotebookArchive.unpack(zip, staging)
        assertTrue(res is NotebookArchive.UnpackResult.Ok)
        assertArrayEquals(bytesA, File(staging, nameA).readBytes())
        assertArrayEquals(bytesB, File(staging, nameB).readBytes())
        val unpackedPage0 = File(staging, "page_0.bin").inputStream().use { RawPageCodec.read(it) }
        assertEquals(listOf(imgB, imgA), unpackedPage0.images)

        val zipWithOrphan = File(tmp.root, "nbV3Orphan.tulis")
        ZipOutputStream(zipWithOrphan.outputStream()).use { out ->
            ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry(orphanName))
            out.write(orphanBytes)
            out.closeEntry()
        }
        val stagingOrphan = File(tmp.root, "stagingV3Orphan")
        val resOrphan = NotebookArchive.unpack(zipWithOrphan, stagingOrphan)
        assertTrue(resOrphan is NotebookArchive.UnpackResult.Ok)
        assertTrue(File(stagingOrphan, nameA).isFile)
        assertFalse(File(stagingOrphan, orphanName).exists())
    }

    @Test
    fun manifestUsesFormat2WithoutImages() {
        val dir = tmp.newFolder("nbV2Only")
        makeMeta(dir, "nbV2Only", 1)
        FileOutputStream(File(dir, "page_0.bin")).use { RawPageCodec.write(it, RawPage()) }
        val zip = File(tmp.root, "nbV2Only.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)
        val manifest = readManifestJson(zip)
        assertEquals(2, manifest.getInt("format"))
        assertEquals(2, manifest.getInt("pageCodec"))

        val legacyDir = makeNotebook("nbLegacyNoImg", 2)
        val legacyZip = File(tmp.root, "nbLegacyNoImg.tulis")
        NotebookArchive.pack(legacyDir, emptyList(), "1.0", legacyZip)
        val legacyManifest = readManifestJson(legacyZip)
        assertEquals(2, legacyManifest.getInt("format"))
        assertEquals(2, legacyManifest.getInt("pageCodec"))
    }

    @Test
    fun rejectsTamperedOrMissingImage() {
        val dir = tmp.newFolder("nbImgCheck")
        makeMeta(dir, "nbImgCheck", 1)
        val imgBytes = byteArrayOf(11, 22, 33, 44, 55)
        val imgName = "img_${sha1Hex(imgBytes)}.webp"
        File(dir, imgName).writeBytes(imgBytes)
        val img = RawImage(imgName, 50f, 60f, 100f, 100f, 0f)
        FileOutputStream(File(dir, "page_0.bin")).use { RawPageCodec.write(it, RawPage(images = listOf(img))) }
        val zip = File(tmp.root, "nbImgCheck.tulis")
        NotebookArchive.pack(dir, emptyList(), "1.0", zip)

        val tamperedZip = File(tmp.root, "nbImgTampered.tulis")
        ZipOutputStream(tamperedZip.outputStream()).use { out ->
            ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == imgName) out.write(byteArrayOf(9, 9, 9)) else z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val sTampered = File(tmp.root, "sImgTampered")
        val rTampered = NotebookArchive.unpack(tamperedZip, sTampered)
        assertTrue(rTampered is NotebookArchive.UnpackResult.Fail && rTampered.reason == "Gambar rusak")
        assertFalse(sTampered.exists())

        val missingZip = File(tmp.root, "nbImgMissing.tulis")
        ZipOutputStream(missingZip.outputStream()).use { out ->
            ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    if (e.name == imgName) continue
                    out.putNextEntry(ZipEntry(e.name))
                    z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val sMissing = File(tmp.root, "sImgMissing")
        val rMissing = NotebookArchive.unpack(missingZip, sMissing)
        assertTrue(rMissing is NotebookArchive.UnpackResult.Fail && rMissing.reason == "Gambar rusak")
        assertFalse(sMissing.exists())
    }

    @Test
    fun contentHashUnchangedForNotebookWithoutImages() {
        val dir = makeNotebook("nb1", 3)
        val expected = "v2:a4f052f92d5c77654989609f7abf6d1ddc4b0e3c8018db1df517ff91a08e3931"
        assertEquals(expected, NotebookArchive.contentHash(dir))
        val orphanBytes = byteArrayOf(7, 8, 9)
        File(dir, "img_${sha1Hex(orphanBytes)}.webp").writeBytes(orphanBytes)
        assertEquals(expected, NotebookArchive.contentHash(dir))
    }
}
