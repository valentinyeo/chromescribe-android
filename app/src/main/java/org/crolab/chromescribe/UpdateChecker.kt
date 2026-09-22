package org.crolab.chromescribe

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Update checks and downloads, split so the user consents *before* any APK is
 * fetched:
 *
 * - [check] only fetches and validates the manifest. On a newer version it
 *   returns [Result.Available] and does not download.
 * - [prepare] is called only after the user taps *Update* in the notes dialog.
 *   It downloads, integrity-checks and on-device-verifies the APK, then
 *   persists a [PendingInstall].
 *
 * A single process-wide busy guard spans both phases so checks, downloads and
 * an in-flight install never overlap.
 *
 * Auth: manifest fetches reuse [Api.openAuthenticated] (WebView-scoped cookie).
 * A check blocked by auth returns [Result.Auth] and does NOT advance the daily
 * marker, so it retries after sign-in. Network/other failures also do not mark
 * the day for automatic checks.
 */
object UpdateChecker {

    private const val MAX_MANIFEST_BYTES = 256L * 1024
    private const val LAST_CHECK = "update_last_check_at"
    private const val PENDING_KEY = "update_pending_install"

    sealed class Result {
        data class UpToDate(val message: String?) : Result()
        /** A newer version exists; nothing downloaded yet. */
        data class Available(val manifest: UpdateManifest) : Result()
        /** Download + verification complete; [pending] is persisted. */
        data class Prepared(val pending: PendingInstall, val apk: File) : Result()
        object Auth : Result()
        data class Failed(val message: String, val manual: Boolean) : Result()
        /** A check/download was already in flight. */
        object Busy : Result()
    }

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    private inline fun <T> guarded(block: () -> T): T? {
        synchronized(this) {
            if (running) return null
            running = true
        }
        try {
            return block()
        } finally {
            running = false
        }
    }

    // --- persisted state ----------------------------------------------------

    fun markChecked(ctx: Context, now: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(LAST_CHECK, now).apply()
    }

    fun lastCheckedAt(ctx: Context): Long = prefs(ctx).getLong(LAST_CHECK, 0L)

    fun dueForForegroundCheck(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        UpdateSchedule.due(lastCheckedAt(ctx), now)

    /** The persisted pending install, or null. */
    fun pending(ctx: Context): PendingInstall? {
        val raw = prefs(ctx).getString(PENDING_KEY, null) ?: return null
        return PendingInstall.decode(raw)
    }

    fun savePending(ctx: Context, pending: PendingInstall) {
        prefs(ctx).edit().putString(PENDING_KEY, pending.encode()).apply()
    }

    fun clearPending(ctx: Context) {
        prefs(ctx).edit().remove(PENDING_KEY).apply()
    }

    /**
     * Foreground cleanup: keep the staged APK until a later foreground sees the
     * new version installed; then delete it. Never deletes at launch while an
     * install may be in flight. Returns true if a staged APK remains.
     */
    fun reconcileStaged(ctx: Context): Boolean {
        val pending = pending(ctx)
        val exists = ApkDownloader.stagedApk(ctx) != null
        val installed = installedVersionCodeOrNull(ctx)
        val action = UpdateCleanup.decide(pending, exists, installed)
        if (action == UpdateCleanup.Action.DELETE) {
            ApkDownloader.clear(ctx)
            // A completed (or abandoned) install is no longer pending.
            if (pending != null && installed != null && installed >= pending.manifest.versionCode) {
                clearPending(ctx)
            }
        }
        return ApkDownloader.stagedApk(ctx) != null
    }

    // --- phases -------------------------------------------------------------

    /** Fetch and validate the manifest only. Never downloads. */
    fun check(ctx: Context, manual: Boolean): Result {
        return guarded {
            val installed = installedVersionCodeOrNull(ctx)
                ?: return@guarded Result.Failed(if (manual) "Could not read installed version" else "", manual)
            val manifest = try {
                fetchManifest(ctx)
            } catch (e: AuthRequiredException) {
                return@guarded Result.Auth
            } catch (e: Exception) {
                // Do NOT mark the day on a failed automatic check.
                return@guarded Result.Failed(
                    if (manual) "Update check failed: ${e.message ?: "network error"}" else "",
                    manual,
                )
            }
            if (!UpdatePolicy.isNewer(manifest, installed, android.os.Build.VERSION.SDK_INT)) {
                markChecked(ctx)
                return@guarded Result.UpToDate(if (manual) "ChromeScribe is up to date" else null)
            }
            markChecked(ctx)
            Result.Available(manifest)
        } ?: Result.Busy
    }

    /**
     * Download, integrity-check and on-device-verify the APK for a manifest
     * returned by [check]. Persists the pending install. On any failure it does
     * not delete an existing staged APK and returns a soft failure.
     */
    fun prepare(ctx: Context, manifest: UpdateManifest, manual: Boolean): Result {
        return guarded {
            val apk = try {
                ApkDownloader.download(ctx, manifest)
            } catch (e: AuthRequiredException) {
                return@guarded Result.Auth
            } catch (e: Exception) {
                return@guarded Result.Failed(
                    if (manual) "Update download failed: ${e.message ?: "network error"}" else "",
                    manual,
                )
            }
            try {
                ApkVerifier.verify(ctx, apk, manifest)
            } catch (e: Exception) {
                // A failing APK is discarded (this attempt's bytes), but the
                // previous staged APK, if any, is not touched.
                ApkDownloader.clear(ctx)
                return@guarded Result.Failed(
                    if (manual) "Update rejected: ${e.message ?: "verification failed"}" else "",
                    manual,
                )
            }
            val pending = PendingInstall(manifest, settingsResume = false)
            savePending(ctx, pending)
            Result.Prepared(pending, apk)
        } ?: Result.Busy
    }

    /**
     * Re-verify hash + length and archive package/signature/version immediately
     * before launching the installer. Returns true only if the staged APK is
     * still exactly the verified artifact.
     */
    fun reverifyBeforeInstall(ctx: Context, pending: PendingInstall): Boolean {
        val apk = ApkDownloader.stagedApk(ctx) ?: return false
        return try {
            val (hash, bytes) = Hash.sha256File(apk, UpdatePolicy.MAX_APK_BYTES)
            if (bytes != pending.manifest.size) return false
            if (!UpdatePolicy.hashMatches(pending.manifest.sha256, hash)) return false
            ApkVerifier.verify(ctx, apk, pending.manifest)
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun fetchManifest(ctx: Context): UpdateManifest {
        val conn = Api.openAuthenticated(ctx, "/api/android/latest")
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code in 300..399 || code == 401 || code == 403) throw AuthRequiredException()
            if (code !in 200..299) throw UpdateException("HTTP $code")
            if (conn.contentType.orEmpty().lowercase().startsWith("text/html")) throw AuthRequiredException()
            if (conn.contentLengthLong > MAX_MANIFEST_BYTES) throw UpdateException("manifest too large")
            return UpdatePolicy.parse(readLimited(conn.inputStream, MAX_MANIFEST_BYTES))
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream?, max: Long): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        input.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw UpdateException("manifest too large")
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    /**
     * Installed version code, or null if it cannot be read. Callers must fail
     * closed on null rather than assume a value that could permit a downgrade.
     */
    fun installedVersionCodeOrNull(ctx: Context): Int? = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) {
        null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chromescribe", Context.MODE_PRIVATE)
}