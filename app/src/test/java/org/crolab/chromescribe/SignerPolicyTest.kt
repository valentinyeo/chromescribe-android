package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression tests for the signer-set comparison that replaced the broken
 * API-28+ archive path. An empty set on either side must always fail (never be
 * treated as a wildcard), mismatched sets must fail, and the diagnostic must
 * say which side was empty.
 */
class SignerPolicyTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun matchingSignerSetsPass() {
        SignerPolicy.requireMatch(listOf(a), listOf(a))
        SignerPolicy.requireMatch(listOf(a, b), listOf(b, a))
    }

    @Test fun emptyArchiveSetIsRejectedWithArchiveDiagnostic() {
        val e = assertThrows(UpdateException::class.java) {
            SignerPolicy.requireMatch(emptyList(), listOf(a))
        }
        assertEquals("archive signatures missing", e.message)
    }

    @Test fun emptyInstalledSetIsRejectedWithInstalledDiagnostic() {
        val e = assertThrows(UpdateException::class.java) {
            SignerPolicy.requireMatch(listOf(a), emptyList())
        }
        assertEquals("installed signatures missing", e.message)
    }

    @Test fun bothEmptyStillFails() {
        assertThrows(UpdateException::class.java) {
            SignerPolicy.requireMatch(emptyList(), emptyList())
        }
    }

    @Test fun mismatchIsRejected() {
        val e = assertThrows(UpdateException::class.java) {
            SignerPolicy.requireMatch(listOf(a), listOf(b))
        }
        assertEquals("signer mismatch", e.message)
    }

    @Test fun extraArchiveSignerIsRejected() {
        assertThrows(UpdateException::class.java) {
            SignerPolicy.requireMatch(listOf(a, b), listOf(a))
        }
    }
}