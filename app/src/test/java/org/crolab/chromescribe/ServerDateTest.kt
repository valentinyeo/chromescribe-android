package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDateTest {

    @Test fun parsesSqliteAndIsoStrings() {
        assertEquals(
            ServerDate.toMillis("2024-01-02 03:04:05"),
            ServerDate.toMillis("2024-01-02T03:04:05Z")
        )
        assertEquals(
            ServerDate.toMillis("2024-01-02 03:04:05"),
            ServerDate.toMillis("2024-01-02T04:04:05+01:00")
        )
    }

    @Test fun parsesEpochSecondsAndMillis() {
        assertEquals(1_700_000_000_000L, ServerDate.toMillis("1700000000"))
        assertEquals(1_700_000_000_123L, ServerDate.toMillis("1700000000123"))
    }

    @Test fun unknownOrMissingIsZero() {
        assertEquals(0L, ServerDate.toMillis(null))
        assertEquals(0L, ServerDate.toMillis(""))
        assertEquals(0L, ServerDate.toMillis("not a date"))
        assertEquals(0L, ServerDate.toMillis("----"))
    }
}