package org.crolab.chromescribe

import org.json.JSONObject

/**
 * The install we are mid-way through, persisted only after the user taps
 * *Update*. It survives Activity recreation and process death so the OS can be
 * handed the APK, and it is what the cleanup decision later compares against.
 *
 * [settingsResume] is set only while we are waiting for the one-time
 * "install unknown apps" grant; it is cleared as soon as the installer is
 * launched (or the flow fails), so the settings round-trip never re-opens the
 * installer in a loop.
 */
data class PendingInstall(
    val manifest: UpdateManifest,
    val settingsResume: Boolean,
) {
    fun encode(): String = JSONObject()
        .put("versionCode", manifest.versionCode)
        .put("versionName", manifest.versionName)
        .put("minSdk", manifest.minSdk)
        .put("apkPath", manifest.apkPath)
        .put("sha256", manifest.sha256)
        .put("size", manifest.size)
        .put("notes", manifest.notes)
        .put("settingsResume", settingsResume)
        .toString()

    companion object {
        /** Re-parse through the same validation as the network path. */
        fun decode(json: String): PendingInstall? = try {
            val o = JSONObject(json)
            val manifest = UpdatePolicy.parse(
                JSONObject()
                    .put("versionCode", o.getInt("versionCode"))
                    .put("versionName", o.getString("versionName"))
                    .put("minSdk", o.getInt("minSdk"))
                    .put("apkPath", o.getString("apkPath"))
                    .put("sha256", o.getString("sha256"))
                    .put("size", o.getLong("size"))
                    .put("notes", o.optString("notes", ""))
                    .toString()
            )
            PendingInstall(manifest, o.optBoolean("settingsResume", false))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Pure decision: when is it safe to delete the staged APK?
 *
 * Never at launch — Android's installer reads the file asynchronously after we
 * hand it over. We keep the staged APK until a *future* foreground observes
 * that the newly installed version is at least the pending version (i.e. the
 * install completed), or until the user abandons a pending install.
 */
object UpdateCleanup {

    enum class Action { KEEP, DELETE }

    /**
     * @param pending the persisted pending install, or null if none
     * @param stagedApkExists whether the staged APK file is present
     * @param installedVersionCode the currently installed version code (>0), or null if unreadable
     */
    fun decide(pending: PendingInstall?, stagedApkExists: Boolean, installedVersionCode: Int?): Action {
        if (!stagedApkExists) return Action.DELETE
        // No pending record: the staged file is unowned/orphaned. It is never
        // trusted (nothing verified it in this state), so remove it.
        if (pending == null) return Action.DELETE
        // Installed version unknown: fail closed and keep (do not delete data
        // we cannot reason about), but do not offer an install either.
        if (installedVersionCode == null) return Action.KEEP
        return if (installedVersionCode >= pending.manifest.versionCode) Action.DELETE else Action.KEEP
    }
}