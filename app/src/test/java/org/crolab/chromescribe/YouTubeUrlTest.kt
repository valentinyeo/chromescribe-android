package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {

    private val id = "dQw4w9WgXcQ"

    @Test fun extractsFromShareTitleAndUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=$id",
            YouTubeUrl.extract("Never Gonna Give You Up https://youtu.be/$id")
        )
    }

    @Test fun acceptsShortLongMobileAndLiveForms() {
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://www.youtube.com/watch?v=$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://youtube.com/watch?v=$id&t=10s"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://m.youtube.com/watch?v=$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://www.youtube.com/shorts/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://www.youtube.com/live/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://youtu.be/$id?si=abc"))
    }

    @Test fun rejectsNonHttpsAndLookalikeHosts() {
        assertNull(YouTubeUrl.normalize("http://youtube.com/watch?v=$id"))
        assertNull(YouTubeUrl.normalize("https://youtube.com.evil.com/watch?v=$id"))
        assertNull(YouTubeUrl.normalize("https://notyoutube.com/watch?v=$id"))
        assertNull(YouTubeUrl.normalize("https://evil.com/?u=https://youtube.com/watch?v=$id"))
        assertNull(YouTubeUrl.normalize("https://user@evil.com/watch?v=$id"))
        assertNull(YouTubeUrl.normalize("https://youtube.com:8443/watch?v=$id"))
    }

    @Test fun rejectsBadIdsAndUnrelatedPaths() {
        assertNull(YouTubeUrl.normalize("https://youtube.com/watch?v=short"))
        assertNull(YouTubeUrl.normalize("https://youtube.com/watch"))
        assertNull(YouTubeUrl.normalize("https://youtube.com/embed/$id"))
        assertNull(YouTubeUrl.normalize("https://youtube.com/channel/UC123"))
        assertNull(YouTubeUrl.normalize("https://example.com/watch?v=$id"))
    }

    @Test fun rejectsBadIdShapes() {
        assertNull(YouTubeUrl.normalize("https://youtu.be/${id}XXXX"))
        assertNull(YouTubeUrl.normalize("https://youtu.be/$id!"))
        assertNull(YouTubeUrl.normalize("https://www.youtube.com/shorts/$id/extra/ok"))
    }

    @Test fun extractReturnsNullWhenNoYoutubeLink() {
        assertNull(YouTubeUrl.extract("just some text"))
        assertNull(YouTubeUrl.extract(null))
        assertNull(YouTubeUrl.extract("see http://youtu.be/$id"))
    }
}