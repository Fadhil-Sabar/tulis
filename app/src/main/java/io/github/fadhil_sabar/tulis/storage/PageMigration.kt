package io.github.fadhil_sabar.tulis.storage

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object PageMigration {
    val lock = Any()

    fun isV1Page(file: File): Boolean {
        return try {
            DataInputStream(FileInputStream(file)).use {
                it.readInt() == RawPageCodec.MAGIC && it.readByte() == RawPageCodec.VERSION_1
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun migrateDir(dir: File, convert: (File) -> ByteArray?) {
        val pages = dir.listFiles { f -> f.isFile && f.name.startsWith("page_") && f.name.endsWith(".bin") } ?: return
        for (pageFile in pages) {
            synchronized(lock) {
                if (isV1Page(pageFile)) {
                    try {
                        val bytes = convert(pageFile)
                        if (bytes != null) replaceAtomically(pageFile, bytes)
                    } catch (t: Throwable) {
                    }
                }
            }
        }
    }

    fun replaceAtomically(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile(target.name + ".", ".tmp", target.parentFile)
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Gagal mengganti ${target.name}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
