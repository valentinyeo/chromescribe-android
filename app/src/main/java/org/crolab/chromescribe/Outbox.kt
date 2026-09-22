package org.crolab.chromescribe

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

enum class OutboxState { PENDING, SENDING, FAILED, AUTH }

data class OutboxEntry(
    val id: String,
    val url: String,
    val createdAt: Long,
    val state: OutboxState,
    val attempts: Int,
    val message: String,
)

/**
 * Durable submission queue. Pure JVM (java.io only) so the retry and
 * persistence semantics can be unit tested without Android.
 *
 * Every mutation rewrites the whole file atomically (temp file + rename), so a
 * crash never leaves a half-written entry and an entry is on disk *before* any
 * network call is made.
 */
class Outbox(private val dir: File) {

    private val file = File(dir, "outbox.tsv")

    companion object {
        const val MAX_ENTRIES = 100
        // One process-wide lock: multiple Activity instances share the same file.
        private val LOCK = Any()
    }

    class OutboxCorruptException(message: String) : java.io.IOException(message)
    class OutboxFullException(message: String) : java.io.IOException(message)

    fun add(url: String): OutboxEntry = synchronized(LOCK) {
        val current = all()
        current.firstOrNull { it.url == url }?.let { return it }
        if (current.size >= MAX_ENTRIES) throw OutboxFullException("outbox full")
        val entry = OutboxEntry(
            id = UUID.randomUUID().toString(),
            url = url,
            createdAt = System.currentTimeMillis(),
            state = OutboxState.PENDING,
            attempts = 0,
            message = "",
        )
        write(current + entry)
        entry
    }

    /**
     * Read every entry. A read or parse failure throws instead of returning an
     * empty list, so a later mutation can never silently overwrite submissions.
     */
    fun all(): List<OutboxEntry> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            throw OutboxCorruptException("cannot read outbox: ${e.message}")
        }
        lines.filter { it.isNotBlank() }.map { line ->
            parse(line) ?: throw OutboxCorruptException("corrupt outbox entry")
        }
    }

    /** Everything not yet confirmed by the server, including a request that was
     * in flight (SENDING) when the process died. Retried on next resume. */
    fun pending(): List<OutboxEntry> = all()

    fun update(id: String, state: OutboxState, message: String) = synchronized(LOCK) {
        val next = all().map {
            if (it.id == id) it.copy(state = state, message = message, attempts = it.attempts + 1)
            else it
        }
        write(next)
    }

    fun remove(id: String) = synchronized(LOCK) {
        write(all().filterNot { it.id == id })
    }

    private fun write(entries: List<OutboxEntry>) {
        if (!dir.exists()) dir.mkdirs()
        val text = entries.joinToString("\n") { encode(it) }
        AtomicFiles.write(file, text)
    }

    private fun encode(e: OutboxEntry): String = listOf(
        e.id, e.url, e.createdAt.toString(), e.state.name, e.attempts.toString(), e.message
    ).joinToString("\t") { enc(it) }

    private fun parse(line: String): OutboxEntry? {
        if (line.isBlank()) return null
        val f = line.split("\t")
        if (f.size < 6) return null
        return try {
            OutboxEntry(
                id = dec(f[0]),
                url = dec(f[1]),
                createdAt = dec(f[2]).toLong(),
                state = OutboxState.valueOf(dec(f[3])),
                attempts = dec(f[4]).toInt(),
                message = dec(f[5]),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
}

/**
 * Same-filesystem temp-write-then-rename. The temp file is fsynced before the
 * rename and the original is never deleted first, so a crash or rename failure
 * always leaves the previous known-good file in place.
 */
object AtomicFiles {
    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + "." + Thread.currentThread().id + "." + System.nanoTime() + ".tmp")
        val fos = java.io.FileOutputStream(tmp)
        try {
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        } finally {
            fos.close()
        }
        if (tmp.renameTo(target)) return
        tmp.delete()
        throw java.io.IOException("could not atomically replace ${target.name}")
    }
}