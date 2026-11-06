package com.example.tulis.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.example.tulis.ink.InkBridge
import com.example.tulis.ink.StrokeItem
import com.example.tulis.model.Folder
import com.example.tulis.model.NoteMode
import com.example.tulis.model.NotebookMeta
import com.example.tulis.model.PageOrientation
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
            val orientationStr = obj.optString("orientation", "")
            val orientation = try {
                if (orientationStr.isNotEmpty()) PageOrientation.valueOf(orientationStr) else PageOrientation.PORTRAIT
            } catch (e: Exception) {
                PageOrientation.PORTRAIT
            }

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
                paperSpacing = paperSpacing,
                orientation = orientation,
                folderId = obj.optString("folderId", "").ifEmpty { null }
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
        obj.put("orientation", meta.orientation.name)
        meta.folderId?.let { obj.put("folderId", it) }

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
        val known = listFolders().mapTo(HashSet()) { it.id }
        for (m in result) {
            if (m.folderId != null && m.folderId !in known) m.folderId = null
        }
        result.sortByDescending { it.updatedAt }
        return result
    }

    private val foldersFile: File
        get() = File(notebooksDir, "folders.json")

    @Synchronized
    fun listFolders(): List<Folder> {
        val f = foldersFile
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).getJSONArray("folders")
            val out = ArrayList<Folder>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Folder(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        parentId = o.optString("parentId", "").ifEmpty { null },
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            val ids = out.mapTo(HashSet()) { it.id }
            out.forEach { if (it.parentId != null && it.parentId !in ids) it.parentId = null }
            out.forEach { if (hasCycle(it, out)) it.parentId = null }
            out
        } catch (t: Throwable) {
            f.renameTo(File(f.parentFile, "folders.json.corrupt"))
            emptyList()
        }
    }

    private fun hasCycle(start: Folder, all: List<Folder>): Boolean {
        val byId = all.associateBy { it.id }
        var cur = start.parentId
        var steps = 0
        while (cur != null) {
            if (cur == start.id || steps++ > all.size) return true
            cur = byId[cur]?.parentId
        }
        return false
    }

    private fun writeFolders(folders: List<Folder>) {
        val arr = JSONArray()
        for (fo in folders) {
            val o = JSONObject()
            o.put("id", fo.id)
            o.put("name", fo.name)
            fo.parentId?.let { o.put("parentId", it) }
            o.put("createdAt", fo.createdAt)
            arr.put(o)
        }
        val json = JSONObject().put("v", 1).put("folders", arr).toString()
        atomicWriteFile(foldersFile) { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    private fun nameTaken(folders: List<Folder>, parentId: String?, name: String, exceptId: String?): Boolean {
        return folders.any { it.parentId == parentId && it.id != exceptId && it.name.equals(name, ignoreCase = true) }
    }

    fun descendantFolderIds(id: String): Set<String> {
        val all = listFolders()
        val out = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (fo in all) {
                if (fo.parentId == cur && out.add(fo.id)) queue.add(fo.id)
            }
        }
        return out
    }

    @Synchronized
    fun createFolder(name: String, parentId: String?): Folder? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val all = listFolders().toMutableList()
        if (parentId != null && all.none { it.id == parentId }) return null
        if (nameTaken(all, parentId, clean, null)) return null
        val folder = Folder(UUID.randomUUID().toString(), clean, parentId, System.currentTimeMillis())
        all.add(folder)
        return try {
            writeFolders(all)
            folder
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    fun renameFolder(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val all = listFolders()
        val target = all.firstOrNull { it.id == id } ?: return false
        if (nameTaken(all, target.parentId, clean, id)) return false
        target.name = clean
        return try {
            writeFolders(all)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Synchronized
    fun moveFolder(id: String, newParentId: String?): Boolean {
        val all = listFolders()
        val target = all.firstOrNull { it.id == id } ?: return false
        if (newParentId != null) {
            if (newParentId == id || newParentId in descendantFolderIds(id)) return false
            if (all.none { it.id == newParentId }) return false
        }
        if (nameTaken(all, newParentId, target.name, id)) return false
        target.parentId = newParentId
        return try {
            writeFolders(all)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Synchronized
    fun deleteFolder(id: String) {
        flush()
        val all = listFolders().toMutableList()
        val target = all.firstOrNull { it.id == id } ?: return
        val parent = target.parentId
        all.remove(target)
        for (fo in all) {
            if (fo.parentId == id) fo.parentId = parent
        }
        try {
            writeFolders(all)
            val dir = notebooksDir.listFiles() ?: return
            for (d in dir) {
                if (!d.isDirectory || d.name == ".trash") continue
                val meta = parseMeta(File(d, "meta.json")) ?: continue
                if (meta.folderId == id) {
                    meta.folderId = parent
                    writeMetaSync(meta)
                }
            }
        } catch (t: Throwable) {
        }
    }

    @Synchronized
    fun moveNotebook(id: String, folderId: String?): Boolean {
        flush()
        if (folderId != null && listFolders().none { it.id == folderId }) return false
        val meta = loadMeta(id) ?: return false
        meta.folderId = folderId
        return try {
            writeMetaSync(meta)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun defaultTitle(): String {
        return "Catatan " + SimpleDateFormat("d MMM HH.mm", Locale("id", "ID")).format(Date())
    }

    fun create(title: String, mode: NoteMode, orientation: PageOrientation = PageOrientation.PORTRAIT, folderId: String? = null): NotebookMeta {
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
            paperSpacing = 28f,
            orientation = orientation,
            folderId = folderId
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

    fun loadPageSync(id: String, index: Int): PageContent {
        val file = pageFile(id, index)
        if (!file.exists()) return PageContent()
        return FileInputStream(file).use { PageCodec.read(it) }
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

    fun deleteAllThumbsAsync(id: String, pageCount: Int) {
        executor.execute {
            for (i in 0 until pageCount) {
                try {
                    pageThumbFile(id, i).delete()
                } catch (t: Throwable) {
                }
            }
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

    fun folderPath(folderId: String?): List<Pair<String, String>> {
        val byId = listFolders().associateBy { it.id }
        val out = ArrayList<Pair<String, String>>()
        var cur = folderId
        while (cur != null && out.size <= byId.size) {
            val fo = byId[cur] ?: break
            out.add(0, fo.id to fo.name)
            cur = fo.parentId
        }
        return out
    }

    fun archiveNotebook(id: String, out: File, appVersion: String, cb: (Boolean) -> Unit) {
        executor.execute {
            val ok = try {
                val meta = parseMeta(metaFile(id))
                if (meta == null) {
                    false
                } else {
                    NotebookArchive.pack(File(notebooksDir, id), folderPath(meta.folderId), appVersion, out)
                    true
                }
            } catch (t: Throwable) {
                false
            }
            mainHandler.post { cb(ok) }
        }
    }

    fun hashNotebook(id: String): String? {
        val dir = File(notebooksDir, id)
        if (!dir.isDirectory || !metaFile(id).isFile) return null
        return try {
            NotebookArchive.contentHash(dir)
        } catch (t: Throwable) {
            null
        }
    }

    fun installFromStaging(stagingDir: File, result: NotebookArchive.UnpackResult.Ok, replaceExisting: Boolean, cb: (Boolean) -> Unit) {
        executor.execute {
            val ok = try {
                installSync(stagingDir, result, replaceExisting)
            } catch (t: Throwable) {
                false
            }
            mainHandler.post { cb(ok) }
        }
    }

    private fun installSync(stagingDir: File, result: NotebookArchive.UnpackResult.Ok, replaceExisting: Boolean): Boolean {
        val target = File(notebooksDir, result.id)
        if (target.exists()) {
            if (!replaceExisting) return false
            moveToTrash(result.id)
        }
        if (!stagingDir.renameTo(target)) return false
        try {
            restoreFolders(result.folderPath, result.id)
        } catch (t: Throwable) {
        }
        return true
    }

    private fun moveToTrash(id: String) {
        val src = File(notebooksDir, id)
        val dst = File(trashDir, id)
        if (dst.exists()) dst.deleteRecursively()
        if (!src.renameTo(dst)) throw IOException("Gagal memindah ${src.name} ke sampah")
        File(dst, "deleted_at").writeText(System.currentTimeMillis().toString())
    }

    @Synchronized
    private fun restoreFolders(path: List<Pair<String, String>>, notebookId: String) {
        val folders = listFolders().toMutableList()
        var parentId: String? = null
        var changed = false
        for ((fid, fname) in path) {
            if (folders.none { it.id == fid }) {
                val name = if (nameTaken(folders, parentId, fname, null)) "$fname (pulih)" else fname
                folders.add(Folder(fid, name, parentId, System.currentTimeMillis()))
                changed = true
            }
            parentId = fid
        }
        if (changed) writeFolders(folders)
        val meta = parseMeta(metaFile(notebookId)) ?: return
        meta.folderId = parentId
        writeMetaSync(meta)
    }

    fun flush() {
        val future = executor.submit { }
        try {
            future.get(10, TimeUnit.SECONDS)
        } catch (t: Throwable) {
        }
    }
}
