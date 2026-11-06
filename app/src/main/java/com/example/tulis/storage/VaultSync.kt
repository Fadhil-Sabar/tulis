package com.example.tulis.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.tulis.export.ExportFormat
import com.example.tulis.export.ExportJob
import com.example.tulis.export.ExportOptions
import com.example.tulis.export.Exporter
import com.example.tulis.model.NoteMode
import com.example.tulis.model.NotebookMeta
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VaultSync private constructor(appContext: Context) {
    companion object {
        private const val PREFS = "tulis_prefs"
        private const val KEY_VAULT = "vault_tree_uri"
        private const val STATE_FILE = "vault-state.json"
        private const val DEBOUNCE_MS = 3000L
        private const val ARCHIVE_TIMEOUT_SEC = 120L
        private const val NOTE_LIMIT = 4 * 1024 * 1024
        private const val FALLBACK_MSG = "Penyedia berkas tidak mendukung ganti nama (tulis tidak atomik)"
        private const val VAULT_NOT_READY = "Vault belum siap"
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

    enum class RestoreState { NEW, SAME, DIFFERENT }

    data class RestoreItem(
        val id: String,
        val title: String,
        val pageCount: Int,
        val folderPath: String,
        val state: RestoreState,
        val modified: Long
    )

    enum class RestoreMode { REPLACE, COPY }

    enum class AttentionKind { VAULT_NEWER, CONFLICT, VAULT_CORRUPT, OTHER }

    data class AttentionItem(val id: String, val title: String, val reason: String, val kind: AttentionKind)

    enum class ConflictChoice { USE_LOCAL, USE_VAULT, KEEP_BOTH }

    data class Status(
        val phase: Phase,
        val vaultName: String?,
        val lastSuccessAt: Long,
        val pending: Int,
        val attention: List<Pair<String, String>>,
        val lastError: String?
    )

    private data class Entry(val uri: Uri, val name: String, val mime: String?, val modified: Long, val size: Long)

    private data class NbState(val hash: String, val fp: String, val mod: Long, val size: Long, val at: Long, val md: String? = null, val pg: String? = null)

    private val context = appContext
    private val repo = NotebookRepository(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cr = appContext.contentResolver
    private val stateFile = File(appContext.filesDir, STATE_FILE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val attention = LinkedHashMap<String, AttentionItem>()
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
                attention.values.map { it.title to it.reason },
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

    fun listRestorable(cb: (List<RestoreItem>?, String?) -> Unit) {
        executor.execute {
            val result = try {
                listRestorableSync()
            } catch (t: Throwable) {
                null to (t.message ?: t.javaClass.simpleName)
            }
            main.post { cb(result.first, result.second) }
        }
    }

    fun restore(id: String, mode: RestoreMode, cb: (Boolean, String?) -> Unit) {
        executor.execute {
            val result = try {
                restoreSync(id, mode)
            } catch (t: Throwable) {
                false to (t.message ?: t.javaClass.simpleName)
            }
            main.post { cb(result.first, result.second) }
        }
    }

    fun attentionItems(): List<AttentionItem> = synchronized(lock) { attention.values.toList() }

    fun resolveConflict(id: String, choice: ConflictChoice, cb: (Boolean, String?) -> Unit) {
        executor.execute {
            val result = try {
                resolveConflictSync(id, choice)
            } catch (t: Throwable) {
                false to (t.message ?: t.javaClass.simpleName)
            }
            main.post { cb(result.first, result.second) }
        }
    }

    private fun resolveConflictSync(id: String, choice: ConflictChoice): Pair<Boolean, String?> {
        return when (choice) {
            ConflictChoice.USE_VAULT -> restoreSync(id, RestoreMode.REPLACE).also { if (it.first) setAttention(id, null) }
            ConflictChoice.KEEP_BOTH -> restoreSync(id, RestoreMode.COPY).let { if (it.first) useLocal(id) else it }
            ConflictChoice.USE_LOCAL -> useLocal(id)
        }
    }

    private fun useLocal(id: String): Pair<Boolean, String?> {
        val tree = readyVault() ?: return (false to VAULT_NOT_READY)
        val name = "$id.tulis"
        restoreDir(tree)?.let { findChild(tree, it, name) }?.let { entry ->
            val backup = "$id.${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.tulis"
            val renamed = DocumentsContract.renameDocument(cr, entry.uri, backup)
            if (renamed == null || docEntry(renamed)?.name != backup) return (false to "Gagal menyimpan cadangan vault")
        }
        val nb = loadState(tree.toString())
        nb.remove(id)
        saveState(tree.toString(), nb)
        setAttention(id, null)
        requestSync(listOf(id), now = true)
        return (true to null)
    }

    private fun readyVault(): Uri? {
        val uri = vaultUri() ?: return null
        val current = phase()
        return if (current == Phase.IDLE || current == Phase.RUNNING) uri else null
    }

    private fun restoreDir(tree: Uri): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val tulis = findChild(tree, root, "Tulis") ?: return null
        return findChild(tree, tulis.uri, "_data")?.uri
    }

    private fun validId(id: String): Boolean = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun readManifestOf(uri: Uri): NotebookArchive.UnpackResult.Ok? {
        return try {
            cr.openInputStream(uri)?.use { NotebookArchive.readManifest(it) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun listRestorableSync(): Pair<List<RestoreItem>?, String?> {
        val tree = readyVault() ?: return (null to VAULT_NOT_READY)
        val dir = restoreDir(tree) ?: return (emptyList<RestoreItem>() to null)
        val notebooks = File(context.filesDir, "notebooks")
        val items = listChildren(tree, dir).mapNotNull { entry ->
            if (entry.name.startsWith(".") || !entry.name.endsWith(".tulis")) return@mapNotNull null
            val m = readManifestOf(entry.uri) ?: return@mapNotNull null
            if (!validId(m.id) || entry.name != "${m.id}.tulis") return@mapNotNull null
            val state = when {
                !File(notebooks, "${m.id}/meta.json").isFile -> RestoreState.NEW
                repo.hashNotebook(m.id) == m.hash -> RestoreState.SAME
                else -> RestoreState.DIFFERENT
            }
            RestoreItem(m.id, m.title, m.pageCount, m.folderPath.joinToString(" / ") { it.second }, state, entry.modified)
        }
        val sorted = items.sortedWith(
            compareBy<RestoreItem> { when (it.state) { RestoreState.NEW -> 0; RestoreState.DIFFERENT -> 1; RestoreState.SAME -> 2 } }.thenComparator { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
        )
        return (sorted to null)
    }

    private fun restoreSync(id: String, mode: RestoreMode): Pair<Boolean, String?> {
        if (!validId(id)) return (false to "Cadangan tidak valid")
        val tree = readyVault() ?: return (false to VAULT_NOT_READY)
        val zip = File(File(context.cacheDir, "vault-restore").apply { mkdirs() }, "$id.tulis")
        val staging = File(context.filesDir, "restore-staging/$id")
        try {
            val entry = restoreDir(tree)?.let { findChild(tree, it, "$id.tulis") } ?: return (false to "Cadangan tidak ditemukan")
            val input = cr.openInputStream(entry.uri) ?: return (false to "Tidak bisa membaca vault")
            input.use { src -> zip.outputStream().use { src.copyTo(it) } }
            val unpacked = when (val r = NotebookArchive.unpack(zip, staging)) {
                is NotebookArchive.UnpackResult.Fail -> return (false to r.reason)
                is NotebookArchive.UnpackResult.Ok -> r
            }
            if (unpacked.id != id) return (false to "Arsip tidak cocok")
            File(staging, "manifest.json").delete()
            val target = if (mode == RestoreMode.COPY) copyAsNew(staging, unpacked) else unpacked
            if (!install(staging, target, mode == RestoreMode.REPLACE)) return (false to "Gagal memasang notebook")
            requestSync(null, now = true)
            return (true to null)
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun copyAsNew(staging: File, ok: NotebookArchive.UnpackResult.Ok): NotebookArchive.UnpackResult.Ok {
        val newId = UUID.randomUUID().toString()
        val newTitle = "${ok.title} (dari vault)"
        val metaFile = File(staging, "meta.json")
        metaFile.writeText(JSONObject(metaFile.readText()).put("id", newId).put("title", newTitle).toString())
        return NotebookArchive.UnpackResult.Ok(newId, newTitle, ok.hash, ok.pageCount, ok.folderPath)
    }

    private fun install(staging: File, ok: NotebookArchive.UnpackResult.Ok, replace: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var done = false
        repo.installFromStaging(staging, ok, replace) {
            done = it
            latch.countDown()
        }
        return latch.await(ARCHIVE_TIMEOUT_SEC, TimeUnit.SECONDS) && done
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
            val tulis = ensureDir(tree, root, "Tulis")
            val dataDir = ensureDir(tree, tulis, "_data")
            val files = listChildren(tree, dataDir).associateByTo(HashMap<String, Entry>()) { it.name }
            val nb = loadState(tree.toString())
            val todo = ids ?: repo.list().map { it.id }
            synchronized(lock) { remaining = todo.size }
            for (id in todo) {
                if (vaultUri() != tree) break
                try {
                    syncOne(id, tree, tulis, dataDir, files, nb)
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

    private fun setAttention(id: String, item: AttentionItem?) {
        synchronized(lock) {
            if (item == null) attention.remove(id) else attention[id] = item
        }
    }

    private fun syncOne(id: String, tree: Uri, tulis: Uri, dataDir: Uri, files: MutableMap<String, Entry>, nb: MutableMap<String, NbState>) {
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
        if (s != null && vf != null && s.fp == fp && vf.modified == s.mod && vf.size == s.size) {
            if (s.md == null) publishQuietly(id, tree, tulis, nb, s.hash)
            return
        }
        val local = repo.hashNotebook(id) ?: return
        if (vf == null) {
            push(id, tree, tulis, dataDir, files, nb, fp)
            return
        }
        val title = repo.loadMeta(id)?.title ?: id
        val unchanged = s != null && vf.modified == s.mod && vf.size == s.size
        val vault = if (unchanged) s?.hash else remoteHash(vf.uri)
        if (vault == null) {
            setAttention(id, AttentionItem(id, title, "Berkas vault rusak", AttentionKind.VAULT_CORRUPT))
            return
        }
        when {
            vault == local -> {
                nb[id] = NbState(local, fp, vf.modified, vf.size, s?.at ?: System.currentTimeMillis(), s?.md, s?.pg)
                saveState(tree.toString(), nb)
                if (s?.md == null) publishQuietly(id, tree, tulis, nb, local)
            }
            vault == s?.hash -> push(id, tree, tulis, dataDir, files, nb, fp)
            local == s?.hash -> setAttention(id, AttentionItem(id, title, "Vault lebih baru", AttentionKind.VAULT_NEWER))
            else -> setAttention(id, AttentionItem(id, title, "Berbeda di vault dan lokal", AttentionKind.CONFLICT))
        }
    }

    private fun push(id: String, tree: Uri, tulis: Uri, dataDir: Uri, files: MutableMap<String, Entry>, nb: MutableMap<String, NbState>, fp: String) {
        val name = "$id.tulis"
        val zip = File(File(context.cacheDir, "vault-push").apply { mkdirs() }, name)
        try {
            archive(id, zip)
            val hash = NotebookArchive.readManifest(zip)?.hash ?: throw IOException("Arsip lokal tidak terbaca")
            if (writeAtomic(dataDir, name, zip, files[name], files)) note(FALLBACK_MSG)
            val after = findChild(tree, dataDir, name) ?: throw IOException("Berkas vault hilang setelah ditulis")
            files[name] = after
            val old = nb[id]
            nb[id] = NbState(hash, fp, after.modified, after.size, System.currentTimeMillis(), old?.md, old?.pg)
            saveState(tree.toString(), nb)
            publishQuietly(id, tree, tulis, nb, hash)
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
        if (docEntry(moved)?.name != name) {
            try {
                DocumentsContract.deleteDocument(cr, moved)
            } catch (t: Throwable) {
            }
            throw IOException("Penyedia berkas mengubah nama")
        }
        backup?.let { deleteOwn(it, prevName) }
        return false
    }

    private fun writeAtomic(dir: Uri, name: String, src: File, existing: Entry?, files: MutableMap<String, Entry>): Boolean {
        val tmpName = ".$name.tmp"
        val prevName = ".$name.prev"
        files.remove(tmpName)?.let { deleteOwn(it.uri, tmpName) }
        val tmpUri = createFile(dir, tmpName)
        copyTo(tmpUri, src, "w")
        val tmpEntry = docEntry(tmpUri)
        if (tmpEntry != null && tmpEntry.size >= 0 && tmpEntry.size != src.length()) {
            throw IOException("Ukuran berkas vault tidak cocok")
        }
        val fallback = commit(dir, name, tmpUri, src, existing, files)
        files.remove(prevName)
        return fallback
    }

    private fun publishQuietly(id: String, tree: Uri, tulis: Uri, nb: MutableMap<String, NbState>, hash: String) {
        try {
            publishView(id, tree, tulis, nb, hash)
        } catch (t: SecurityException) {
            throw t
        } catch (t: Throwable) {
            note(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun publishView(id: String, tree: Uri, tulis: Uri, nb: MutableMap<String, NbState>, hash: String) {
        val s = nb[id] ?: return
        val meta = repo.loadMeta(id) ?: return
        val dir = File(File(context.filesDir, "notebooks"), id)
        val pageCount = if (meta.mode == NoteMode.INFINITE) 1 else meta.pageCount
        val assetDir = ensureDir(tree, ensureDir(tree, tulis, "_assets"), id)
        val assetFiles = listChildren(tree, assetDir).associateByTo(HashMap<String, Entry>()) { it.name }
        val prev = s.pg?.split(",")
        val pg = ArrayList<String>(pageCount)
        val embeds = ArrayList<String>(pageCount)
        for (i in 0 until pageCount) {
            val page = File(dir, "page_$i.bin")
            val part = if (page.isFile) "${page.length()}:${page.lastModified()}" else "missing"
            val name = pngName(i)
            val fresh = part == prev?.getOrNull(i) && assetFiles.containsKey(name)
            val present = fresh || renderPage(id, meta, i, tree, assetDir, assetFiles)
            pg.add(if (present) part else "")
            if (present) embeds.add("![](_assets/$id/$name)")
        }
        val folder = repo.folderPath(meta.folderId).joinToString("/") { it.second }
        val listing = listChildren(tree, tulis).associateByTo(HashMap<String, Entry>()) { it.name }
        val base = VaultMarkdown.fileName(meta.title, id, false)
        val alt = VaultMarkdown.fileName(meta.title, id, true)
        val ours = s.md?.let { n -> listing[n]?.takeIf { ownedBy(id, it) } }
        fun lookup(n: String): Entry? = listing[n] ?: listing.values.firstOrNull { it.name.equals(n, ignoreCase = true) }
        val baseEntry = lookup(base)
        val wanted = if (baseEntry == null || ownedBy(id, baseEntry)) base else alt
        val wantedEntry = lookup(wanted)
        val taken = wantedEntry != null && !ownedBy(id, wantedEntry)
        val pick: Pair<String, Entry?>? = when {
            ours != null -> {
                val renamed = if (!taken && wanted != ours.name) DocumentsContract.renameDocument(cr, ours.uri, wanted)?.let { docEntry(it) } else null
                renamed?.let { it.name to it } ?: (ours.name to ours)
            }
            taken -> {
                setAttention(id, AttentionItem(id, meta.title, "Nama catatan bentrok", AttentionKind.OTHER))
                null
            }
            else -> wanted to wantedEntry
        }
        var mdName: String? = null
        if (pick != null) {
            val (name, current) = pick
            val text: String? = if (current == null) {
                VaultMarkdown.create(id, meta.title, meta.updatedAt, pageCount, folder, hash, embeds)
            } else {
                val existing = readNote(current.uri)
                when (val m = if (existing == null) VaultMarkdown.Merge.NotOurs else VaultMarkdown.merge(existing, id, meta.updatedAt, pageCount, folder, hash, embeds)) {
                    is VaultMarkdown.Merge.Ok -> m.text
                    is VaultMarkdown.Merge.Broken -> {
                        setAttention(id, AttentionItem(id, meta.title, "Catatan Obsidian rusak: ${m.reason}", AttentionKind.OTHER))
                        null
                    }
                    VaultMarkdown.Merge.NotOurs -> {
                        setAttention(id, AttentionItem(id, meta.title, "Nama catatan bentrok", AttentionKind.OTHER))
                        null
                    }
                }
            }
            if (text != null) {
                writeNote(id, tulis, name, text, current, listing)
                mdName = name
            }
        }
        nb[id] = s.copy(md = mdName, pg = pg.joinToString(","))
        saveState(tree.toString(), nb)
    }

    private fun renderPage(id: String, meta: NotebookMeta, index: Int, tree: Uri, assetDir: Uri, files: MutableMap<String, Entry>): Boolean {
        val name = pngName(index)
        val cache = File(File(context.cacheDir, "vault-png").apply { mkdirs() }, "$id-$index.png")
        try {
            val job = ExportJob(
                id, meta.mode, meta.orientation, meta.paperStyle, meta.paperSpacing,
                ExportOptions(ExportFormat.PNG, false, true, 1f), listOf(index), emptyMap(),
                if (meta.mode == NoteMode.INFINITE) repo.loadPageSync(id, 0) else null
            )
            try {
                cache.outputStream().use { Exporter.run(job, it, AtomicBoolean(false), { p -> repo.loadPageSync(id, p) }, { _, _ -> }) }
            } catch (e: IllegalStateException) {
                return false
            }
            if (writeAtomic(assetDir, name, cache, files[name], files)) note(FALLBACK_MSG)
            files[name] = findChild(tree, assetDir, name) ?: throw IOException("Berkas PNG hilang setelah ditulis")
            return true
        } finally {
            cache.delete()
        }
    }

    private fun writeNote(id: String, tulis: Uri, name: String, text: String, current: Entry?, listing: MutableMap<String, Entry>) {
        val cache = File(File(context.cacheDir, "vault-note").apply { mkdirs() }, "$id.md")
        try {
            cache.writeText(text)
            if (writeAtomic(tulis, name, cache, current, listing)) note(FALLBACK_MSG)
        } finally {
            cache.delete()
        }
    }

    private fun ownedBy(id: String, entry: Entry): Boolean = readNote(entry.uri)?.let { VaultMarkdown.ownerId(it) } == id

    private fun readNote(uri: Uri): String? {
        return try {
            cr.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > NOTE_LIMIT) return null
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (t: IOException) {
            null
        }
    }

    private fun pngName(index: Int): String = "p%03d.png".format(index + 1)

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
        if (docEntry(uri)?.name != name) {
            try {
                DocumentsContract.deleteDocument(cr, uri)
            } catch (t: Throwable) {
            }
            throw IOException("Penyedia berkas mengubah nama")
        }
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
                nb[k] = NbState(
                    s.getString("hash"), s.getString("fp"), s.getLong("mod"), s.getLong("size"), s.getLong("at"),
                    if (s.isNull("md")) null else s.getString("md"),
                    if (s.isNull("pg")) null else s.getString("pg")
                )
            }
        } catch (t: Throwable) {
        }
        return nb
    }

    private fun saveState(root: String, nb: Map<String, NbState>) {
        val arr = JSONObject()
        for ((id, s) in nb) {
            arr.put(id, JSONObject().put("hash", s.hash).put("fp", s.fp).put("mod", s.mod).put("size", s.size).put("at", s.at).put("md", s.md).put("pg", s.pg))
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
