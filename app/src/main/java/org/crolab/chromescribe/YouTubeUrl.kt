package org.crolab.chromescribe

import java.net.URI
import java.util.Locale

/**
 * Strict, dependency-free YouTube URL handling. Pure JVM so it is unit tested.
 *
 * Only HTTPS links on the exact YouTube hosts are accepted and everything is
 * normalised to https://www.youtube.com/watch?v=<id>. Look-alike hosts,
 * user-info tricks, other ports, other schemes and non-video paths are rejected.
 */
object YouTubeUrl {

    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be"
    )

    // YouTube ids are exactly 11 chars of [A-Za-z0-9_-].
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Pull the first acceptable YouTube URL out of a shared text blob. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (raw in URL_IN_TEXT.findAll(text).map { it.value }) {
            normalize(raw)?.let { return it }
        }
        return null
    }

    /** Validate and normalise a single URL, or null if it is not a real YouTube video. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.', ',', ')', ']', '}')
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        val port = uri.port
        if (port != -1 && port != 443) return null

        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host !in HOSTS) return null

        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> {
                val rest = path.trim('/')
                if (rest.contains('/')) null else rest
            }
            else -> when {
                path == "/watch" || path == "/watch/" -> watchId(uri.rawQuery)
                path.startsWith("/shorts/") -> singleSegment(path.removePrefix("/shorts/"))
                path.startsWith("/live/") -> singleSegment(path.removePrefix("/live/"))
                else -> null
            }
        }
        if (id == null || !ID.matches(id)) return null
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun singleSegment(rest: String): String? {
        val trimmed = rest.trim('/')
        return if (trimmed.contains('/')) null else trimmed
    }

    private fun watchId(rawQuery: String?): String? {
        val q = rawQuery ?: return null
        for (pair in q.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            if (pair.substring(0, i) != "v") continue
            return pair.substring(i + 1).substringBefore('&')
        }
        return null
    }
}