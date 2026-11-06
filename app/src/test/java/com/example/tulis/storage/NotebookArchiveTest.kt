package com.example.tulis.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
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
                        out.write(JSONObject(z.getInputStream(e).readBytes().toString(Charsets.UTF_8)).put("format", 2).toString().toByteArray())
                    } else z.getInputStream(e).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        val r = NotebookArchive.unpack(newer, File(tmp.root, "s3"))
        assertTrue(r is NotebookArchive.UnpackResult.Fail && r.reason == "Perbarui Tulis")
    }
}
