package io.github.fadhil_sabar.tulis.storage

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ImageRefs {
    private const val BUF_SIZE = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    fun isValidName(name: String): Boolean = RawPageCodec.IMAGE_NAME.matches(name)

    fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return toHex(digest)
    }

    fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(BUF_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return toHex(digest.digest())
    }

    fun referencedNames(dir: File, pageCount: Int): Set<String>? {
        val names = LinkedHashSet<String>()
        for (i in 0 until pageCount) {
            val file = File(dir, "page_$i.bin")
            if (!file.exists()) continue
            val page = try {
                BufferedInputStream(FileInputStream(file)).use { RawPageCodec.read(it) }
            } catch (_: Exception) {
                return null
            }
            for (img in page.images) {
                names.add(img.name)
            }
        }
        return names
    }

    fun imageFiles(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && isValidName(it.name) }.sortedBy { it.name }
    }

    fun gcOrphans(dir: File, referenced: Set<String>, nowMs: Long, graceMs: Long = 3_600_000L): Int {
        val cutoff = nowMs - graceMs
        var deleted = 0
        for (file in imageFiles(dir)) {
            if (file.name !in referenced && file.lastModified() < cutoff) {
                if (file.delete()) {
                    deleted++
                }
            }
        }
        return deleted
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
