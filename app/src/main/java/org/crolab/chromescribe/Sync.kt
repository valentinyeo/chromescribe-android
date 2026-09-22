package org.crolab.chromescribe

import android.content.Context
import android.util.Log

/** Foreground-only worker. Static so activities can't leak through it. */
object AppExecutors {
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "chromescribe-io").apply { isDaemon = true }
    }

    /**
     * Unexpected failures are logged instead of silently swallowed, and can be
     * surfaced to the UI via [onError]. Callers still catch expected errors.
     */
    fun run(tag: String = "chromescribe", onError: ((Throwable) -> Unit)? = null, task: () -> Unit) {
        io.execute {
            try {
                task()
            } catch (e: Throwable) {
                Log.e(tag, "background task failed", e)
                onError?.let { callback ->
                    try {
                        callback(e)
                    } catch (cb: Throwable) {
                        Log.e(tag, "error callback failed", cb)
                    }
                }
            }
        }
    }
}

/**
 * Explicit "Sync" plus best-effort caching of whatever transcript is open.
 * A failed list fetch never overwrites the previous index; a failed content
 * fetch never deletes the previous snapshot.
 */
object Sync {

    const val SYNC_LIMIT = 20

    data class Report(
        val listed: Int,
        val cached: Int,
        val failed: Int,
        val authRequired: Boolean,
        val message: String?,
    )

    fun run(ctx: Context, store: TranscriptStore, refresh: Boolean): Report {
        val list = try {
            Api.listTranscripts(ctx)
        } catch (e: AuthRequiredException) {
            return Report(0, 0, 0, true, "Sign in required")
        } catch (e: ApiException) {
            return Report(0, 0, 0, false, "Server error ${e.code}: ${e.message}")
        } catch (e: Exception) {
            return Report(0, 0, 0, false, "Sync failed: ${e.message ?: "network error"}")
        }
        // A metadata write failure must be reported, not hidden.
        try {
            store.saveIndex(list)
        } catch (e: Exception) {
            return Report(0, 0, 0, false, "Sync failed: could not save library index")
        }

        var cached = 0
        var failed = 0
        for (meta in list.take(SYNC_LIMIT)) {
            if (!refresh && store.has(meta.id)) continue
            try {
                val md = Api.transcriptContent(ctx, meta.id)
                if (store.saveContent(meta, md)) cached++ else failed++
            } catch (e: AuthRequiredException) {
                return Report(list.size, cached, failed, true, "Sign in required")
            } catch (e: Exception) {
                failed++
            }
        }
        val message = when {
            failed > 0 -> "Synced $cached, $failed failed"
            else -> "Synced $cached of ${list.size}"
        }
        return Report(list.size, cached, failed, false, message)
    }

    /** Cache the transcript currently open in the WebView (metadata + markdown). */
    fun cacheViewed(ctx: Context, store: TranscriptStore, id: String, title: String): Boolean {
        if (store.has(id)) return true
        val meta = try {
            Api.transcriptMeta(ctx, id)
        } catch (e: Exception) {
            TranscriptMeta(id, title.ifBlank { id }, "", 0L)
        }
        return try {
            store.saveContent(meta, Api.transcriptContent(ctx, id))
        } catch (e: Exception) {
            false
        }
    }
}