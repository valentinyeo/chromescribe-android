package org.crolab.chromescribe

import org.json.JSONObject

/**
 * Parsed `/api/android/latest` response.
 *
 * [apkPath] is validated against a strict allow-list pattern so a compromised
 * or buggy server can never point the installer at an arbitrary R2 key.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val apkPath: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

/** Raised when the update endpoint returns something we must not act on. */
class UpdateException(message: String) : java.io.IOException(message)

/**
 * Pure parsing/validation for the update manifest and APK integrity metadata.
 * No Android types, so every rejection rule is unit tested on the JVM.
 *
 * Fail-closed: anything malformed, out of range, suspiciously large, or with a
 * non-canonical path is rejected rather than "best effort" accepted.
 */
object UpdatePolicy {

    /** 20 MiB download ceiling. */
    const val MAX_APK_BYTES = 20L * 1024 * 1024
    const val MIN_APK_BYTES = 64L * 1024

    /** Only `/api/android/releases/<integer>/app.apk` is ever downloadable. */
    private val APK_PATH = Regex("^/api/android/releases/[1-9][0-9]{0,9}/app\\.apk$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val VERSION_NAME = Regex("^[0-9]+(\\.[0-9]+){0,3}$")
    private const val MAX_NOTES_CHARS = 4000

    /** True only for the exact canonical release path. */
    fun isAllowedApkPath(path: String?): Boolean {
        if (path == null) return false
        if (path.contains('\\') || path.contains("..") || path.contains("//")) return false
        return APK_PATH.matches(path)
    }

    /**
     * Version embedded in a canonical apk path, or null. Used to cross-check
     * that a manifest's path and versionCode agree.
     */
    fun pathVersionCode(path: String): Int? =
        if (!isAllowedApkPath(path)) null
        else path.substringAfter("/releases/").substringBefore('/').toIntOrNull()

    fun parse(json: String): UpdateManifest {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UpdateException("malformed update manifest")
        }
        // Strict integer extraction: JSON numbers must be integral (no decimals,
        // no exponent), and strings/booleans are never coerced.
        val code = strictInt(root, "versionCode") ?: throw UpdateException("missing versionCode")
        if (code < 1) throw UpdateException("missing versionCode")
        val nameRaw = root.opt("versionName")
        val name = nameRaw as? String ?: throw UpdateException("invalid versionName")
        if (!VERSION_NAME.matches(name)) throw UpdateException("invalid versionName")
        val minSdk = strictInt(root, "minSdk") ?: throw UpdateException("missing minSdk")
        if (minSdk < 1) throw UpdateException("missing minSdk")
        val pathRaw = root.opt("apkPath") as? String ?: throw UpdateException("unsafe apkPath")
        if (!isAllowedApkPath(pathRaw)) throw UpdateException("unsafe apkPath")
        if (pathVersionCode(pathRaw) != code) throw UpdateException("apkPath/versionCode mismatch")
        val shaRaw = root.opt("sha256") as? String ?: throw UpdateException("invalid sha256")
        val sha = shaRaw.lowercase()
        if (!SHA256.matches(sha)) throw UpdateException("invalid sha256")
        val size = strictLong(root, "size") ?: throw UpdateException("apk size out of range")
        if (size < MIN_APK_BYTES || size > MAX_APK_BYTES) throw UpdateException("apk size out of range")
        val notes = (root.opt("notes") as? String).orEmpty().take(MAX_NOTES_CHARS)
        return UpdateManifest(code, name, minSdk, pathRaw, sha, size, notes)
    }

    /**
     * Integral JSON number only. Rejects decimals (1.5), exponents (3e0),
     * booleans, strings that merely look numeric, null, and out-of-range
     * values. The check is lexical on the raw representation so a value that
     * merely *equals* an integer (e.g. 3.0) is still rejected.
     */
    private fun strictInt(root: JSONObject, key: String): Int? {
        val l = strictLong(root, key) ?: return null
        if (l < Int.MIN_VALUE.toLong() || l > Int.MAX_VALUE.toLong()) return null
        return l.toInt()
    }

    private fun strictLong(root: JSONObject, key: String): Long? {
        val v = root.opt(key) ?: return null
        // Accept only JSON integer literals, which org.json represents as
        // Int/Long. Anything else (BigDecimal from 3.0/3e0, Boolean, String,
        // null) is rejected rather than coerced.
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            else -> null
        }
    }

    /**
     * Decide whether [manifest] is worth offering for the currently installed
     * version. A same-or-older version code is never offered.
     */
    fun isNewer(manifest: UpdateManifest, installedVersionCode: Int, installedMinSdk: Int): Boolean {
        if (manifest.minSdk > installedMinSdk) return false
        return manifest.versionCode > installedVersionCode
    }

    /** Case-insensitive constant-time-ish hash comparison (no early byte exit on mismatch prefix). */
    fun hashMatches(expectedHex: String, actualHex: String): Boolean {
        val a = expectedHex.lowercase()
        val b = actualHex.lowercase()
        if (a.length != b.length || a.isEmpty()) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/**
 * Tracks whether a foreground daily update check is due. Stored in
 * SharedPreferences, but the rule itself is pure and testable.
 */
object UpdateSchedule {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    fun due(lastCheckAt: Long, now: Long): Boolean {
        if (lastCheckAt <= 0L) return true
        if (now < lastCheckAt) return true // clock moved backwards: allow a check
        return now - lastCheckAt >= INTERVAL_MS
    }
}