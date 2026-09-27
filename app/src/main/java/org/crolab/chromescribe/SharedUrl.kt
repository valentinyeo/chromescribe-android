package org.crolab.chromescribe

import java.net.URI
import java.util.Locale

/** Shared-link validation without Android dependencies. The server makes the final decision. */
object SharedUrl {
    enum class Kind { YOUTUBE, TIKTOK, ARTICLE }
    data class Link(val url: String, val kind: Kind)

    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be"
    )

    // YouTube ids are exactly 11 chars of [A-Za-z0-9_-].
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Extract only the first URL, even when a later URL would be supported. */
    fun firstUrl(text: String?): String? =
        text?.let { URL_IN_TEXT.find(it)?.value }

    fun extract(text: String?): Link? = firstUrl(text)?.let { parse(it) }

    fun parse(raw: String): Link? {
        val trimmed = raw.trim()
        if (trimmed.length > 2048) return null
        val uri = try {
            URI(trimmed)
        } catch (e: java.net.URISyntaxException) {
            // Preserve the existing YouTube share-punctuation handling only.
            val cleaned = trimmed.trimEnd('.', ',', ')', ']', '}')
            return if (cleaned != trimmed) parse(cleaned)?.takeIf { it.kind == Kind.YOUTUBE }
            else null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port > 65535) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val safetyHost = host.trimEnd('.')
        if (safetyHost == "localhost" || safetyHost.endsWith(".localhost") ||
            safetyHost == "local" || safetyHost.endsWith(".local") ||
            safetyHost == "internal" || safetyHost.endsWith(".internal") ||
            safetyHost.contains(':') || isIpv4Literal(safetyHost)) return null

        if (host in HOSTS) {
            if (uri.port != -1 && uri.port != 443) return null
            val youtubeUri = URI(trimmed.trimEnd('.', ',', ')', ']', '}'))
            return youtube(youtubeUri, host)?.let { Link(it, Kind.YOUTUBE) }
        }
        if (host in setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com")) {
            if (!Regex("/@[^/]+/video/[0-9]+/?").matches(uri.rawPath.orEmpty())) return null
            return Link(trimmed, Kind.TIKTOK)
        }
        if (host == "vm.tiktok.com" || host == "vt.tiktok.com") {
            if (!Regex("/[A-Za-z0-9_-]+/?").matches(uri.rawPath.orEmpty())) return null
            return Link(trimmed, Kind.TIKTOK)
        }
        return Link(trimmed, Kind.ARTICLE)
    }

    // Browser URL parsers also recognise integer, octal and hexadecimal IPv4 forms.
    private fun isIpv4Literal(host: String): Boolean =
        host.split('.').all {
            it.matches(Regex("[0-9]+")) || it.matches(Regex("0[xX][0-9a-fA-F]+"))
        }

    private fun youtube(uri: URI, host: String): String? {
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
