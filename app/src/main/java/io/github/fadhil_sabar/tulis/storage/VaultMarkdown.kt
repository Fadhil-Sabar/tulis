package io.github.fadhil_sabar.tulis.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VaultMarkdown {
    const val BEGIN = "%% tulis:begin %%"
    const val END = "%% tulis:end %%"

    private const val FENCE = "---"
    private const val FIELD_ID = "tulis-id"
    private const val FIELD_UPDATED = "tulis-updated"
    private const val FIELD_PAGES = "tulis-pages"
    private const val FIELD_FOLDER = "tulis-folder"
    private const val FIELD_HASH = "tulis-hash"
    private const val EMPTY_TITLE = "Tanpa judul"
    private const val MAX_NAME = 80
    private const val ID_PREFIX_LEN = 6
    private val FORBIDDEN = "\\/:*?\"<>|#^[]".toSet()
    private val SPACES = Regex("\\s+")

    sealed class Merge {
        class Ok(val text: String) : Merge()
        class Broken(val reason: String) : Merge()
        object NotOurs : Merge()
    }

    fun fileName(title: String, id: String, disambiguate: Boolean): String {
        val clean = title
            .filter { it !in FORBIDDEN && !it.isISOControl() }
            .replace(SPACES, " ")
            .trim(' ', '.')
            .take(MAX_NAME)
            .trim(' ', '.')
            .ifEmpty { EMPTY_TITLE }
        return if (disambiguate) "$clean ~${id.take(ID_PREFIX_LEN)}.md" else "$clean.md"
    }

    fun create(id: String, title: String, updatedAt: Long, pageCount: Int, folderPath: String, hash: String, embeds: List<String>): String {
        val fields = listOfNotNull(
            "$FIELD_ID: $id",
            "$FIELD_UPDATED: ${stamp(updatedAt)}",
            "$FIELD_PAGES: $pageCount",
            folderPath.takeIf { it.isNotEmpty() }?.let { "$FIELD_FOLDER: ${quote(it)}" },
            "$FIELD_HASH: $hash",
        )
        return buildString {
            append(FENCE).append('\n')
            fields.forEach { append(it).append('\n') }
            append(FENCE).append("\n\n")
            append(BEGIN).append('\n')
            embeds.forEach { append(it).append('\n') }
            append(END).append('\n')
        }
    }

    fun merge(existing: String, id: String, updatedAt: Long, pageCount: Int, folderPath: String, hash: String, embeds: List<String>): Merge {
        if (ownerId(existing) != id) return Merge.NotOurs
        val lines = existing.split("\n")
        val close = closeIndex(lines) ?: return Merge.NotOurs
        val wanted = linkedMapOf<String, String?>(
            FIELD_UPDATED to stamp(updatedAt),
            FIELD_PAGES to pageCount.toString(),
            FIELD_FOLDER to folderPath.takeIf { it.isNotEmpty() }?.let { quote(it) },
            FIELD_HASH to hash,
        )
        val seen = mutableSetOf<String>()
        val front = mutableListOf<String>()
        for (line in lines.subList(1, close)) {
            val k = key(line)
            if (k !in wanted) {
                front += line
                continue
            }
            seen += k
            wanted.getValue(k)?.let { front += "$k: $it${eolOf(line)}" }
        }
        val eol = eolOf(lines[0])
        wanted.filter { it.value != null && it.key !in seen }.forEach { front += "${it.key}: ${it.value}$eol" }

        val body = lines.subList(close + 1, lines.size)
        val begins = body.indices.filter { body[it].trim() == BEGIN }
        val ends = body.indices.filter { body[it].trim() == END }
        if (begins.isEmpty() && ends.isEmpty()) return Merge.Broken("Penanda tidak ditemukan")
        if (begins.size != 1 || ends.size != 1 || ends[0] < begins[0]) return Merge.Broken("Penanda rusak")
        val b = begins[0]
        val e = ends[0]
        val newBody = body.subList(0, b + 1) + embeds.map { it + eolOf(body[b]) } + body.subList(e, body.size)
        return Merge.Ok((listOf(lines[0]) + front + lines[close] + newBody).joinToString("\n"))
    }

    fun ownerId(existing: String): String? {
        val lines = existing.split("\n")
        val close = closeIndex(lines) ?: return null
        return lines.subList(1, close)
            .firstOrNull { key(it) == FIELD_ID }
            ?.substringAfter(':')
            ?.trim('"', '\'', ' ', '\r')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun closeIndex(lines: List<String>): Int? {
        if (lines.isEmpty() || lines[0].trimEnd('\r') != FENCE) return null
        return (1 until lines.size).firstOrNull { lines[it].trimEnd('\r') == FENCE }
    }

    private fun key(line: String) = line.substringBefore(':').trim()

    private fun eolOf(line: String) = if (line.endsWith('\r')) "\r" else ""

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
