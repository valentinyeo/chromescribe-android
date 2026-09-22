package org.crolab.chromescribe

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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

        val archiveSigners = archiveSignerDigests(pm, apk)
        val installedSigners = installedSignerDigests(ctx)
        SignerPolicy.requireMatch(archiveSigners, installedSigners)

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

    /**
     * SHA-256 digests of the archive's *current* signer certificates.
     *
     * On API 28+ we read `SigningInfo.getApkContentsSigners` (the signers
     * actually in the APK), matching how the installed package's current
     * signers are read — we require the same current signer, not
     * rotation-history equality. `getPackageArchiveInfo` does not reliably
     * populate `signingInfo` for a file (observed on Android 10/API 29), so
     * when it is missing/empty we fall back to the legacy `signatures` field,
     * fetched with GET_SIGNATURES.
     */
    private fun archiveSignerDigests(pm: PackageManager, apk: File): List<String> {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            }
        } catch (e: Exception) {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = info?.let { currentSignerDigests(it) }.orEmpty()
            if (modern.isNotEmpty()) return modern
            @Suppress("DEPRECATION")
            val legacyInfo = try {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            } catch (e: Exception) {
                null
            }
            return legacySignatureDigests(legacyInfo)
        }
        return legacySignatureDigests(info)
    }

    /** Current APK signers from modern signing info, or empty if unavailable. */
    private fun currentSignerDigests(info: PackageInfo): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val si = info.signingInfo ?: return emptyList()
        val sigs = si.apkContentsSigners
        if (sigs == null || sigs.isEmpty()) return emptyList()
        return sigs.map { sha256(it.toByteArray()) }.sorted()
    }

    @Suppress("DEPRECATION")
    private fun legacySignatureDigests(info: PackageInfo?): List<String> {
        val sigs = info?.signatures ?: return emptyList()
        if (sigs.isEmpty()) return emptyList()
        return sigs.map { sha256(it.toByteArray()) }.sorted()
    }

    private fun installedSignerDigests(ctx: Context): List<String> {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = try {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (e: Exception) {
                null
            }
            val modern = info?.let { currentSignerDigests(it) }.orEmpty()
            if (modern.isNotEmpty()) return modern
        }
        // Fall back to legacy signatures for the installed package too.
        @Suppress("DEPRECATION")
        val legacyInfo = try {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            null
        }
        return legacySignatureDigests(legacyInfo)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Pure signer-set comparison. Both sets must be non-empty and identical;
 * diagnostics distinguish which side was missing. Never weakens validation
 * (an empty set is always a failure, never a wildcard).
 */
object SignerPolicy {

    fun requireMatch(archiveSigners: List<String>, installedSigners: List<String>) {
        if (archiveSigners.isEmpty()) throw UpdateException("archive signatures missing")
        if (installedSigners.isEmpty()) throw UpdateException("installed signatures missing")
        if (archiveSigners.sorted() != installedSigners.sorted()) throw UpdateException("signer mismatch")
    }
}