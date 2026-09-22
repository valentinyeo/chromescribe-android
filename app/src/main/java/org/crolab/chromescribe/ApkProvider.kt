package org.crolab.chromescribe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves exactly one file: the verified, staged update APK.
 *
 * This exists because modern Android must be handed a content:// URI to launch
 * the package installer (file:// is blocked). It is deliberately as small and
 * as locked-down as possible:
 *
 * - not exported; the installer reaches it only through the one-shot
 *   `FLAG_GRANT_READ_URI_PERMISSION` grant attached to the install intent,
 *   never through general access,
 * - only the single well-known name `pending.apk` is resolvable; no path is
 *   ever taken from the caller,
 * - read-only; every mutating call is rejected.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun apkFile(): File? {
        val ctx = context ?: return null
        return ApkDownloader.stagedApk(ctx)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        if (uri.lastPathSegment != FILE_NAME) throw FileNotFoundException("unknown file")
        val f = apkFile() ?: throw FileNotFoundException("no staged apk")
        @Suppress("DEPRECATION")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment == FILE_NAME) APK_MIME else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment != FILE_NAME) return null
        val f = apkFile() ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (c in cols) when (c) {
            OpenableColumns.DISPLAY_NAME -> row.add(FILE_NAME)
            OpenableColumns.SIZE -> row.add(f.length())
            else -> row.add(null)
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "org.crolab.chromescribe.apk"
        const val FILE_NAME = "pending.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        fun contentUri(): Uri = Uri.parse("content://$AUTHORITY/$FILE_NAME")
    }
}