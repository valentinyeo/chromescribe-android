package org.crolab.chromescribe

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject

/**
 * Renders one transcript's markdown. Reads the cached copy first so it works
 * with no signal; only falls back to the network when nothing is cached.
 */
class ReadActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        setContentView(web)

        val id = intent.getStringExtra("id") ?: return finish()
        title = intent.getStringExtra("title") ?: "Transcript"

        val file = Api.contentFile(this, id)
        if (file.exists()) {
            show(file.readText())
        } else {
            Thread {
                try {
                    val md = Api.get("/api/transcript/$id/content")
                    file.writeText(md)
                    runOnUiThread { show(md) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Not cached and offline", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }.start()
        }
    }

    private fun show(markdown: String) {
        val template = assets.open("reader.html").bufferedReader().use { it.readText() }
        val html = template.replace("__MARKDOWN__", JSONObject.quote(markdown))
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }
}
