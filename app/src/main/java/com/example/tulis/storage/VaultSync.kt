package com.example.tulis.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VaultSync private constructor(appContext: Context) {
    companion object {
        private const val PREFS = "tulis_prefs"
        private const val KEY_VAULT = "vault_tree_uri"
        private const val STATE_FILE = "vault-state.json"
        private const val DEBOUNCE_MS = 3000L
        private const val ARCHIVE_TIMEOUT_SEC = 120L
        private const val FALLBACK_MSG = "Penyedia berkas tidak mendukung ganti nama (tulis tidak atomik)"
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val META_KEYS = listOf("title", "mode", "paper", "paperSpacing", "orientation", "folderId", "pageCount")
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE
        )

        @Volatile
        private var instance: VaultSync? = null

        fun get(context: Context): VaultSync {
            return instance ?: synchronized(this) {
                instance ?: VaultSync(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class Phase { NOT_CONFIGURED, NEED_REPICK, IDLE, RUNNING }

    data class Status(
        val phase: Phase,
        val vaultName: String?,
        val lastSuccessAt: Long,
        val pending: Int,
        val attention: List<Pair<String, String>>,
        val lastError: String?
    )

    private data class Entry(val uri: Uri, val name: String, val mime: String?, val modified: Long, val size: Long)

    private data class NbState(val hash: String, val fp: String, val mod: Long, val size: Long, val at: Long)

    private val context = appContext
    private val repo = NotebookRepository(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cr = appContext.contentResolver
    private val stateFile = File(appContext.filesDir, STATE_FILE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val attention = LinkedHashMap<String, Pair<String, String>>()
    private val pendingIds = LinkedHashSet<String>()
    private var allPending = false
    private var running = false
    private var remaining = 0
    private var lastError: String? = null
    private var lastSuccessAt = 0L
    private var needRepick = false
    private var vaultName: String? = null
    private var listener: ((Status) -> Unit)? = null
    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }
    private val debounce = Runnable { vaultUri()?.let { startRun(it) } }

    fun status(): Status {
        val uri = vaultUri()
        val current = phase()
        return synchronized(lock) {
            Status(
                current,
                vaultName ?: uri?.lastPathSegment?.substringAfterLast(':'),
                lastSuccessAt,
                pendingIds.size + remaining,
                attention.values.toList(),
                lastError
            )
        }
    }

    fun setListener(l: ((Status) -> Unit)?) {
        synchronized(lock) { listener = l }
    }

    fun setVault(treeUri: Uri) {
        val previous = vaultUri()
        try {
            cr.takePersistableUriPermission(treeUri, GRANT_FLAGS)
        } catch (t: SecurityException) {
        }
        prefs.edit().putString(KEY_VAULT, treeUri.toString()).apply()
        if (previous != treeUri) executor.execute { stateFile.delete() }
        synchronized(lock) {
            needRepick = false
            vaultName = null
            attention.clear()
        }
        publish()
    }

    fun clearVault() {
        vaultUri()?.let {
            try {
                cr.releasePersistableUriPermission(it, GRANT_FLAGS)
            } catch (t: Throwable) {
            }
        }
        prefs.edit().remove(KEY_VAULT).apply()
        executor.execute { stateFile.delete() }
        main.removeCallbacks(debounce)
        synchronized(lock) {
            pendingIds.clear()
            allPending = false
            attention.clear()
            needRepick = false
            vaultName = null
            lastError = null
        }
        publish()
    }

    fun requestSync(ids: List<String>? = null, now: Boolean = false) {
        main.post {
            val tree = vaultUri() ?: return@post
            val current = phase()
            if (current == Phase.NOT_CONFIGURED || current == Phase.NEED_REPICK) return@post
            synchronized(lock) {
                if (ids == null) {
                    allPending = true
                    pendingIds.clear()
                } else {
                    pendingIds.addAll(ids)
                }
            }
            main.removeCallbacks(debounce)
            if (now) startRun(tree) else main.postDelayed(debounce, DEBOUNCE_MS)
            publish()
        }
    }

    private fun vaultUri(): Uri? = prefs.getString(KEY_VAULT, null)?.let { Uri.parse(it) }

    private fun phase(): Phase {
        val uri = vaultUri() ?: return Phase.NOT_CONFIGURED
        synchronized(lock) {
            if (needRepick) return Phase.NEED_REPICK
            if (running) return Phase.RUNNING
        }
        return if (hasAccess(uri)) Phase.IDLE else Phase.NEED_REPICK
    }

    private fun hasAccess(uri: Uri): Boolean {
        return cr.persistedUriPermissions.any {
            it.uri.toString() == uri.toString() && it.isReadPermission && it.isWritePermission
        }
    }

    private fun publish() {
        main.post {
            val l = synchronized(lock) { listener } ?: return@post
            l(status())
        }
    }

    private fun startRun(tree: Uri) {
        val ids = synchronized(lock) {
            if (running || (!allPending && pendingIds.isEmpty())) return
            val snapshot = if (allPending) null else pendingIds.toList()
            if (allPending) attention.clear()
            pendingIds.clear()
            allPending = false
            running = true
            lastError = null
            snapshot
        }
        publish()
        executor.execute { process(ids, tree) }
    }

    private fun finishRun() {
        synchronized(lock) {
            running = false
            remaining = 0
            if (lastError == null && !needRepick) lastSuccessAt = System.currentTimeMillis()
        }
        publish()
        vaultUri()?.let { startRun(it) }
    }

    private fun process(ids: List<String>?, tree: Uri) {
        try {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val rootName = docEntry(root)?.name ?: throw SecurityException("Akar vault tidak bisa dibaca")
            synchronized(lock) { vaultName = rootName }
            val dataDir = ensureDir(tree, ensureDir(tree, root, "Tulis"), "_data")
            val files = listChildren(tree, dataDir).associateByTo(HashMap<String, Entry>()) { it.name }
            val nb = loadState(tree.toString())
            val todo = ids ?: repo.list().map { it.id }
            synchronized(lock) { remaining = todo.size }
            for (id in todo) {
                if (vaultUri() != tree) break
                try {
                    syncOne(id, tree, dataDir, files, nb)
                } catch (t: SecurityException) {
                    throw t
                } catch (t: Throwable) {
                    note(t.message ?: t.javaClass.simpleName)
                }
                synchronized(lock) { remaining-- }
            }
        } catch (t: SecurityException) {
            synchronized(lock) { needRepick = true }
        } catch (t: Throwable) {
            note(t.message ?: t.javaClass.simpleName)
        } finally {
            main.post { finishRun() }
        }
    }

    private fun note(message: String) {
        synchronized(lock) { lastError = message }
    }

    private fun setAttention(id: String, value: Pair<String, String>?) {
        synchronized(lock) {
            if (value == null) attention.remove(id) else attention.put(id, value)
        }
    }

    private fun syncOne(id: String, tree: Uri, dataDir: Uri, files: MutableMap<String, Entry>, nb: MutableMap<String, NbState>) {
        val name = "$id.tulis"
        val prevName = ".$name.prev"
        val dir = File(File(context.filesDir, "notebooks"), id)
        setAttention(id, null)
        if (!File(dir, "meta.json").isFile) return
        if (!files.containsKey(name)) files[prevName]?.let { prev ->
            val restored = DocumentsContract.renameDocument(cr, prev.uri, name) ?: throw IOException("Gagal memulihkan berkas vault")
            files.remove(prevName)
            docEntry(restored)?.let { files[name] = it }
        }
        val s = nb[id]
        val vf = files[name]
        val fp = fingerprint(dir)
        if (s != null && vf != null && s.fp == fp && vf.modified == s.mod && vf.size == s.size) return
        val local = repo.hashNotebook(id) ?: return
        if (vf == null) {
            push(id, tree, dataDir, files, nb, fp)
            return
        }
        val title = repo.loadMeta(id)?.title ?: id
        val unchanged = s != null && vf.modified == s.mod && vf.size == s.size
        val vault = if (unchanged) s?.hash else remoteHash(vf.uri)
        if (vault == null) {
            setAttention(id, title to "Berkas vault rusak")
            return
        }
        when {
            vault == local -> {
                nb[id] = NbState(local, fp, vf.modified, vf.size, s?.at ?: System.currentTimeMillis())
                saveState(tree.toString(), nb)
            }
            vault == s?.hash -> push(id, tree, dataDir, files, nb, fp)
            local == s?.hash -> setAttention(id, title to "Vault lebih baru")
            else -> setAttention(id, title to "Berbeda di vault dan lokal")
        }
    }

    private fun push(id: String, tree: Uri, dataDir: Uri, files: MutableMap<String, Entry>, nb: MutableMap<String, NbState>, fp: String) {
        val name = "$id.tulis"
        val tmpName = ".$name.tmp"
        val prevName = ".$name.prev"
        val zip = File(File(context.cacheDir, "vault-push").apply { mkdirs() }, name)
        try {
            archive(id, zip)
            val hash = NotebookArchive.readManifest(zip)?.hash ?: throw IOException("Arsip lokal tidak terbaca")
            files.remove(tmpName)?.let { deleteOwn(it.uri, tmpName) }
            val tmpUri = createFile(dataDir, tmpName)
            copyTo(tmpUri, zip, "w")
            val tmpEntry = docEntry(tmpUri)
            if (tmpEntry != null && tmpEntry.size >= 0 && tmpEntry.size != zip.length()) {
                throw IOException("Ukuran berkas vault tidak cocok")
            }
            val fallback = commit(dataDir, name, tmpUri, zip, files[name], files)
            files.remove(prevName)
            if (fallback) note(FALLBACK_MSG)
            val after = findChild(tree, dataDir, name) ?: throw IOException("Berkas vault hilang setelah ditulis")
            files[name] = after
            nb[id] = NbState(hash, fp, after.modified, after.size, System.currentTimeMillis())
            saveState(tree.toString(), nb)
        } finally {
            zip.delete()
        }
    }

    private fun commit(dataDir: Uri, name: String, tmpUri: Uri, zip: File, target: Entry?, files: MutableMap<String, Entry>): Boolean {
        val tmpName = ".$name.tmp"
        val prevName = ".$name.prev"
        var backup: Uri? = null
        if (target != null) {
            files[prevName]?.let { deleteOwn(it.uri, prevName) }
            backup = DocumentsContract.renameDocument(cr, target.uri, prevName)
            if (backup == null) {
                copyTo(target.uri, zip, "wt")
                deleteOwn(tmpUri, tmpName)
                return true
            }
        }
        val moved = DocumentsContract.renameDocument(cr, tmpUri, name)
        if (moved == null) {
            copyTo(createFile(dataDir, name), zip, "wt")
            deleteOwn(tmpUri, tmpName)
            backup?.let { deleteOwn(it, prevName) }
            return true
        }
        if (docEntry(moved)?.name != name) throw IOException("Penyedia berkas mengubah nama")
        backup?.let { deleteOwn(it, prevName) }
        return false
    }

    private fun archive(id: String, out: File) {
        val latch = CountDownLatch(1)
        var ok = false
        repo.archiveNotebook(id, out, appVersion) {
            ok = it
            latch.countDown()
        }
        if (!latch.await(ARCHIVE_TIMEOUT_SEC, TimeUnit.SECONDS) || !ok) throw IOException("Gagal mengarsipkan $id")
    }

    private fun copyTo(uri: Uri, src: File, mode: String) {
        val out = cr.openOutputStream(uri, mode) ?: throw IOException("Tidak bisa menulis ke vault")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
    }

    private fun createFile(parent: Uri, name: String): Uri {
        val uri = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name)
            ?: throw IOException("Gagal membuat $name")
        if (docEntry(uri)?.name != name) throw IOException("Penyedia berkas mengubah nama")
        return uri
    }

    private fun deleteOwn(uri: Uri, name: String) {
        require(name.endsWith(".tmp") || name.endsWith(".prev"))
        DocumentsContract.deleteDocument(cr, uri)
    }

    private fun remoteHash(uri: Uri): String? {
        return try {
            cr.openInputStream(uri)?.use { NotebookArchive.readManifest(it)?.hash }
        } catch (t: Throwable) {
            null
        }
    }

    private fun ensureDir(tree: Uri, parent: Uri, name: String): Uri {
        findChild(tree, parent, name)?.let { return it.uri }
        return DocumentsContract.createDocument(cr, parent, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Gagal membuat folder $name")
    }

    private fun findChild(tree: Uri, parent: Uri, name: String): Entry? {
        return listChildren(tree, parent).firstOrNull { it.name == name }
    }

    private fun listChildren(tree: Uri, parent: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val cursor = cr.query(children, PROJECTION, null, null, null) ?: throw SecurityException("Akses vault ditolak")
        return cursor.use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(readRow(c) { DocumentsContract.buildDocumentUriUsingTree(tree, it) })
            out
        }
    }

    private fun docEntry(uri: Uri): Entry? {
        val cursor = cr.query(uri, PROJECTION, null, null, null) ?: return null
        return cursor.use { c -> if (c.moveToFirst()) readRow(c) { uri } else null }
    }

    private fun readRow(c: Cursor, uriOf: (String) -> Uri): Entry {
        val docId = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        return Entry(
            uriOf(docId),
            c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) ?: "",
            c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)),
            longAt(c, Document.COLUMN_LAST_MODIFIED),
            longAt(c, Document.COLUMN_SIZE)
        )
    }

    private fun longAt(c: Cursor, column: String): Long {
        val i = c.getColumnIndexOrThrow(column)
        return if (c.isNull(i)) -1L else c.getLong(i)
    }

    private fun loadState(root: String): MutableMap<String, NbState> {
        val nb = HashMap<String, NbState>()
        try {
            val o = JSONObject(stateFile.readText())
            if (o.optString("root") != root) return nb
            val arr = o.optJSONObject("nb") ?: return nb
            val keys = arr.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val s = arr.getJSONObject(k)
                nb[k] = NbState(s.getString("hash"), s.getString("fp"), s.getLong("mod"), s.getLong("size"), s.getLong("at"))
            }
        } catch (t: Throwable) {
        }
        return nb
    }

    private fun saveState(root: String, nb: Map<String, NbState>) {
        val arr = JSONObject()
        for ((id, s) in nb) {
            arr.put(id, JSONObject().put("hash", s.hash).put("fp", s.fp).put("mod", s.mod).put("size", s.size).put("at", s.at))
        }
        val json = JSONObject().put("root", root).put("nb", arr).toString()
        val tmp = File(context.filesDir, "$STATE_FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            if (!tmp.renameTo(stateFile)) throw IOException("Gagal menyimpan status vault")
        }
    }

    private fun fingerprint(dir: File): String {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val digest = MessageDigest.getInstance("SHA-256")
        for (key in META_KEYS) digest.update("$key=${meta.optString(key, "")}\n".toByteArray(Charsets.UTF_8))
        for (i in 0 until meta.optInt("pageCount", 0)) {
            val page = File(dir, "page_$i.bin")
            val part = if (page.isFile) "${page.length()}:${page.lastModified()}" else "missing"
            digest.update("$i=$part\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
