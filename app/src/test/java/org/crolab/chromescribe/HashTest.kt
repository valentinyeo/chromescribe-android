package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Streaming-hash behaviour used for APK integrity. Ensures the digest is a
 * real SHA-256 and that the size ceiling is enforced while reading, not after.
 */
class HashTest {

    @Test fun emptyInputHasKnownSha256() {
        val (hex, bytes) = Hash.sha256Hex(ByteArrayInputStream(ByteArray(0)), 100)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
        assertEquals(0L, bytes)
    }

    @Test fun abcHasKnownSha256() {
        val (hex, bytes) = Hash.sha256Hex("abc".byteInputStream(), 100)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals(3L, bytes)
    }

    @Test fun digestSpansBufferBoundaries() {
        // Larger than the 64 KiB copy buffer so multiple reads are hashed.
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }
        val (hex, bytes) = Hash.sha256Hex(ByteArrayInputStream(data), 1_000_000)
        assertEquals(200_000L, bytes)
        assertEquals(expected, hex)
        assertEquals(64, hex.length)
    }

    @Test fun exceedingCeilingThrows() {
        val data = ByteArray(1000) { 1 }
        assertThrows(UpdateException::class.java) {
            Hash.sha256Hex(ByteArrayInputStream(data), 999)
        }
    }

    @Test fun exactlyAtCeilingIsAllowed() {
        val data = ByteArray(1000) { 1 }
        assertEquals(1000L, Hash.sha256Hex(ByteArrayInputStream(data), 1000).second)
    }
}