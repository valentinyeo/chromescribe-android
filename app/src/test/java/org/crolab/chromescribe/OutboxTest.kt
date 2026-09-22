package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun outbox() = Outbox(tmp.newFolder())

    @Test fun entryIsOnDiskBeforeAnyNetworkAndSurvivesRestart() {
        val dir = tmp.newFolder()
        Outbox(dir).add("https://www.youtube.com/watch?v=a")
        // New instance simulates process death.
        val pending = Outbox(dir).pending()
        assertEquals(1, pending.size)
        assertEquals("https://www.youtube.com/watch?v=a", pending[0].url)
    }

    @Test fun inFlightSendingEntryIsRetriedAfterRestart() {
        val dir = tmp.newFolder()
        val entry = Outbox(dir).add("https://www.youtube.com/watch?v=b")
        Outbox(dir).update(entry.id, OutboxState.SENDING, "sending")
        val recovered = Outbox(dir).pending().single()
        assertEquals(OutboxState.SENDING, recovered.state)
    }

    @Test fun confirmedEntryIsRemoved() {
        val dir = tmp.newFolder()
        val entry = Outbox(dir).add("https://www.youtube.com/watch?v=c")
        Outbox(dir).remove(entry.id)
        assertTrue(Outbox(dir).pending().isEmpty())
    }

    @Test fun duplicateUrlDoesNotQueueTwice() {
        val dir = tmp.newFolder()
        val a = Outbox(dir).add("https://www.youtube.com/watch?v=d")
        val b = Outbox(dir).add("https://www.youtube.com/watch?v=d")
        assertEquals(a.id, b.id)
        assertEquals(1, Outbox(dir).pending().size)
    }

    @Test fun corruptionIsReportedAndNotOverwritten() {
        val dir = tmp.newFolder()
        val file = java.io.File(dir, "outbox.tsv")
        file.writeText("this line is not a valid entry")
        val ob = Outbox(dir)
        try {
            ob.all()
            throw AssertionError("expected OutboxCorruptException")
        } catch (expected: Outbox.OutboxCorruptException) {
        }
        // The bad data must still be there — no silent wipe.
        assertEquals("this line is not a valid entry", file.readText())
    }

    @Test fun boundedSizeRefusesNewEntries() {
        val dir = tmp.newFolder()
        val ob = Outbox(dir)
        for (i in 0 until Outbox.MAX_ENTRIES) {
            ob.add("https://www.youtube.com/watch?v=$i")
        }
        try {
            ob.add("https://www.youtube.com/watch?v=overflow")
            throw AssertionError("expected OutboxFullException")
        } catch (expected: Outbox.OutboxFullException) {
        }
        assertEquals(Outbox.MAX_ENTRIES, Outbox(dir).pending().size)
    }

    @Test fun retainsFailedEntryWithMessage() {
        val dir = tmp.newFolder()
        val entry = Outbox(dir).add("https://www.youtube.com/watch?v=e")
        Outbox(dir).update(entry.id, OutboxState.FAILED, "network error")
        val recovered = Outbox(dir).pending().single()
        assertEquals(OutboxState.FAILED, recovered.state)
        assertEquals("network error", recovered.message)
        assertEquals(1, recovered.attempts)
    }
}