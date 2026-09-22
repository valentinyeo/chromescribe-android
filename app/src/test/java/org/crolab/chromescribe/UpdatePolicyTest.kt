package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Security-critical validation for the self-updater: the manifest parser, the
 * apkPath allow-list, integrity/size bounds, newer-version rules and the daily
 * schedule. All pure JVM so every rejection rule is enforced by a test.
 */
class UpdatePolicyTest {

    private fun manifest(
        code: Int = 3,
        name: String = "1.2.0",
        minSdk: Int = 24,
        path: String = "/api/android/releases/3/app.apk",
        sha: String = "a".repeat(64),
        size: Long = 1_000_000L,
        notes: String = "notes",
    ) = """{"versionCode":$code,"versionName":"$name","minSdk":$minSdk,""" +
        """"apkPath":"$path","sha256":"$sha","size":$size,"notes":"$notes"}"""

    // --- happy path ---------------------------------------------------------

    @Test fun parsesValidManifest() {
        val m = UpdatePolicy.parse(manifest())
        assertEquals(3, m.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals(24, m.minSdk)
        assertEquals("/api/android/releases/3/app.apk", m.apkPath)
        assertEquals("a".repeat(64), m.sha256)
        assertEquals(1_000_000L, m.size)
        assertEquals("notes", m.notes)
    }

    @Test fun uppercaseHashIsLowercased() {
        val m = UpdatePolicy.parse(manifest(sha = "AB".repeat(32)))
        assertEquals("ab".repeat(32), m.sha256)
    }

    // --- apkPath allow-list -------------------------------------------------

    @Test fun rejectsArbitraryApkPaths() {
        val bad = listOf(
            "/api/android/latest",
            "/api/android/releases/3/other.apk",
            "/api/android/releases/../../etc/app.apk",
            "/api/android/releases/3/app.apk/../../secret",
            "/api/android/releases/0/app.apk",
            "/api/android/releases/-1/app.apk",
            "/api/android/releases/3/app.apk?x=1",
            "https://evil.example/app.apk",
            "//evil.example/app.apk",
            "\\api\\android\\releases\\3\\app.apk",
            "/api/android/releases/3/app.apk\n",
            "/api/android/releases/12345678901/app.apk",
        )
        for (p in bad) {
            assertFalse("should reject $p", UpdatePolicy.isAllowedApkPath(p))
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(path = p)) }
        }
    }

    @Test fun acceptsOnlyCanonicalApkPath() {
        assertTrue(UpdatePolicy.isAllowedApkPath("/api/android/releases/1/app.apk"))
        assertTrue(UpdatePolicy.isAllowedApkPath("/api/android/releases/42/app.apk"))
        assertTrue(UpdatePolicy.isAllowedApkPath("/api/android/releases/9999999999/app.apk"))
        assertNull(UpdatePolicy.pathVersionCode("/api/android/releases/x/app.apk"))
        assertEquals(42, UpdatePolicy.pathVersionCode("/api/android/releases/42/app.apk"))
    }

    @Test fun rejectsPathVersionMismatch() {
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(manifest(code = 3, path = "/api/android/releases/4/app.apk"))
        }
    }

    // --- field validation ---------------------------------------------------

    @Test fun rejectsBadVersionCode() {
        for (body in listOf(
            """{"versionName":"1.2.0","minSdk":24,"apkPath":"/api/android/releases/3/app.apk","sha256":"${"a".repeat(64)}","size":1000000}""",
            manifest(code = 0),
            manifest(code = -1),
        )) {
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(body) }
        }
    }

    @Test fun rejectsBadVersionName() {
        for (name in listOf("", "v1.2", "1.2.0-beta", "abc", "1.2.3.4.5")) {
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(name = name)) }
        }
    }

    @Test fun rejectsBadMinSdk() {
        assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(minSdk = 0)) }
        assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(minSdk = -1)) }
    }

    @Test fun rejectsNonSha256() {
        for (sha in listOf("", "abc", "g".repeat(64), "a".repeat(63), "a".repeat(65))) {
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(sha = sha)) }
        }
    }

    @Test fun rejectsBadSizes() {
        for (size in listOf(0L, -1L, UpdatePolicy.MIN_APK_BYTES - 1, UpdatePolicy.MAX_APK_BYTES + 1)) {
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(manifest(size = size)) }
        }
    }

    @Test fun acceptsBoundarySizes() {
        assertEquals(UpdatePolicy.MIN_APK_BYTES, UpdatePolicy.parse(manifest(size = UpdatePolicy.MIN_APK_BYTES)).size)
        assertEquals(UpdatePolicy.MAX_APK_BYTES, UpdatePolicy.parse(manifest(size = UpdatePolicy.MAX_APK_BYTES)).size)
    }

    @Test fun rejectsMalformedJson() {
        for (body in listOf("", "not json", "[]", "null")) {
            assertThrows(UpdateException::class.java) { UpdatePolicy.parse(body) }
        }
    }

    @Test fun rejectsNonIntegralOrCoercedVersionCode() {
        val sha = "a".repeat(64)
        for (code in listOf("3.0", "3.5", "true", "false", "\"3\"", "null", "3e0", "1e9")) {
            val body =
                "{\"versionCode\":$code,\"versionName\":\"1.2.0\",\"minSdk\":24," +
                "\"apkPath\":\"/api/android/releases/3/app.apk\",\"sha256\":\"$sha\",\"size\":1000000}"
            assertThrows("versionCode=$code should be rejected", UpdateException::class.java) {
                UpdatePolicy.parse(body)
            }
        }
    }

    @Test fun rejectsNonIntegralOrCoercedMinSdkAndSize() {
        val sha = "a".repeat(64)
        for (v in listOf("24.0", "24.5", "true", "\"24\"", "null")) {
            val minSdkBody =
                "{\"versionCode\":3,\"versionName\":\"1.2.0\",\"minSdk\":$v," +
                "\"apkPath\":\"/api/android/releases/3/app.apk\",\"sha256\":\"$sha\",\"size\":1000000}"
            assertThrows("minSdk=$v should be rejected", UpdateException::class.java) {
                UpdatePolicy.parse(minSdkBody)
            }
            val sizeBody =
                "{\"versionCode\":3,\"versionName\":\"1.2.0\",\"minSdk\":24," +
                "\"apkPath\":\"/api/android/releases/3/app.apk\",\"sha256\":\"$sha\",\"size\":$v}"
            assertThrows("size=$v should be rejected", UpdateException::class.java) {
                UpdatePolicy.parse(sizeBody)
            }
        }
    }

    @Test fun rejectsNonStringVersionNameApkPathAndSha() {
        val sha = "a".repeat(64)
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(
                "{\"versionCode\":3,\"versionName\":1.2,\"minSdk\":24," +
                "\"apkPath\":\"/api/android/releases/3/app.apk\",\"sha256\":\"$sha\",\"size\":1000000}"
            )
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(
                "{\"versionCode\":3,\"versionName\":\"1.2.0\",\"minSdk\":24," +
                "\"apkPath\":123,\"sha256\":\"$sha\",\"size\":1000000}"
            )
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(
                "{\"versionCode\":3,\"versionName\":\"1.2.0\",\"minSdk\":24," +
                "\"apkPath\":\"/api/android/releases/3/app.apk\",\"sha256\":true,\"size\":1000000}"
            )
        }
    }

    @Test fun notesAreTruncatedNotRejected() {
        val huge = "x".repeat(10_000)
        assertEquals(4000, UpdatePolicy.parse(manifest(notes = huge)).notes.length)
    }

    // --- newer rules --------------------------------------------------------

    @Test fun newerRequiresHigherCode() {
        val m = UpdatePolicy.parse(manifest(code = 3))
        assertTrue(UpdatePolicy.isNewer(m, 2, 24))
        assertFalse(UpdatePolicy.isNewer(m, 3, 24))
        assertFalse(UpdatePolicy.isNewer(m, 4, 24))
    }

    @Test fun newerIsFalseWhenMinSdkTooNew() {
        val m = UpdatePolicy.parse(manifest(code = 3, minSdk = 30))
        assertFalse(UpdatePolicy.isNewer(m, 2, 29))
        assertTrue(UpdatePolicy.isNewer(m, 2, 30))
    }

    // --- hash comparison ----------------------------------------------------

    @Test fun hashMatchesOnlyExactEqual() {
        val h = "ab".repeat(32)
        assertTrue(UpdatePolicy.hashMatches(h, h))
        assertTrue(UpdatePolicy.hashMatches(h.uppercase(), h))
        assertFalse(UpdatePolicy.hashMatches(h, "ab".repeat(31) + "cd"))
        assertFalse(UpdatePolicy.hashMatches("", ""))
        assertFalse(UpdatePolicy.hashMatches(h, h.dropLast(1)))
    }

    // --- daily schedule -----------------------------------------------------

    @Test fun scheduleDueWhenNeverChecked() {
        assertTrue(UpdateSchedule.due(0L, 1_000L))
    }

    @Test fun scheduleRespectsDailyInterval() {
        val now = 1_000_000_000L
        assertFalse(UpdateSchedule.due(now - 1000, now))
        assertFalse(UpdateSchedule.due(now - UpdateSchedule.INTERVAL_MS + 1, now))
        assertTrue(UpdateSchedule.due(now - UpdateSchedule.INTERVAL_MS, now))
        assertTrue(UpdateSchedule.due(now - UpdateSchedule.INTERVAL_MS - 1, now))
    }

    @Test fun scheduleAllowsCheckWhenClockMovesBackwards() {
        assertTrue(UpdateSchedule.due(2_000L, 1_000L))
    }
}