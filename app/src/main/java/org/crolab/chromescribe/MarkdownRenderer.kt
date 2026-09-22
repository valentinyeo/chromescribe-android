package org.crolab.chromescribe

/**
 * Small, safe, dependency-free markdown subset renderer. Pure JVM so the
 * escaping rules are unit tested.
 *
 * Security model: raw HTML is escaped first, so no transcript content can emit
 * a tag. Only http(s) URLs become links; `javascript:` and other schemes are
 * never linkified, and images are left as plain text (no network loads).
 * The only HTML we re-introduce is a fixed set of structural tags we generate.
 */
object MarkdownRenderer {

    private val UL = Regex("^\\s*[-*+]\\s+(.*)$")
    private val OL = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val HR = Regex("^\\s*([-*_])\\1{2,}\\s*$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val AUTOLINK = Regex("""https?://[^\s<>"']+""")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<!\\*)\\*(?!\\*)(.+?)\\*(?!\\*)")

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() -> i++
                HR.matches(trimmed) -> { out.append("<hr>\n"); i++ }
                trimmed == "<details>" -> { out.append("<details>\n"); i++ }
                trimmed == "</details>" -> { out.append("</details>\n"); i++ }
                trimmed.startsWith("<summary>") && trimmed.endsWith("</summary>") -> {
                    val inner = trimmed.removePrefix("<summary>").removeSuffix("</summary>")
                    out.append("<summary>").append(inline(inner)).append("</summary>\n")
                    i++
                }
                HEADING.matches(trimmed) -> {
                    val m = HEADING.find(trimmed)!!
                    val level = m.groupValues[1].length
                    out.append("<h$level>").append(inline(m.groupValues[2])).append("</h$level>\n")
                    i++
                }
                QUOTE.matches(trimmed) -> {
                    out.append("<blockquote>")
                    var first = true
                    while (i < lines.size && QUOTE.matches(lines[i].trim())) {
                        if (!first) out.append("<br>")
                        out.append(inline(QUOTE.find(lines[i].trim())!!.groupValues[1]))
                        first = false
                        i++
                    }
                    out.append("</blockquote>\n")
                }
                UL.matches(trimmed) -> {
                    out.append("<ul>")
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (UL.matches(t)) {
                            out.append("<li>").append(inline(UL.find(t)!!.groupValues[1])).append("</li>")
                            i++
                        } else if (t.isEmpty() && nextListLine(lines, i + 1, UL)) {
                            i++
                        } else break
                    }
                    out.append("</ul>\n")
                }
                OL.matches(trimmed) -> {
                    // Source markdown often numbers every item "1."; render the
                    // list once and let HTML number sequentially. Only the first
                    // marker can move the start.
                    val start = OL.find(trimmed)!!.groupValues[1].toIntOrNull() ?: 1
                    out.append(if (start == 1) "<ol>" else "<ol start=\"$start\">")
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (OL.matches(t)) {
                            out.append("<li>").append(inline(OL.find(t)!!.groupValues[2])).append("</li>")
                            i++
                        } else if (t.isEmpty() && nextListLine(lines, i + 1, OL)) {
                            i++
                        } else break
                    }
                    out.append("</ol>\n")
                }
                else -> {
                    val para = StringBuilder()
                    while (i < lines.size && !isBlockStart(lines[i].trim())) {
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(lines[i].trim())
                        i++
                    }
                    out.append("<p>").append(inline(para.toString())).append("</p>\n")
                }
            }
        }
        return out.toString()
    }

    private fun nextListLine(lines: List<String>, from: Int, regex: Regex): Boolean {
        var j = from
        while (j < lines.size && lines[j].trim().isEmpty()) j++
        return j < lines.size && regex.matches(lines[j].trim())
    }

    private fun isBlockStart(t: String): Boolean =
        t.isEmpty() || HR.matches(t) || HEADING.matches(t) || QUOTE.matches(t) ||
            UL.matches(t) || OL.matches(t) || t == "<details>" || t == "</details>" ||
            (t.startsWith("<summary>") && t.endsWith("</summary>"))

    /**
     * Escape, then walk code spans explicitly (no sentinel indices, so untrusted
     * NUL/digit text cannot collide with a placeholder), applying link/emphasis
     * only to the text outside code spans.
     */
    fun inline(text: String): String {
        val escaped = escape(text)
        val out = StringBuilder()
        var i = 0
        while (i < escaped.length) {
            val tick = escaped.indexOf('`', i)
            if (tick < 0) {
                out.append(emphasize(linkify(escaped.substring(i))))
                break
            }
            val end = escaped.indexOf('`', tick + 1)
            if (end < 0) {
                out.append(emphasize(linkify(escaped.substring(i))))
                break
            }
            if (tick > i) out.append(emphasize(linkify(escaped.substring(i, tick))))
            out.append("<code>").append(escaped.substring(tick + 1, end)).append("</code>")
            i = end + 1
        }
        return out.toString()
    }

    private fun linkify(s: String): String = AUTOLINK.replace(s) { m ->
        val url = m.value
        "<a href=\"$url\" rel=\"noreferrer noopener\">$url</a>"
    }

    private fun emphasize(s: String): String =
        ITALIC.replace(BOLD.replace(s, "<strong>$1</strong>"), "<em>$1</em>")

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}