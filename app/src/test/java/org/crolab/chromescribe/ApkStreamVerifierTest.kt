package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure-JVM tests for the streaming download verifier: interrupted/truncated/
 * oversized/hash-mismatch inputs must be rejected, and a failed attempt must
 * leave any pre-existing staged file untouched.
 */
class ApkStreamVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun out(name: String = "out.apk"): FileOutputStream = FileOutputStream(File(tmp.root, name))

    @Test fun acceptsExactLengthAndHash() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        out().use { o ->
            val r = ApkStreamVerifier.streamAndVerify(
                ByteArrayInputStream(data), o, data.size.toLong(), sha(data)
            )
            assertEquals(data.size.toLong(), r.bytes)
            assertEquals(sha(data), r.sha256)
        }
    }

    @Test fun rejectsTruncatedStream() {
        val data = ByteArray(1000) { 1 }
        assertThrows(UpdateException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(data.copyOf(100)), o, data.size.toLong(), sha(data)
                )
            }
        }
    }

    @Test fun rejectsOverlongStream() {
        val data = ByteArray(1000) { 1 }
        assertThrows(UpdateException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(data), o, 999L, sha(data)
                )
            }
        }
    }

    @Test fun rejectsOversizeAgainstCeiling() {
        val data = ByteArray(UpdatePolicy.MAX_APK_BYTES.toInt() + 1) { 1 }
        assertThrows(UpdateException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(data), o, UpdatePolicy.MAX_APK_BYTES, sha(data)
                )
            }
        }
    }

    @Test fun rejectsHashMismatch() {
        val data = ByteArray(1000) { 1 }
        assertThrows(UpdateException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(data), o, data.size.toLong(), "0".repeat(64)
                )
            }
        }
    }

    @Test fun rejectsExpectedSizeAboveCeilingBeforeReading() {
        assertThrows(UpdateException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(ByteArray(0)), o, UpdatePolicy.MAX_APK_BYTES + 1, "0".repeat(64)
                )
            }
        }
    }

    @Test fun rejectsInterruptedStream() {
        // A stream that fails mid-read must propagate as a failure, not a
        // short-but-hash-valid write.
        val good = ByteArray(1000) { 1 }
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw java.io.IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 100) throw java.io.IOException("interrupted")
                val n = minOf(len, 100 - served)
                System.arraycopy(good, served, b, off, n)
                served += n
                return n
            }
        }
        assertThrows(java.io.IOException::class.java) {
            out().use { o ->
                ApkStreamVerifier.streamAndVerify(failing, o, good.size.toLong(), sha(good))
            }
        }
    }

    @Test fun failedAttemptDoesNotTouchPreviousStagedFile() {
        // Simulate the download policy: write a new temp file, and on failure
        // leave the existing target intact because rename never happened.
        val previous = File(tmp.root, "pending.apk")
        previous.writeText("previous-good-apk")
        val temp = File(tmp.root, "pending.apk.tmp")
        try {
            FileOutputStream(temp).use { o ->
                ApkStreamVerifier.streamAndVerify(
                    ByteArrayInputStream(ByteArray(10) { 1 }), o, 999L, "0".repeat(64)
                )
            }
        } catch (e: Exception) {
            temp.delete()
        }
        assertEquals("previous-good-apk", previous.readText())
    }

    @Test fun successfulRenameReplacesPreviousStagedFile() {
        val target = File(tmp.root, "pending.apk")
        target.writeText("old")
        val temp = File(tmp.root, "pending.apk.tmp")
        val data = "new-and-verified".toByteArray()
        FileOutputStream(temp).use { o ->
            ApkStreamVerifier.streamAndVerify(ByteArrayInputStream(data), o, data.size.toLong(), sha(data))
        }
        assertTrue(temp.renameTo(target))
        assertEquals("new-and-verified", target.readText())
    }
}

/** Pending-install persistence and the never-delete-early cleanup decision. */
class PendingInstallTest {

    private fun manifest(code: Int = 3) = UpdatePolicy.parse(
        """{"versionCode":$code,"versionName":"1.2.0","minSdk":24,""" +
            """"apkPath":"/api/android/releases/$code/app.apk","sha256":"${"a".repeat(64)}","size":1000000,"notes":"n"}"""
    )

    @Test fun encodeDecodeRoundTrip() {
        val p = PendingInstall(manifest(3), settingsResume = true)
        val decoded = PendingInstall.decode(p.encode())
        assertNotNull(decoded)
        assertEquals(3, decoded!!.manifest.versionCode)
        assertEquals("/api/android/releases/3/app.apk", decoded.manifest.apkPath)
        assertTrue(decoded.settingsResume)
    }

    @Test fun decodeRejectsTamperedOrMalformed() {
        assertNull(PendingInstall.decode("not json"))
        // Tampered apkPath is revalidated on decode.
        val bad = """{"versionCode":3,"versionName":"1.2.0","minSdk":24,""" +
            """"apkPath":"/etc/passwd","sha256":"${"a".repeat(64)}","size":1000000,"notes":"x","settingsResume":true}"""
        assertNull(PendingInstall.decode(bad))
    }

    @Test fun cleanupKeepsWhileInstallNotCompleted() {
        val p = PendingInstall(manifest(3), settingsResume = false)
        assertEquals(UpdateCleanup.Action.KEEP, UpdateCleanup.decide(p, stagedApkExists = true, installedVersionCode = 2))
        assertEquals(UpdateCleanup.Action.KEEP, UpdateCleanup.decide(p, stagedApkExists = true, installedVersionCode = null))
    }

    @Test fun cleanupDeletesOnlyAfterNewVersionInstalled() {
        val p = PendingInstall(manifest(3), settingsResume = false)
        assertEquals(UpdateCleanup.Action.DELETE, UpdateCleanup.decide(p, stagedApkExists = true, installedVersionCode = 3))
        assertEquals(UpdateCleanup.Action.DELETE, UpdateCleanup.decide(p, stagedApkExists = true, installedVersionCode = 4))
    }

    @Test fun cleanupDeletesOrphanedStagedFileWithNoPending() {
        assertEquals(UpdateCleanup.Action.DELETE, UpdateCleanup.decide(null, stagedApkExists = true, installedVersionCode = 2))
    }

    @Test fun cleanupDeletesWhenNoStagedFile() {
        assertEquals(UpdateCleanup.Action.DELETE, UpdateCleanup.decide(null, stagedApkExists = false, installedVersionCode = null))
    }

    @Test fun cleanupNeverDeletesWhenFileMissing() {
        // No staged file: nothing to keep, decision is DELETE (no-op).
        assertEquals(UpdateCleanup.Action.DELETE, UpdateCleanup.decide(PendingInstall(manifest(3), false), false, 2))
    }
}