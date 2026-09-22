package org.crolab.chromescribe

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * On-device APK inspection. Everything here uses the platform [PackageManager],
 * so it is build-verified but must be device-verified before being claimed
 * working. Every failure is fail-closed: the staged APK is not offered for
 * install unless package name, signature, version and minSdk all match
 * expectations derived from the currently installed app.
 */
object ApkVerifier {

    data class Verified(val versionCode: Long, val versionName: String)

    fun verify(ctx: Context, apk: File, manifest: UpdateManifest): Verified {
        if (!apk.exists() || apk.length() <= 0) throw UpdateException("apk missing")
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archive: PackageInfo = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
                ?: throw UpdateException("cannot read apk")
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("cannot read apk: ${e.message}")
        }

        if (archive.packageName != ctx.packageName) throw UpdateException("apk package mismatch")
        val archiveCode = versionCodeOf(archive)
        if (archiveCode != manifest.versionCode.toLong()) throw UpdateException("apk version mismatch")
        // Fail closed if the installed version cannot be read: never assume a
        // value that could allow a downgrade or reinstall of an older build.
        val installed = installedVersionCode(ctx) ?: throw UpdateException("cannot read installed version")
        if (archiveCode <= installed) throw UpdateException("apk not newer")

        // minSdk must be present and compatible; a missing value is rejected.
        val minSdk = archive.applicationInfo?.minSdkVersion ?: throw UpdateException("apk minSdk unknown")
        if (minSdk < 1) throw UpdateException("apk minSdk invalid")
        if (minSdk > Build.VERSION.SDK_INT) throw UpdateException("apk requires newer Android")
        if (manifest.minSdk != minSdk) throw UpdateException("apk minSdk mismatch")

        val archiveSigners = signerDigests(archive)
        val installedSigners = installedSignerDigests(ctx)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) throw UpdateException("no signatures")
        if (archiveSigners != installedSigners) throw UpdateException("signer mismatch")

        return Verified(archiveCode, archive.versionName.orEmpty())
    }

    private fun installedVersionCode(ctx: Context): Long? = try {
        versionCodeOf(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /** SHA-256 digests of every signer certificate, sorted, so sets compare. */
    private fun signerDigests(info: PackageInfo): List<String> {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures ?: return emptyList()
        }
        return sigs.map { sha256(it.toByteArray()) }.sorted()
    }

    private fun installedSignerDigests(ctx: Context): List<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        } catch (e: Exception) {
            return emptyList()
        }
        return signerDigests(info)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}