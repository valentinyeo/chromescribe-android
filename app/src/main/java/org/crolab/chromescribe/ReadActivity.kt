package org.crolab.chromescribe

import android.app.Activity
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import java.io.File

/**
 * Offline reader. Renders a cached markdown snapshot through the safe native
 * renderer. JavaScript, file/content access and all network loads are disabled,
 * so a transcript can never phone home or inject markup.
 */
class ReadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("id") ?: return finish()
        val cache = TranscriptStore(File(filesDir, "cache"))
        val markdown = cache.content(id)
        if (markdown == null) {
            Toast.makeText(this, R.string.toast_not_saved, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        title = intent.getStringExtra("title")
            ?: cache.index().firstOrNull { it.id == id }?.title
            ?: getString(R.string.title_transcript)

        val web = WebView(applicationContext)
        val s = web.settings
        s.javaScriptEnabled = false
        s.blockNetworkLoads = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        val template = assets.open("reader.html").bufferedReader().use { it.readText() }
        val html = template.replace("__CONTENT__", MarkdownRenderer.render(markdown))
        // Fixed non-null HTTPS base: a null base truncates inline HTML containing
        // '#' on Android Q, which made the reader blank. blockNetworkLoads=true
        // means the base is an origin only, never fetched.
        web.loadDataWithBaseURL("https://offline.chromescribe.invalid/", html, "text/html", "utf-8", null)
        setContentView(web)
    }
}