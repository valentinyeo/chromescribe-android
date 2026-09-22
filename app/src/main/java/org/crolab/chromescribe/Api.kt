package org.crolab.chromescribe

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The WebView session is missing/expired: show "sign in", don't parse JSON. */
class AuthRequiredException : IOException("ChromeScribe sign-in required")

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin HttpURLConnection client for the dashboard API.
 *
 * Login happens in the WebView, so the only credential is the dashboard's own
 * Cloudflare cookie. It is read from CookieManager and attached *only* when the
 * request host exactly matches the dashboard host. Redirects are never
 * followed, so cookies can't leak to another host. There is no SSL bypass and
 * no JS bridge.
 */
object Api {

    val BASE: String = BuildConfig.WEB_BASE
    private const val MAX_BYTES = 2_000_000
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    sealed class QueueResult {
        data class Queued(val id: String) : QueueResult()
        data class AlreadyTranscribed(val transcriptId: String) : QueueResult()
        object Duplicate : QueueResult()
    }

    fun listTranscripts(ctx: Context): List<TranscriptMeta> {
        val root = JSONObject(get(ctx, "/api/transcripts"))
        if (!root.optBoolean("success", false)) throw ApiException(200, "API reported failure")
        val arr = root.optJSONArray("data") ?: throw ApiException(200, "missing data")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.opt("id")?.toString() ?: return@mapNotNull null
            metaFrom(o, id)
        }
    }

    fun transcriptMeta(ctx: Context, id: String): TranscriptMeta {
        requireId(id)
        val root = JSONObject(get(ctx, "/api/transcripts/$id"))
        if (!root.optBoolean("success", false)) throw ApiException(200, "API reported failure")
        val o = root.optJSONObject("data") ?: throw ApiException(200, "missing data")
        return metaFrom(o, o.opt("id")?.toString() ?: id)
    }

    private fun metaFrom(o: JSONObject, fallbackId: String): TranscriptMeta {
        val raw = o.optString("created_at", "").ifBlank { o.optString("created_at_formatted", "") }
        return TranscriptMeta(
            id = o.opt("id")?.toString() ?: fallbackId,
            title = o.optString("title", fallbackId),
            channel = o.optString("channel", ""),
            createdAt = ServerDate.toMillis(raw),
            createdAtRaw = raw,
        )
    }

    fun transcriptContent(ctx: Context, id: String): String {
        requireId(id)
        return get(ctx, "/api/transcript/$id/content")
    }

    fun queue(ctx: Context, url: String): QueueResult {
        val body = JSONObject().put("url", url).toString()
        return parseQueueResponse(postJson(ctx, "/api/queue", body))
    }

    /**
     * Open a same-origin, no-redirect HTTPS connection with the WebView's
     * scoped dashboard cookie attached. Used by the updater so cookie handling
     * lives in exactly one place. The caller owns connect/disconnect.
     */
    internal fun openAuthenticated(ctx: Context, path: String): HttpURLConnection {
        val base = URL(BASE)
        val url = URL(base, path)
        // Scheme, host and effective port must match the dashboard origin
        // exactly; userinfo (credentials in the URL) is never allowed.
        if (url.protocol.lowercase() != "https") throw ApiException(0, "insecure update URL")
        if (url.host.lowercase() != base.host.lowercase()) throw ApiException(0, "cross-origin update URL")
        if (effectivePort(url.port) != effectivePort(base.port)) throw ApiException(0, "cross-origin update port")
        if (!url.userInfo.isNullOrEmpty()) throw ApiException(0, "update URL carries credentials")
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx))
        conn.setRequestProperty("Accept", "application/json")
        attachCookie(conn)
        return conn
    }

    private fun effectivePort(port: Int): Int = if (port == -1) 443 else port

    /**
     * Parse a `/api/queue` envelope. Only an explicit acknowledgement is
     * accepted: queued=true with an id (optionally duplicate), or queued=false
     * with a transcript_id. Anything else is rejected so the durable outbox is
     * never cleared on a response the server did not actually confirm.
     */
    fun parseQueueResponse(body: String): QueueResult {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw ApiException(200, root.optString("error", "queue rejected"))
        }
        val data = root.optJSONObject("data")
            ?: throw ApiException(200, "malformed queue response: missing data")
        val queuedValue = data.opt("queued")
        if (queuedValue !is Boolean) throw ApiException(200, "malformed queue response: missing queued")
        return if (queuedValue) {
            val id = validId(data.opt("id"))
                ?: throw ApiException(200, "malformed queue response: missing id")
            if (data.optBoolean("duplicate", false)) QueueResult.Duplicate
            else QueueResult.Queued(id)
        } else {
            val transcriptId = validId(data.opt("transcript_id"))
                ?: throw ApiException(200, "malformed queue response: missing transcript_id")
            QueueResult.AlreadyTranscribed(transcriptId)
        }
    }

    /**
     * Returns a normalized integer id string for a valid positive numeric id,
     * or null for anything malformed (0, negative, fractional, NaN/infinity,
     * out of range, or non-numeric).
     */
    private fun validId(value: Any?): String? {
        when (value) {
            null -> return null
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) return null
                val l = d.toLong()
                if (d != l.toDouble()) return null
                if (l < 1L || l > MAX_ID) return null
                return l.toString()
            }
            is String -> {
                val t = value.trim()
                if (!t.matches(Regex("^[0-9]{1,10}$"))) return null
                val l = t.toLongOrNull() ?: return null
                if (l < 1L || l > MAX_ID) return null
                return l.toString()
            }
            else -> return null
        }
    }

    private const val MAX_ID = 9_999_999_999L

    private fun get(ctx: Context, path: String): String = request(ctx, "GET", path, null)

    private fun postJson(ctx: Context, path: String, json: String): String =
        request(ctx, "POST", path, json)

    private fun request(ctx: Context, method: String, path: String, body: String?): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx))
            conn.setRequestProperty("Accept", "application/json, text/markdown, text/plain;q=0.8")
            attachCookie(conn)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (code in 300..399 || code == 401 || code == 403) throw AuthRequiredException()
            if (conn.contentType.orEmpty().lowercase().startsWith("text/html")) {
                throw AuthRequiredException()
            }
            val text = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw ApiException(code, text.take(300))
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Cookies are host-scoped and redirects are disabled, so this is the only place we attach them. */
    private fun attachCookie(conn: HttpURLConnection) {
        val requestHost = conn.url.host.lowercase()
        val baseHost = URL(BASE).host.lowercase()
        if (requestHost != baseHost) return
        val cookie = CookieManager.getInstance().getCookie(BASE) ?: return
        conn.setRequestProperty("Cookie", cookie)
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        input.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw ApiException(0, "response too large")
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    private fun requireId(id: String) {
        if (!SAFE_ID.matches(id)) throw ApiException(0, "unsafe transcript id")
    }
}