package org.crolab.chromescribe

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/** Server metadata for one transcript. [createdAt] is parsed millis (0 if unknown). */
data class TranscriptMeta(
    val id: String,
    val title: String,
    val channel: String,
    val createdAt: Long,
    val createdAtRaw: String = "",
)

/**
 * On-disk mirror of the dashboard. Pure JVM (java.io only) so cache atomicity
 * and offline safety are unit tested. Each markdown snapshot is written whole
 * and atomically and is never mutated in place, so a killed process always
 * keeps the previous readable copy.
 */
class TranscriptStore(private val dir: File) {

    companion object {
        const val MAX_CONTENT_CHARS = 1_000_000
        const val MAX_SAVED = 50
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }

    private val index = File(dir, "index.tsv")

    /**
     * Read the metadata index. A read or parse failure throws so a caller can
     * report it instead of silently overwriting the stored titles.
     */
    fun index(): List<TranscriptMeta> {
        if (!index.exists()) return emptyList()
        val lines = try {
            index.readLines()
        } catch (e: Exception) {
            throw IOException("cannot read index: ${e.message}")
        }
        return lines.filter { it.isNotBlank() }.map { line ->
            parseMeta(line) ?: throw IOException("corrupt index entry")
        }
    }

    private fun indexOrEmpty(): List<TranscriptMeta> = try {
        index()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Merge the server list with metadata for content already saved. Older
     * saved transcripts that fell out of the server's latest window keep their
     * titles instead of being silently orphaned.
     */
    fun saveIndex(serverList: List<TranscriptMeta>) {
        if (!dir.exists()) dir.mkdirs()
        val existing = indexOrEmpty().associateBy { it.id }
        val merged = LinkedHashMap<String, TranscriptMeta>()
        for (id in contentIds()) merged[id] = existing[id] ?: TranscriptMeta(id, id, "", 0L)
        for (meta in serverList) merged[meta.id] = meta
        writeIndex(merged.values.toList())
        prune()
    }

    /**
     * Write an immutable content snapshot and remember its metadata.
     * Returns false for an unsafe id or an oversized body; existing data is
     * left untouched in that case.
     */
    fun saveContent(meta: TranscriptMeta, markdown: String): Boolean {
        if (!SAFE_ID.matches(meta.id)) return false
        if (markdown.length > MAX_CONTENT_CHARS) return false
        AtomicFiles.write(contentFile(meta.id), markdown)
        val existing = indexOrEmpty().associateBy { it.id }
        val merged = LinkedHashMap<String, TranscriptMeta>()
        for (id in contentIds()) merged[id] = existing[id] ?: TranscriptMeta(id, id, "", 0L)
        merged[meta.id] = meta
        writeIndex(merged.values.toList())
        prune()
        return true
    }

    fun content(id: String): String? {
        if (!SAFE_ID.matches(id)) return null
        val f = contentFile(id)
        return if (f.exists()) try { f.readText() } catch (e: Exception) { null } else null
    }

    fun has(id: String): Boolean = SAFE_ID.matches(id) && contentFile(id).exists()

    fun remove(id: String) {
        if (!SAFE_ID.matches(id)) return
        contentFile(id).delete()
        writeIndex(indexOrEmpty().filterNot { it.id == id })
    }

    /** Only transcripts that actually have readable content on disk. */
    fun saved(): List<TranscriptMeta> {
        val known = indexOrEmpty().associateBy { it.id }
        return contentFiles().mapNotNull { f ->
            val id = idOf(f) ?: return@mapNotNull null
            known[id] ?: TranscriptMeta(id, id, "", 0L)
        }.sortedByDescending { it.createdAt }
    }

    /** Bound total disk: drop the oldest saved snapshots beyond [MAX_SAVED]. */
    private fun prune() {
        val files = contentFiles()
        if (files.size <= MAX_SAVED) return
        val known = indexOrEmpty().associateBy { it.id }
        val oldestFirst = files.sortedBy { f -> idOf(f)?.let { known[it]?.createdAt ?: 0L } ?: 0L }
        var toRemove = files.size - MAX_SAVED
        val removed = mutableListOf<String>()
        for (f in oldestFirst) {
            if (toRemove <= 0) break
            val id = idOf(f) ?: continue
            if (f.delete()) {
                removed.add(id)
                toRemove--
            }
        }
        if (removed.isNotEmpty()) writeIndex(indexOrEmpty().filterNot { it.id in removed })
    }

    private fun contentFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("content-") && f.name.endsWith(".md") }?.toList()
            ?: emptyList()

    private fun contentIds(): List<String> = contentFiles().mapNotNull { idOf(it) }

    private fun idOf(f: File): String? {
        val id = f.name.removePrefix("content-").removeSuffix(".md")
        return if (SAFE_ID.matches(id)) id else null
    }

    private fun contentFile(id: String) = File(dir, "content-$id.md")

    private fun writeIndex(list: List<TranscriptMeta>) {
        if (!dir.exists()) dir.mkdirs()
        AtomicFiles.write(index, list.joinToString("\n") { encodeMeta(it) })
    }

    private fun encodeMeta(m: TranscriptMeta): String = listOf(
        m.id, m.title, m.channel, m.createdAt.toString(), m.createdAtRaw
    ).joinToString("\t") { URLEncoder.encode(it, "UTF-8") }

    private fun parseMeta(line: String): TranscriptMeta? {
        if (line.isBlank()) return null
        val f = line.split("\t")
        if (f.size < 4) return null
        return try {
            TranscriptMeta(
                id = URLDecoder.decode(f[0], "UTF-8"),
                title = URLDecoder.decode(f[1], "UTF-8"),
                channel = URLDecoder.decode(f[2], "UTF-8"),
                createdAt = URLDecoder.decode(f[3], "UTF-8").toLong(),
                createdAtRaw = if (f.size >= 5) URLDecoder.decode(f[4], "UTF-8") else "",
            )
        } catch (e: Exception) {
            null
        }
    }
}