package com.example.tulis.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object NotebookArchive {
    const val FORMAT = 1
    private const val PAGE_CODEC = 1
    private const val PAGE_MAGIC = 0x494E4B50
    private const val PAGE_VERSION = 1
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 5000
    private const val BUF_SIZE = 64 * 1024
    private val entryName = Regex("^(meta\\.json|manifest\\.json|thumb\\.webp|page_\\d+\\.bin)$")
    private val metaKeys = listOf("title", "mode", "paper", "paperSpacing", "orientation", "folderId", "pageCount")

    sealed class UnpackResult {
        class Ok(
            val id: String,
            val title: String,
            val hash: String,
            val pageCount: Int,
            val folderPath: List<Pair<String, String>>
        ) : UnpackResult()

        class Fail(val reason: String) : UnpackResult()
    }

    fun contentHash(dir: File): String {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val digest = MessageDigest.getInstance("SHA-256")
        for (key in metaKeys) {
            digest.update("$key=${meta.optString(key, "")}\n".toByteArray(Charsets.UTF_8))
        }
        val pageCount = meta.getInt("pageCount")
        val buf = ByteArray(BUF_SIZE)
        for (i in 0 until pageCount) {
            val page = File(dir, "page_$i.bin")
            if (page.isFile) {
                FileInputStream(page).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
            } else {
                digest.update("missing=$i\n".toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun pack(dir: File, folderPath: List<Pair<String, String>>, appVersion: String, out: File) {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val pageCount = meta.getInt("pageCount")
        val pathArr = JSONArray()
        for ((fid, fname) in folderPath) {
            pathArr.put(JSONObject().put("id", fid).put("name", fname))
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("pageCodec", PAGE_CODEC)
            .put("app", appVersion)
            .put("id", meta.getString("id"))
            .put("title", meta.getString("title"))
            .put("hash", contentHash(dir))
            .put("pageCount", pageCount)
            .put("folderPath", pathArr)
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                putBytes(zos, "manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
                putFile(zos, "meta.json", File(dir, "meta.json"))
                for (i in 0 until pageCount) {
                    val page = File(dir, "page_$i.bin")
                    if (page.isFile) putFile(zos, page.name, page)
                }
                val thumb = File(dir, "thumb.webp")
                if (thumb.isFile) putFile(zos, thumb.name, thumb)
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("Gagal menulis ${out.name}")
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun unpack(zip: File, stagingDir: File): UnpackResult {
        val result = try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            extract(zip, stagingDir)
        } catch (t: Throwable) {
            UnpackResult.Fail(t.message ?: t.javaClass.simpleName)
        }
        if (result is UnpackResult.Fail) stagingDir.deleteRecursively()
        return result
    }

    fun readManifest(zip: File): UnpackResult.Ok? {
        return try {
            FileInputStream(zip).use { readManifest(it) }
        } catch (t: Throwable) {
            null
        }
    }

    fun readManifest(input: java.io.InputStream): UnpackResult.Ok? {
        return try {
            val zis = ZipInputStream(input)
            generateSequence { zis.nextEntry }.firstOrNull { it.name == "manifest.json" } ?: return null
            val manifest = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
            UnpackResult.Ok(
                manifest.getString("id"),
                manifest.getString("title"),
                manifest.getString("hash"),
                manifest.getInt("pageCount"),
                parseFolderPath(manifest)
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun putBytes(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putFile(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zos, BUF_SIZE) }
        zos.closeEntry()
    }

    private fun extract(zip: File, stagingDir: File): UnpackResult {
        var total = 0L
        var count = 0
        val buf = ByteArray(BUF_SIZE)
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                count++
                if (count > MAX_ENTRIES) return UnpackResult.Fail("Terlalu banyak entri")
                if (!entryName.matches(entry.name)) return UnpackResult.Fail("Entri tidak valid: ${entry.name}")
                FileOutputStream(File(stagingDir, entry.name)).use { fos ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return UnpackResult.Fail("Arsip terlalu besar")
                        fos.write(buf, 0, n)
                    }
                }
            }
        }
        return verify(stagingDir)
    }

    private fun verify(stagingDir: File): UnpackResult {
        val manifestFile = File(stagingDir, "manifest.json")
        val metaFile = File(stagingDir, "meta.json")
        if (!manifestFile.isFile || !metaFile.isFile) return UnpackResult.Fail("Arsip tidak lengkap")
        val manifest = JSONObject(manifestFile.readText())
        if (manifest.getInt("format") > FORMAT) return UnpackResult.Fail("Perbarui Tulis")
        val id = manifest.getString("id")
        val pageCount = manifest.getInt("pageCount")
        val meta = JSONObject(metaFile.readText())
        if (meta.getString("id") != id || meta.getInt("pageCount") != pageCount) {
            return UnpackResult.Fail("Meta tidak cocok")
        }
        for (i in 0 until pageCount) {
            if (!validPage(File(stagingDir, "page_$i.bin"))) return UnpackResult.Fail("Halaman $i rusak")
        }
        val hash = contentHash(stagingDir)
        if (hash != manifest.getString("hash")) return UnpackResult.Fail("Hash tidak cocok")
        return UnpackResult.Ok(id, manifest.getString("title"), hash, pageCount, parseFolderPath(manifest))
    }

    private fun validPage(file: File): Boolean {
        return try {
            DataInputStream(FileInputStream(file)).use {
                it.readInt() == PAGE_MAGIC && it.readByte().toInt() == PAGE_VERSION
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun parseFolderPath(manifest: JSONObject): List<Pair<String, String>> {
        val arr = manifest.optJSONArray("folderPath") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.getString("name")
        }
    }
}
