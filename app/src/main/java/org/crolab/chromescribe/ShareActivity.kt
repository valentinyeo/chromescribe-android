package org.crolab.chromescribe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Share target. The YouTube app sends "Title https://youtu.be/xxxx" as one
 * blob of text, so pull the URL out rather than trusting the whole string.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Regex("""https?://\S+""").find(text)?.value

        if (url == null) {
            Toast.makeText(this, "No URL in that share", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, "Sending to ChromeScribe…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val data = Api.queue(url)
                when {
                    !data.optBoolean("queued", false) -> "Already transcribed — open the app"
                    data.optBoolean("duplicate", false) -> "Already queued"
                    else -> "Queued for transcription"
                }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
