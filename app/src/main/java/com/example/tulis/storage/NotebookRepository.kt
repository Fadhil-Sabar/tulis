package com.example.tulis.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.example.tulis.ink.InkBridge
import com.example.tulis.ink.StrokeItem
import com.example.tulis.model.NoteMode
import com.example.tulis.model.NotebookMeta
import com.example.tulis.model.PaperStyle
import com.example.tulis.model.PageContent
import com.example.tulis.model.TextItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TrashedNotebook(val meta: NotebookMeta, val deletedAt: Long)

class NotebookRepository(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notebooksDir: File
        get() = File(context.filesDir, "notebooks").apply { mkdirs() }

    private val trashDir: File
        get() = File(notebooksDir, ".trash").apply { mkdirs() }

    private fun notebookDir(id: String): File {
        return File(notebooksDir, id).apply { mkdirs() }
    }

    private fun metaFile(id: String): File {
        return File(File(notebooksDir, id), "meta.json")
    }

    private fun pageFile(id: String, index: Int): File {
        return File(File(notebooksDir, id), "page_$index.bin")
    }

    private fun atomicWriteFile(targetFile: File, writer: (FileOutputStream) -> Unit) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, targetFile.name + ".tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                writer(fos)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    throw IOException("Failed to rename $tmpFile to $targetFile")
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    private fun parseMeta(file: File): NotebookMeta? {
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            val obj = JSONObject(jsonStr)
            val id = obj.getString("id")
            val title = obj.getString("title")
            val modeStr = obj.getString("mode")
            val mode = NoteMode.valueOf(modeStr)
            val pageCount = obj.getInt("pageCount")
            val updatedAt = obj.getLong("updatedAt")
            val lastPage = obj.optInt("lastPage", 0)

            var vpScale = 1f
            var vpTx = 0f
            var vpTy = 0f
            if (obj.has("vp")) {
                val vpArr = obj.getJSONArray("vp")
                if (vpArr.length() >= 3) {
                    vpScale = vpArr.getDouble(0).toFloat()
                    vpTx = vpArr.getDouble(1).toFloat()
                    vpTy = vpArr.getDouble(2).toFloat()
                }
            }

            val defaultStyle = if (mode == NoteMode.INFINITE) PaperStyle.DOTS else PaperStyle.PLAIN
            val paperStr = obj.optString("paper", "")
            val paperStyle = try {
                if (paperStr.isNotEmpty()) PaperStyle.valueOf(paperStr) else defaultStyle
            } catch (e: Exception) {
                defaultStyle
            }
            val rawSpacing = obj.optDouble("paperSpacing", 28.0).toFloat()
            val paperSpacing = rawSpacing.coerceIn(12f, 120f)

            NotebookMeta(
                id = id,
                title = title,
                mode = mode,
                pageCount = pageCount,
                updatedAt = updatedAt,
                vpScale = vpScale,
                vpTx = vpTx,
                vpTy = vpTy,
                lastPage = lastPage,
                paperStyle = paperStyle,
                paperSpacing = paperSpacing
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun serializeMeta(meta: NotebookMeta): String {
        val obj = JSONObject()
        obj.put("id", meta.id)
        obj.put("title", meta.title)
        obj.put("mode", meta.mode.name)
        obj.put("pageCount", meta.pageCount)
        obj.put("updatedAt", meta.updatedAt)
        obj.put("lastPage", meta.lastPage)
        obj.put("paper", meta.paperStyle.name)
        obj.put("paperSpacing", meta.paperSpacing.toDouble())

        val vpArr = JSONArray()
        vpArr.put(meta.vpScale.toDouble())
        vpArr.put(meta.vpTx.toDouble())
        vpArr.put(meta.vpTy.toDouble())
        obj.put("vp", vpArr)

        return obj.toString()
    }

    private fun writeMetaSync(meta: NotebookMeta) {
        val target = metaFile(meta.id)
        val json = serializeMeta(meta)
        atomicWriteFile(target) { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    fun list(): List<NotebookMeta> {
        val dir = notebooksDir
        val files = dir.listFiles() ?: return emptyList()
        val result = mutableListOf<NotebookMeta>()
        for (f in files) {
            if (f.isDirectory && f.name != ".trash") {
                val mf = File(f, "meta.json")
                val meta = parseMeta(mf)
                if (meta != null) {
                    result.add(meta)
                }
            }
        }
        result.sortByDescending { it.updatedAt }
        return result
    }

    fun defaultTitle(): String {
        return "Catatan " + SimpleDateFormat("d MMM HH.mm", Locale("id", "ID")).format(Date())
    }

    fun create(title: String, mode: NoteMode): NotebookMeta {
        val finalTitle = if (title.isBlank()) defaultTitle() else title
        val id = UUID.randomUUID().toString()
        val defaultPaperStyle = if (mode == NoteMode.INFINITE) PaperStyle.DOTS else PaperStyle.PLAIN
        val meta = NotebookMeta(
            id = id,
            title = finalTitle,
            mode = mode,
            pageCount = 1,
            updatedAt = System.currentTimeMillis(),
            vpScale = 1f,
            vpTx = 0f,
            vpTy = 0f,
            lastPage = 0,
            paperStyle = defaultPaperStyle,
            paperSpacing = 28f
        )
        notebookDir(id)
        writeMetaSync(meta)
        return meta
    }

    fun loadMeta(id: String): NotebookMeta? {
        return parseMeta(metaFile(id))
    }

    fun saveMetaAsync(meta: NotebookMeta) {
        executor.execute {
            try {
                writeMetaSync(meta)
            } catch (t: Throwable) {
            }
        }
    }

    fun afterPending(cb: () -> Unit) {
        executor.execute {
            mainHandler.post(cb)
        }
    }

    fun loadPage(id: String, index: Int, cb: (PageContent) -> Unit) {
        executor.execute {
            val file = pageFile(id, index)
            val content: PageContent = if (!file.exists()) {
                PageContent()
            } else {
                try {
                    FileInputStream(file).use { fis ->
                        PageCodec.read(fis)
                    }
                } catch (t: Throwable) {
                    val corruptFile = File(file.parentFile, file.name + ".corrupt")
                    file.renameTo(corruptFile)
                    PageContent()
                }
            }
            mainHandler.post {
                cb(content)
            }
        }
    }

    fun savePageAsync(id: String, index: Int, page: PageContent) {
        val strokeSnapshots = ArrayList<StrokeItem>(page.strokes.size)
        for (item in page.strokes) {
            val cached = item.encodedInputs
            val copyEncoded = cached?.copyOf()
            strokeSnapshots.add(
                StrokeItem(
                    stroke = item.stroke,
                    brushKind = item.brushKind,
                    colorArgb = item.colorArgb,
                    sizePx = item.sizePx,
                    strokeToWorld = item.strokeToWorld,
                    encodedInputs = copyEncoded
                )
            )
        }
        val textSnapshots = ArrayList<TextItem>(page.texts.size)
        for (item in page.texts) {
            textSnapshots.add(
                TextItem(
                    x = item.x,
                    y = item.y,
                    sizeWorld = item.sizeWorld,
                    colorArgb = item.colorArgb,
                    widthWorld = item.widthWorld,
                    text = item.text
                )
            )
        }

        executor.execute {
            try {
                val preparedPage = PageContent()
                for (item in strokeSnapshots) {
                    if (item.encodedInputs == null) {
                        item.encodedInputs = InkBridge.encodeInputs(item)
                    }
                    preparedPage.strokes.add(item)
                }
                preparedPage.texts.addAll(textSnapshots)

                val target = pageFile(id, index)
                atomicWriteFile(target) { fos ->
                    PageCodec.write(fos, preparedPage)
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun deletePage(id: String, index: Int, pageCount: Int) {
        executor.execute {
            try {
                val target = pageFile(id, index)
                if (target.exists()) {
                    target.delete()
                }
                val thumbTarget = pageThumbFile(id, index)
                if (thumbTarget.exists()) {
                    thumbTarget.delete()
                }
                for (k in index + 1 until pageCount) {
                    val oldFile = pageFile(id, k)
                    val newFile = pageFile(id, k - 1)
                    if (oldFile.exists()) {
                        oldFile.renameTo(newFile)
                    }

                    val oldThumb = pageThumbFile(id, k)
                    val newThumb = pageThumbFile(id, k - 1)
                    if (oldThumb.exists()) {
                        oldThumb.renameTo(newThumb)
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun insertPage(id: String, index: Int, pageCount: Int, page: PageContent) {
        val strokeSnapshots = ArrayList<StrokeItem>(page.strokes.size)
        for (item in page.strokes) {
            val cached = item.encodedInputs
            val copyEncoded = cached?.copyOf()
            strokeSnapshots.add(
                StrokeItem(
                    stroke = item.stroke,
                    brushKind = item.brushKind,
                    colorArgb = item.colorArgb,
                    sizePx = item.sizePx,
                    strokeToWorld = item.strokeToWorld,
                    encodedInputs = copyEncoded
                )
            )
        }
        val textSnapshots = ArrayList<TextItem>(page.texts.size)
        for (item in page.texts) {
            textSnapshots.add(
                TextItem(
                    x = item.x,
                    y = item.y,
                    sizeWorld = item.sizeWorld,
                    colorArgb = item.colorArgb,
                    widthWorld = item.widthWorld,
                    text = item.text
                )
            )
        }

        executor.execute {
            try {
                for (k in pageCount - 2 downTo index) {
                    val src = pageFile(id, k)
                    val dst = pageFile(id, k + 1)
                    if (src.exists()) {
                        src.renameTo(dst)
                    }

                    val srcThumb = pageThumbFile(id, k)
                    val dstThumb = pageThumbFile(id, k + 1)
                    if (srcThumb.exists()) {
                        srcThumb.renameTo(dstThumb)
                    }
                }

                val preparedPage = PageContent()
                for (item in strokeSnapshots) {
                    if (item.encodedInputs == null) {
                        item.encodedInputs = InkBridge.encodeInputs(item)
                    }
                    preparedPage.strokes.add(item)
                }
                preparedPage.texts.addAll(textSnapshots)

                val target = pageFile(id, index)
                atomicWriteFile(target) { fos ->
                    PageCodec.write(fos, preparedPage)
                }

                val meta = parseMeta(metaFile(id))
                if (meta != null) {
                    meta.pageCount = pageCount
                    writeMetaSync(meta)
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun softDelete(id: String) {
        flush()
        try {
            val src = File(notebooksDir, id)
            val dst = File(trashDir, id)
            if (src.exists()) {
                trashDir.mkdirs()
                if (dst.exists()) {
                    dst.deleteRecursively()
                }
                if (src.renameTo(dst)) {
                    val deletedAtFile = File(dst, "deleted_at")
                    deletedAtFile.writeText(System.currentTimeMillis().toString())
                }
            }
        } catch (t: Throwable) {
        }
    }

    fun listTrash(): List<TrashedNotebook> {
        val trash = trashDir
        val files = trash.listFiles() ?: return emptyList()
        val result = mutableListOf<TrashedNotebook>()
        for (f in files) {
            if (f.isDirectory) {
                val mf = File(f, "meta.json")
                val meta = parseMeta(mf)
                if (meta != null) {
                    val deletedAtFile = File(f, "deleted_at")
                    val deletedAt = if (deletedAtFile.exists()) {
                        deletedAtFile.readText().trim().toLongOrNull() ?: f.lastModified()
                    } else {
                        f.lastModified()
                    }
                    result.add(TrashedNotebook(meta, deletedAt))
                }
            }
        }
        result.sortByDescending { it.deletedAt }
        return result
    }

    fun restore(id: String) {
        flush()
        try {
            val src = File(trashDir, id)
            val dst = File(notebooksDir, id)
            if (src.exists()) {
                if (dst.exists()) {
                    dst.deleteRecursively()
                }
                if (src.renameTo(dst)) {
                    val deletedAtFile = File(dst, "deleted_at")
                    if (deletedAtFile.exists()) {
                        deletedAtFile.delete()
                    }
                }
            }
        } catch (t: Throwable) {
        }
    }

    fun deleteForever(id: String) {
        flush()
        executor.execute {
            try {
                val dir = File(trashDir, id)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun emptyTrash() {
        executor.execute {
            try {
                val trash = trashDir
                val files = trash.listFiles()
                if (files != null) {
                    for (f in files) {
                        f.deleteRecursively()
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun purgeExpired(days: Int = 30) {
        executor.execute {
            try {
                val trash = trashDir
                if (!trash.exists()) return@execute
                val files = trash.listFiles() ?: return@execute
                val cutoff = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
                for (f in files) {
                    if (f.isDirectory) {
                        val deletedAtFile = File(f, "deleted_at")
                        val deletedAt = if (deletedAtFile.exists()) {
                            deletedAtFile.readText().trim().toLongOrNull() ?: f.lastModified()
                        } else {
                            f.lastModified()
                        }
                        if (deletedAt < cutoff) {
                            f.deleteRecursively()
                        }
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun purgeTrash() {
        purgeExpired(30)
    }

    fun trashThumbFile(id: String): File {
        return File(File(trashDir, id), "thumb.webp")
    }

    fun trashCount(): Int {
        val trash = trashDir
        if (!trash.exists()) return 0
        val files = trash.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.isDirectory) {
                count++
            }
        }
        return count
    }

    fun rename(id: String, title: String) {
        val meta = loadMeta(id) ?: return
        meta.title = title
        saveMetaAsync(meta)
    }

    fun delete(id: String) {
        executor.execute {
            try {
                val dir = File(notebooksDir, id)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun pageThumbFile(id: String, index: Int): File {
        return if (index == 0) {
            thumbFile(id)
        } else {
            File(notebookDir(id), "thumb_$index.webp")
        }
    }

    fun savePageThumbAsync(id: String, index: Int, bitmap: Bitmap) {
        executor.execute {
            try {
                val target = pageThumbFile(id, index)
                atomicWriteFile(target) { fos ->
                    val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(format, 80, fos)
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun thumbFile(id: String): File {
        return File(File(notebooksDir, id), "thumb.webp")
    }

    fun saveThumbAsync(id: String, bitmap: Bitmap) {
        executor.execute {
            try {
                val target = thumbFile(id)
                atomicWriteFile(target) { fos ->
                    val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(format, 80, fos)
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun flush() {
        val future = executor.submit { }
        try {
            future.get(10, TimeUnit.SECONDS)
        } catch (t: Throwable) {
        }
    }
}
