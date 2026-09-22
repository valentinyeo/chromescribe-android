package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for `/api/queue` acknowledgements. A malformed or incomplete
 * envelope must NOT be treated as accepted, or the durable outbox would be
 * cleared without the server having queued anything.
 */
class QueueResponseTest {

    @Test fun queuedTrueWithNumericIdIsAcknowledged() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"id":42}}""")
        assertTrue(r is Api.QueueResult.Queued)
        assertEquals("42", (r as Api.QueueResult.Queued).id)
    }

    @Test fun stringNumericIdIsAccepted() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"id":"7"}}""")
        assertEquals("7", (r as Api.QueueResult.Queued).id)
    }

    @Test fun duplicateRequiresIdAndIsAcknowledged() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"duplicate":true,"id":9}}""")
        assertTrue(r is Api.QueueResult.Duplicate)
    }

    @Test fun alreadyTranscribedRequiresTranscriptId() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":false,"transcript_id":3224}}""")
        assertTrue(r is Api.QueueResult.AlreadyTranscribed)
        assertEquals("3224", (r as Api.QueueResult.AlreadyTranscribed).transcriptId)
    }

    @Test fun emptyDataIsRejectedNotTreatedAsDuplicate() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{}}""")
        }
    }

    @Test fun queuedTrueWithoutIdIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{"queued":true}}""")
        }
    }

    @Test fun queuedFalseWithoutTranscriptIdIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{"queued":false}}""")
        }
    }

    @Test fun missingDataIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true}""")
        }
    }

    @Test fun successFalseIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":false,"error":"nope"}""")
        }
    }

    @Test fun nonBooleanQueuedIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{"queued":"true","id":1}}""")
        }
    }

    @Test fun booleanOrFractionalIdIsRejected() {
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"id":true}}""")
        }
        assertThrows(ApiException::class.java) {
            Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"id":1.5}}""")
        }
    }

    @Test fun integralDoubleIdNormalizesToIntegerString() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":true,"id":1.0}}""")
        assertEquals("1", (r as Api.QueueResult.Queued).id)
    }

    @Test fun stringIdNormalizesLeadingZeros() {
        val r = Api.parseQueueResponse("""{"success":true,"data":{"queued":false,"transcript_id":"007"}}""")
        assertEquals("7", (r as Api.QueueResult.AlreadyTranscribed).transcriptId)
    }

    @Test fun zeroNegativeOutOfRangeAndNonFiniteIdsAreRejected() {
        for (body in listOf(
            """{"success":true,"data":{"queued":true,"id":0}}""",
            """{"success":true,"data":{"queued":true,"id":-5}}""",
            """{"success":true,"data":{"queued":true,"id":99999999999999}}""",
            """{"success":true,"data":{"queued":false,"transcript_id":"0"}}""",
            """{"success":true,"data":{"queued":true,"id":1e309}}"""
        )) {
            assertThrows(ApiException::class.java) { Api.parseQueueResponse(body) }
        }
    }
}