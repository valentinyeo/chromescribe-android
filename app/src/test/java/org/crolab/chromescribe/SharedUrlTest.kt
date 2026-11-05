package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedUrlTest {

    private fun normalize(raw: String) = SharedUrl.parse(raw)?.url
    private fun extract(raw: String?) = SharedUrl.extract(raw)?.url

    private val id = "dQw4w9WgXcQ"

    @Test fun extractsFromShareTitleAndUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=$id",
            extract("Never Gonna Give You Up https://youtu.be/$id")
        )
    }

    @Test fun acceptsShortLongMobileAndLiveForms() {
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://www.youtube.com/watch?v=$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://youtube.com/watch?v=$id&t=10s"))
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://m.youtube.com/watch?v=$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://www.youtube.com/shorts/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://www.youtube.com/live/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", normalize("https://youtu.be/$id?si=abc"))
    }

    @Test fun rejectsNonHttpsAndLookalikeHosts() {
        assertNull(normalize("http://youtube.com/watch?v=$id"))
        assertEquals(SharedUrl.Kind.ARTICLE, SharedUrl.parse("https://youtube.com.evil.com/watch?v=$id")?.kind)
        assertEquals(SharedUrl.Kind.ARTICLE, SharedUrl.parse("https://notyoutube.com/watch?v=$id")?.kind)
        assertEquals(SharedUrl.Kind.ARTICLE, SharedUrl.parse("https://evil.com/?u=https://youtube.com/watch?v=$id")?.kind)
        assertNull(normalize("https://user@evil.com/watch?v=$id"))
        assertNull(normalize("https://youtube.com:8443/watch?v=$id"))
    }

    @Test fun rejectsBadIdsAndUnrelatedPaths() {
        assertNull(normalize("https://youtube.com/watch?v=short"))
        assertNull(normalize("https://youtube.com/watch"))
        assertNull(normalize("https://youtube.com/embed/$id"))
        assertNull(normalize("https://youtube.com/channel/UC123"))
        assertEquals(SharedUrl.Kind.ARTICLE, SharedUrl.parse("https://example.com/watch?v=$id")?.kind)
    }

    @Test fun rejectsBadIdShapes() {
        assertNull(normalize("https://youtu.be/${id}XXXX"))
        assertNull(normalize("https://youtu.be/$id!"))
        assertNull(normalize("https://www.youtube.com/shorts/$id/extra/ok"))
    }

    @Test fun extractReturnsNullWhenNoSupportedLink() {
        assertNull(extract("just some text"))
        assertNull(extract(null))
        assertNull(extract("see http://youtu.be/$id"))
    }

    @Test fun classifiesYoutubeAndPreservesNormalization() {
        assertEquals(SharedUrl.Kind.YOUTUBE, SharedUrl.parse("https://youtu.be/$id")?.kind)
        for (suffix in listOf(").", "]", "}")) {
            assertEquals("https://www.youtube.com/watch?v=$id",
                extract("Watch https://youtu.be/$id$suffix"))
        }
    }

    @Test fun acceptsTikTokVideoAndShortLinks() {
        for (url in listOf(
            "https://tiktok.com/@user/video/12345",
            "https://www.tiktok.com/@user.name/video/12345/?lang=en",
            "https://m.tiktok.com/@user/video/12345",
            "https://vm.tiktok.com/Zabc123/",
            "https://vt.tiktok.com/Zabc123/?share=1"
        )) {
            assertEquals(url, SharedUrl.Link(url, SharedUrl.Kind.TIKTOK), SharedUrl.parse(url))
            assertEquals(url, extract("Some caption $url more caption"))
        }
    }

    @Test fun acceptsTikTokPathShortLinksFromShareCaptions() {
        for (host in listOf("tiktok.com", "www.tiktok.com", "m.tiktok.com")) {
            for (suffix in listOf("", "/", "/?is_from_webapp=1&sender_device=pc")) {
                val url = "https://$host/t/ZP8abc123$suffix"
                assertEquals(SharedUrl.Link(url, SharedUrl.Kind.TIKTOK),
                    SharedUrl.extract("Watch this TikTok\n$url\nShared from TikTok"))
            }
        }
    }

    @Test fun rejectsMalformedTikTokPathShortLinks() {
        for (path in listOf("/t/", "/t/a-b/", "/t/abc/extra", "/t/%61bc/")) {
            assertNull(SharedUrl.extract("Watch https://www.tiktok.com$path"))
        }
    }

    @Test fun rejectsMalformedTikTokLinks() {
        for (url in listOf(
            "http://vt.tiktok.com/abc/", "https://tiktok.com/@user/video/notdigits",
            "https://tiktok.com/@user", "https://tiktok.com/@user/video/123/extra",
            "https://vt.tiktok.com/", "https://vm.tiktok.com/abc/extra"
        )) assertNull(url, SharedUrl.parse(url))
    }

    @Test fun lookalikesAreArticlesNeverVideos() {
        for (host in listOf("tiktok.com.evil.tld", "nottiktok.com", "vt.tiktok.com.evil.tld")) {
            assertEquals(SharedUrl.Kind.ARTICLE,
                SharedUrl.parse("https://$host/@user/video/123")?.kind)
        }
    }

    @Test fun acceptsArticlesWithoutChangingTheirUrls() {
        for (url in listOf(
            "https://example.org/story?x=1&y=2#section",
            "https://news.example.org:8443/article",
            "https://example.org/wiki/Example_(word)",
            "https://example.org/ends-in-a-period.",
            "HTTPS://EXAMPLE.ORG/article"
        )) assertEquals(SharedUrl.Link(url, SharedUrl.Kind.ARTICLE), SharedUrl.parse(url))
    }

    @Test fun rejectsUnsafeAndMalformedArticles() {
        for (url in listOf(
            "http://example.org/article", "ftp://example.org/article",
            listOf("https://user", "pass@example.org/a").joinToString(":"), "https://@example.org/",
            "https://127.0.0.1/a", "https://192.168.1.1/", "https://[::1]/",
            "https://[2001:db8::1]/", "https://2130706433/", "https://127.1/",
            "https://0x7f000001/", "https://0177.0.0.1/",
            "https://localhost/a", "https://sub.localhost/", "https://LOCALHOST./",
            "https://host.local/a", "https://host.internal/a", "https://host.internal./",
            "https://", "https:///story", "https://example.org:99999/",
            "https://example.org/a b", "https://example.org/%zz"
        )) assertNull(url, SharedUrl.parse(url))
    }

    @Test fun enforcesUrlLengthNotCaptionLength() {
        val url = "https://example.org/" + "a".repeat(2028)
        assertEquals(2048, url.length)
        assertEquals(url, extract("caption ".repeat(500) + url))
        assertNull(SharedUrl.parse(url + "a"))
        assertNull(extract(url + "a"))
    }

    @Test fun extractsFirstUrlRatherThanFirstValidUrl() {
        assertEquals("https://example.org/story",
            extract("Read this\nhttps://example.org/story https://vt.tiktok.com/abc/"))
        assertNull(extract("http://example.org/story https://vt.tiktok.com/abc/"))
        assertNull(extract("https://localhost/ https://example.org/story"))
        assertEquals("https://vt.tiktok.com/abc/",
            extract("Caption <https://vt.tiktok.com/abc/>"))
    }
}
