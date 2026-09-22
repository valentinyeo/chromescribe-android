package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class TranscriptStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun meta(id: String, title: String = "Title $id", created: Long = 0L) =
        TranscriptMeta(id, title, "Channel", created)

    @Test fun contentSurvivesRestartAndIsReadableOffline() {
        val dir = tmp.newFolder()
        TranscriptStore(dir).saveContent(meta("42"), "# Hello")
        val reopened = TranscriptStore(dir)
        assertEquals("# Hello", reopened.content("42"))
        assertTrue(reopened.has("42"))
    }

    @Test fun savedListsOnlyEntriesWithRealContent() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        store.saveIndex(listOf(meta("1"), meta("2"))) // metadata only
        assertTrue(store.saved().isEmpty())
        store.saveContent(meta("2"), "body")
        assertEquals(listOf("2"), store.saved().map { it.id })
    }

    @Test fun removeDropsContentAndMetadata() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        store.saveContent(meta("7"), "body")
        store.remove("7")
        assertNull(store.content("7"))
        assertFalse(store.has("7"))
        assertTrue(store.saved().isEmpty())
    }

    @Test fun unsafeIdsAreRejectedWithoutPathTraversal() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        assertFalse(store.saveContent(meta("../escape"), "x"))
        assertNull(store.content("../escape"))
        assertFalse(java.io.File(tmp.root, "escape").exists())
    }

    @Test fun oversizedContentIsRejectedAndOldCopyPreserved() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        assertTrue(store.saveContent(meta("9"), "good"))
        val huge = "x".repeat(TranscriptStore.MAX_CONTENT_CHARS + 1)
        assertFalse(store.saveContent(meta("9", title = "new"), huge))
        assertEquals("good", store.content("9"))
        assertEquals("Title 9", store.saved().single().title)
    }

    @Test fun resavingReplacesSnapshotAndKeepsOrder() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        store.saveContent(meta("1", "one", 10), "v1")
        store.saveContent(meta("2", "two", 20), "v2")
        store.saveContent(meta("1", "one revised", 10), "v1 revised")
        assertEquals("v1 revised", store.content("1"))
        assertEquals(2, store.saved().size)
        assertEquals("two", store.saved().first().title)
    }

    @Test fun olderSavedMetadataSurvivesTheServersLatestWindow() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        store.saveContent(meta("old", "Old title", 10), "old body")
        store.saveIndex(listOf(meta("new", "New title", 20)))
        val saved = store.saved().associateBy { it.id }
        assertEquals("Old title", saved["old"]?.title)
        assertEquals("old body", store.content("old"))
        assertEquals("New title", store.index().first { it.id == "new" }.title)
    }

    @Test fun serverEntryRefreshesSavedTitle() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        store.saveContent(meta("1", "Old", 10), "body")
        store.saveIndex(listOf(meta("1", "New", 10)))
        assertEquals("New", store.saved().single().title)
    }

    @Test fun corruptIndexIsReported() {
        val dir = tmp.newFolder()
        java.io.File(dir, "index.tsv").writeText("not a valid index row")
        assertThrows(IOException::class.java) { TranscriptStore(dir).index() }
    }

    @Test fun savedListStillWorksWithCorruptIndex() {
        val dir = tmp.newFolder()
        TranscriptStore(dir).saveContent(meta("1", "Title", 1), "body")
        java.io.File(dir, "index.tsv").writeText("garbage")
        val saved = TranscriptStore(dir).saved().single()
        assertEquals("1", saved.id)
        assertEquals("1", saved.title) // falls back to id instead of crashing
    }

    @Test fun savedFilesAreBoundedOnDisk() {
        val dir = tmp.newFolder()
        val store = TranscriptStore(dir)
        for (i in 0 until TranscriptStore.MAX_SAVED + 5) {
            store.saveContent(meta("$i", "T$i", i.toLong()), "body$i")
        }
        assertTrue(store.saved().size <= TranscriptStore.MAX_SAVED)
        assertTrue(store.content("${TranscriptStore.MAX_SAVED + 4}") != null)
    }
}