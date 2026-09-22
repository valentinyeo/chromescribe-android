package org.crolab.chromescribe

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Server timestamps arrive as ISO/SQLite strings (e.g. `2024-01-02 03:04:05`),
 * not epoch longs. Pure JVM so the parsing is unit tested. Returns 0 when the
 * value is missing or unrecognised; callers then fall back to list order.
 */
object ServerDate {

    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun toMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val text = raw.trim()
        val digits = text.toLongOrNull()
        if (digits != null) {
            // 10-digit values are epoch seconds, 13-digit are epoch millis.
            return if (text.length <= 11) digits * 1000L else digits
        }
        for (pattern in PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(text)?.let { return it.time }
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return 0L
    }
}