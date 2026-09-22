package org.crolab.chromescribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    private fun render(md: String) = MarkdownRenderer.render(md)

    @Test fun escapesRawHtmlAndClosingScriptTags() {
        val html = render("</script><script>alert(1)</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;/script&gt;"))
    }

    @Test fun doesNotLinkifyJavascriptUrls() {
        val html = render("[click](javascript:alert(1))")
        assertFalse(html.contains("<a"))
        assertTrue(html.contains("javascript:alert(1)"))
    }

    @Test fun onlyHttpLinksBecomeAnchors() {
        val html = render("see https://example.com/x and http://example.com/y")
        assertTrue(html.contains("<a href=\"https://example.com/x\""))
        assertTrue(html.contains("<a href=\"http://example.com/y\""))
        assertFalse(html.contains("javascript:"))
    }

    @Test fun renderingHeadingsEmphasisAndCode() {
        val html = render("## Summary\n\n**bold** and *italic* and `code`")
        assertTrue(html.contains("<h2>Summary</h2>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<code>code</code>"))
    }

    /** ChromeScribe markdown often numbers every item "1." — render one list. */
    @Test fun repeatedOrderedMarkerRendersSequentialList() {
        val html = render("1. first\n1. second\n1. third")
        assertTrue(html.contains("<ol>"))
        assertEquals(1, Regex("<ol").findAll(html).count())
        assertEquals(3, Regex("<li>").findAll(html).count())
        assertFalse(html.contains("value="))
    }

    @Test fun orderedListContinuesAcrossBlankLines() {
        val html = render("1. first\n\n1. second\n\n1. third")
        assertEquals(1, Regex("<ol").findAll(html).count())
        assertEquals(3, Regex("<li>").findAll(html).count())
    }

    @Test fun orderedStartOnlyFromFirstMarker() {
        val html = render("3. third\n7. seventh")
        assertTrue(html.contains("<ol start=\"3\">"))
        assertFalse(html.contains("value="))
    }

    @Test fun bulletsGroupAcrossBlankLines() {
        val html = render("- one\n\n- two\n- three")
        assertEquals(1, Regex("<ul").findAll(html).count())
        assertEquals(3, Regex("<li>").findAll(html).count())
        assertTrue(html.contains("<li>one</li>"))
        assertTrue(html.contains("<li>three</li>"))
    }

    @Test fun rendersBlockquoteAndRule() {
        val html = render("> quoted\n> more\n\n---")
        assertTrue(html.contains("<blockquote>quoted<br>more</blockquote>"))
        assertTrue(html.contains("<hr>"))
    }

    @Test fun keepsDetailsAndSummaryStructure() {
        val html = render("<details>\n<summary>Transcript</summary>\n\nOriginal words here.\n\n</details>")
        assertTrue(html.contains("<details>"))
        assertTrue(html.contains("<summary>Transcript</summary>"))
        assertTrue(html.contains("Original words here."))
        assertTrue(html.contains("</details>"))
    }

    @Test fun imagesAreNotEmittedAsNetworkLoads() {
        val html = render("![alt](https://evil.example/track.png)")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("alt"))
    }

    /** A literal NUL + digits used to be able to collide with the token parser. */
    @Test fun maliciousNulAndDigitsDoNotThrowOrEscape() {
        val html = render("\u0000 0 \u0000 and `\u0000 1 \u0000` and **bold**")
        assertTrue(html.contains("<code>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("\u0000"))
    }

    @Test fun codeSpanContentIsEscapedNotExecuted() {
        val html = render("`<script>alert(1)</script>`")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("<code>&lt;script&gt;alert(1)&lt;/script&gt;</code>"))
    }

    @Test fun linkWithAmpersandAndHtmlIsEscaped() {
        val html = render("see https://x.test/a?b=1&c=2 <i>")
        assertTrue(html.contains("<a href=\"https://x.test/a?b=1&amp;c=2\""))
        assertTrue(html.contains("&lt;i&gt;"))
    }

    @Test fun plainTextIsPreservedInParagraphs() {
        assertEquals("<p>hello world</p>\n", render("hello\nworld"))
    }
}