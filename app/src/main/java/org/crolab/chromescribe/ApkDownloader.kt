package org.crolab.chromescribe

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 + byte-count checks. Pure JVM so integrity rules are
 * unit-tested without Android. Never loads the whole APK into memory.
 */
object Hash {
    fun sha256Hex(input: InputStream, maxBytes: Long): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        input.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw UpdateException("apk exceeds size limit")
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }

    fun sha256File(file: File, maxBytes: Long): Pair<String, Long> =
        sha256Hex(FileInputStream(file), maxBytes)
}

/**
 * Streams an input to an output while enforcing the expected length and
 * ceiling *as bytes arrive*, hashing as it goes. Pure JVM so interrupted,
 * truncated, oversized and hash-mismatch cases are unit-tested directly.
 *
 * It writes only to the caller's temp file; it never touches the previous
 * staged APK.
 */
object ApkStreamVerifier {

    data class Outcome(val sha256: String, val bytes: Long)

    fun streamAndVerify(
        input: InputStream,
        out: FileOutputStream,
        expectedSize: Long,
        expectedSha256: String,
    ): Outcome {
        if (expectedSize > UpdatePolicy.MAX_APK_BYTES) throw UpdateException("expected apk too large")
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        input.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                // Enforce the ceiling *while* streaming, not after buffering.
                if (total > UpdatePolicy.MAX_APK_BYTES) throw UpdateException("apk exceeds size limit")
                // Enforce the declared length early too: an over-long stream is
                // rejected without writing the excess.
                if (total > expectedSize) throw UpdateException("apk longer than declared")
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (total != expectedSize) throw UpdateException("apk size mismatch")
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (!UpdatePolicy.hashMatches(expectedSha256, hex)) throw UpdateException("apk hash mismatch")
        return Outcome(hex, total)
    }
}

/**
 * Downloads an APK from the dashboard origin into a private cache directory.
 *
 * Safety properties:
 * - same-origin HTTPS only, redirects disabled (keeps the scoped session from
 *   leaking), via [Api.openAuthenticated],
 * - hard 20 MiB ceiling and the declared length enforced *while streaming*,
 * - the verified bytes are streamed to a temp file and renamed only after the
 *   expected length and SHA-256 both match,
 * - a failed attempt never deletes a previously staged good APK: the rename
 *   either replaces it or the temp file is discarded, leaving the old file.
 */
object ApkDownloader {

    private fun dir(ctx: Context) = File(ctx.filesDir, "updates").apply { mkdirs() }
    private fun targetFile(ctx: Context) = File(dir(ctx), "pending.apk")

    /** The staged APK, or null if none is present. */
    fun stagedApk(ctx: Context): File? = targetFile(ctx).takeIf { it.exists() && it.length() > 0 }

    /** Delete the staged APK. Callers decide *when* this is safe. */
    fun clear(ctx: Context) {
        targetFile(ctx).delete()
        File(dir(ctx), "pending.apk.tmp").delete()
    }

    /**
     * Download and integrity-check [manifest]. Returns the staged file, or
     * throws [UpdateException]/[AuthRequiredException]. The previous staged
     * APK is left untouched on any failure.
     */
    fun download(ctx: Context, manifest: UpdateManifest): File {
        val conn = try {
            Api.openAuthenticated(ctx, manifest.apkPath)
        } catch (e: AuthRequiredException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("update download blocked: ${e.message}")
        }
        val target = targetFile(ctx)
        val tmp = File(dir(ctx), "pending.apk.tmp")
        tmp.delete()
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive")
            val code = conn.responseCode
            if (code in 300..399 || code == 401 || code == 403) throw AuthRequiredException()
            if (code !in 200..299) throw UpdateException("download failed: HTTP $code")
            val ct = conn.contentType.orEmpty().lowercase()
            if (ct.startsWith("text/html")) throw UpdateException("download returned HTML")
            if (conn.contentLengthLong > UpdatePolicy.MAX_APK_BYTES) throw UpdateException("apk too large")

            FileOutputStream(tmp).use { out ->
                ApkStreamVerifier.streamAndVerify(conn.inputStream, out, manifest.size, manifest.sha256)
                out.flush()
                out.fd.sync()
            }

            // Verified: atomically replace the staged file. renameTo replaces an
            // existing target on the same filesystem; if it fails we fail
            // *without* deleting the previous good APK.
            if (!tmp.renameTo(target)) throw UpdateException("could not stage apk")
            return target
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }
}