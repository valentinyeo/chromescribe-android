package org.crolab.chromescribe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Launches the system package installer for the verified APK.
 *
 * Uses the platform `ACTION_VIEW` + `application/vnd.android.package-archive`
 * flow rather than the PackageInstaller session API: no new dependencies, the
 * OS shows its own confirmation screen, and our content provider grants a
 * one-shot read permission for exactly this URI.
 *
 * Android 8+ (API 26) additionally requires the user to allow "install unknown
 * apps" for this app. That is requested once via
 * `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; the caller must resume the install after
 * the user returns.
 *
 * The caller is responsible for having re-verified the APK immediately before
 * calling [install] (see [UpdateChecker.reverifyBeforeInstall]).
 */
object ApkInstaller {

    /** True when this app may currently request package installs (always true below API 26). */
    fun canInstall(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    /**
     * Whether the user must first be sent to the "install unknown apps"
     * settings screen. Always false below API 26, where no such toggle exists.
     */
    fun needsUnknownSourcesSettings(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !canInstall(ctx)

    /** Settings intent for the one-time unknown-sources grant. */
    fun unknownSourcesIntent(ctx: Context): Intent {
        val action = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES else Settings.ACTION_SECURITY_SETTINGS
        return Intent(action, Uri.parse("package:${ctx.packageName}"))
    }

    /**
     * Fire the installer for the staged APK. Returns false (never throws) if
     * there is no handler; we do not fall back to a chooser, which cannot fix a
     * missing installer and would obscure the real error.
     */
    fun install(ctx: Context, apk: java.io.File): Boolean {
        if (!apk.exists()) return false
        val uri = ApkProvider.contentUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkProvider.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}