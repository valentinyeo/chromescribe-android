package org.crolab.chromescribe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Wraps the real ChromeScribe website in a hardened WebView. Login (Cloudflare
 * Access email OTP) stays inside the WebView; native API calls reuse the
 * resulting cookies. Also the ACTION_SEND target and the offline library host.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var webFrame: FrameLayout
    private lateinit var offlineList: ListView
    private lateinit var status: TextView
    private lateinit var outbox: Outbox
    private lateinit var cache: TranscriptStore
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentTranscriptId: String? = null
    private var currentTitle: String = ""
    private var retrying = false
    private var showingOffline = false
    private var statusPinned = false
    private val pageLoad = PageLoadTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outbox = Outbox(File(filesDir, "outbox"))
        cache = TranscriptStore(File(filesDir, "cache"))
        buildUi()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
            // A stored outbox entry already survived; never re-handle the share.
            if (web.url == null) web.loadUrl(Api.BASE)
        } else {
            web.loadUrl(Api.BASE)
            handleIntent(intent)
        }
        retryOutbox()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        retryOutbox()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = ui { retryOutbox() }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Emulators without a network stack: ignore, resume still retries.
        }
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        networkCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        webFrame.removeView(web)
        web.stopLoading()
        web.webViewClient = WebViewClient()
        web.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** Run UI work only while this Activity is still alive. */
    private fun ui(block: () -> Unit) {
        if (isFinishing || isDestroyedCompat()) return
        runOnUiThread {
            if (!isFinishing && !isDestroyedCompat()) block()
        }
    }

    private fun isDestroyedCompat() = isDestroyed

    // --- UI -----------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(primaryButton(getString(R.string.btn_website)) { showWebsite() })
        bar.addView(primaryButton(getString(R.string.btn_saved)) {
            startActivity(Intent(this, SavedActivity::class.java))
        })
        bar.addView(primaryButton(getString(R.string.btn_add)) { pasteDialog() })
        bar.addView(primaryButton(getString(R.string.btn_overflow)) { showOverflowMenu(it) })
        root.addView(bar)

        status = TextView(this).apply {
            setPadding(24, 8, 24, 8)
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            maxLines = 3
        }
        root.addView(status)

        webFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        web = WebView(applicationContext).also { configureWeb(it) }
        webFrame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        offlineList = ListView(this).apply { visibility = View.GONE }
        webFrame.addView(offlineList, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(webFrame)

        setContentView(root)
    }

    private fun primaryButton(label: String, onClick: (View) -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick(it) }
        }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, 1, 1, getString(R.string.menu_sync))
            menu.add(Menu.NONE, 2, 2, getString(R.string.menu_save))
            menu.add(Menu.NONE, 3, 3, getString(R.string.menu_retry))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> doSync()
                    2 -> saveCurrent()
                    3 -> retryNow()
                }
                true
            }
            show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Trusted dashboard needs JS; no bridge, file access or mixed content.
    private fun configureWeb(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.setGeolocationEnabled(false)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, false)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Subframe navigation (e.g. a YouTube embed) must never launch an app.
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme
                if (scheme.equals("http", true) || scheme.equals("https", true)) {
                    if (NavigationPolicy.isInternal(scheme, uri.host, uri.port, DASH_HOST, DASH_PORT)) return false
                    openExternal(uri)
                    return true
                }
                return true // block file/content/javascript/intent
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoad.onStarted()
            }

            override fun onPageFinished(view: WebView, url: String) {
                // WebView calls this even after a main-frame error; don't let it
                // clear the offline state that enables the Website retry.
                if (!pageLoad.finishedSuccessfully()) return
                detectTranscript(url)
                val uri = Uri.parse(url)
                if (NavigationPolicy.isDashboard(uri.scheme, uri.host, uri.port, DASH_HOST, DASH_PORT)) {
                    showingOffline = false
                    web.visibility = View.VISIBLE
                    // Successful dashboard page (e.g. after sign-in) is a good retry point.
                    retryOutbox()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageLoad.onMainFrameError()
                    showingOffline = true
                    showOffline(getString(R.string.status_offline_library))
                }
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    // --- website / offline --------------------------------------------------

    private fun showWebsite() {
        offlineList.visibility = View.GONE
        web.visibility = View.VISIBLE
        if (showingOffline || web.url == null) {
            showingOffline = false
            web.loadUrl(Api.BASE)
        }
    }

    private fun showOffline(message: String) {
        setStatus(message)
        val saved = cache.saved()
        offlineList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            saved.map { "${it.title}\n${it.channel}".trimEnd('\n') }
        )
        offlineList.setOnItemClickListener { _, _, position, _ ->
            val m = saved.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, ReadActivity::class.java)
                    .putExtra("id", m.id).putExtra("title", m.title)
            )
        }
        web.visibility = View.GONE
        offlineList.visibility = View.VISIBLE
    }

    private fun detectTranscript(url: String) {
        val id = transcriptIdFrom(url)
        currentTranscriptId = id
        currentTitle = id?.let { titleFromUrl(url) ?: it } ?: ""
        if (id != null && !cache.has(id)) {
            val app = applicationContext
            AppExecutors.run("cache") { Sync.cacheViewed(app, cache, id, currentTitle) }
        }
    }

    private fun transcriptIdFrom(url: String): String? = try {
        val u = Uri.parse(url)
        if (u.host?.lowercase() != Uri.parse(Api.BASE).host?.lowercase()) null
        else {
            val q = u.getQueryParameter("id")
            val segs = u.pathSegments
            when {
                q != null && SAFE.matches(q) && (u.path?.contains("transcript") == true || segs.contains("transcript")) -> q
                segs.size >= 2 && (segs[0] == "transcript" || segs[0] == "t") && SAFE.matches(segs[1]) -> segs[1]
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun titleFromUrl(url: String): String? =
        try { Uri.parse(url).getQueryParameter("title") } catch (e: Exception) { null }

    // --- sharing / outbox ---------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = YouTubeUrl.extract(text)
        if (url == null) {
            showWebsite()
            setStatus(getString(R.string.status_no_share_link), pin = false)
        } else {
            submit(url)
        }
        // Consume only after durable storage (or a rejection), so rotation and
        // recreation can never resubmit the same share.
        intent.action = null
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun pasteDialog() {
        val input = EditText(this).apply {
            hint = "https://youtu.be/..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_title)
            .setView(input)
            .setPositiveButton(R.string.btn_add) { _, _ ->
                val typed = input.text.toString()
                val url = YouTubeUrl.extract(typed) ?: YouTubeUrl.normalize(typed.trim())
                if (url == null) setStatus(getString(R.string.status_invalid_link), pin = false)
                else submit(url)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Persist first, then try the network. Nothing is ever reported as transcribed on a queue success. */
    private fun submit(url: String) {
        try {
            outbox.add(url)
        } catch (e: Exception) {
            setStatus(getString(R.string.status_save_failed, e.message ?: ""), pin = true)
            return
        }
        setStatus(getString(R.string.status_saved_offline), pin = false)
        showWebsite()
        retryOutbox()
    }

    private fun retryNow() {
        statusPinned = false
        setStatus(getString(R.string.status_retrying), pin = false)
        retryOutbox()
    }

    private fun retryOutbox() {
        if (retrying) return
        val entries = try {
            outbox.pending()
        } catch (e: Exception) {
            setStatus(getString(R.string.status_outbox_unreadable, e.message ?: ""), pin = true)
            return
        }
        if (entries.isEmpty()) {
            updateStatus()
            return
        }
        retrying = true
        val app = applicationContext
        AppExecutors.run("outbox", onError = {
            ui { setStatus(getString(R.string.status_outbox_error), pin = true) }
        }) {
            var lastMessage = ""
            var pinned = false
            try {
                for (entry in entries) {
                    try {
                        outbox.update(entry.id, OutboxState.SENDING, "sending")
                        when (val result = Api.queue(app, entry.url)) {
                            is Api.QueueResult.Queued -> {
                                outbox.remove(entry.id)
                                lastMessage = getString(R.string.status_queued)
                            }
                            is Api.QueueResult.AlreadyTranscribed -> {
                                outbox.remove(entry.id)
                                lastMessage = getString(R.string.status_already_transcribed)
                                ui { openTranscript(result.transcriptId) }
                            }
                            is Api.QueueResult.Duplicate -> {
                                outbox.remove(entry.id)
                                lastMessage = getString(R.string.status_already_queued)
                            }
                        }
                    } catch (e: AuthRequiredException) {
                        outbox.update(entry.id, OutboxState.AUTH, "sign in")
                        lastMessage = getString(R.string.status_sign_in_retry)
                        pinned = true
                    } catch (e: ApiException) {
                        // Server rejected / errored: keep it queued and say so.
                        outbox.update(entry.id, OutboxState.FAILED, "server ${e.code}")
                        lastMessage = getString(R.string.status_server_error, e.code)
                        pinned = true
                    } catch (e: Exception) {
                        outbox.update(entry.id, OutboxState.FAILED, e.message ?: "network error")
                        lastMessage = getString(R.string.status_offline_retry)
                    }
                }
            } finally {
                ui {
                    retrying = false
                    if (lastMessage.isNotEmpty()) setStatus(lastMessage, pin = pinned)
                    updateStatus()
                }
            }
        }
    }

    private fun openTranscript(id: String) {
        // Best-effort site route; verified once the parent tests against the live site.
        web.loadUrl("${Api.BASE}/transcript.html?id=$id")
    }

    // --- sync / save --------------------------------------------------------

    private fun doSync() {
        setStatus(getString(R.string.status_syncing), pin = false)
        val app = applicationContext
        AppExecutors.run("sync", onError = {
            ui { setStatus(getString(R.string.status_background_failed), pin = true) }
        }) {
            val report = Sync.run(app, cache, refresh = true)
            getSharedPreferences("chromescribe", MODE_PRIVATE).edit()
                .putString("last_sync", report.message.orEmpty())
                .putLong("last_sync_at", System.currentTimeMillis()).apply()
            ui {
                if (report.authRequired) {
                    showWebsite()
                    setStatus(getString(R.string.status_sign_in_sync), pin = true)
                } else if (report.message != null) {
                    setStatus(report.message, pin = report.failed > 0)
                }
            }
        }
    }

    private fun saveCurrent() {
        val id = currentTranscriptId
        if (id == null) {
            setStatus(getString(R.string.status_nothing_to_save), pin = false)
            return
        }
        setStatus(getString(R.string.status_saving), pin = false)
        val app = applicationContext
        AppExecutors.run("save", onError = {
            ui { setStatus(getString(R.string.status_background_failed), pin = true) }
        }) {
            val ok = Sync.cacheViewed(app, cache, id, currentTitle)
            ui {
                setStatus(getString(if (ok) R.string.status_saved else R.string.status_save_online_only), pin = !ok)
            }
        }
    }

    private fun updateStatus() {
        if (statusPinned) return
        val pending = try {
            outbox.pending().size
        } catch (e: Exception) {
            setStatus(getString(R.string.status_outbox_unreadable, e.message ?: ""), pin = true)
            return
        }
        status.text = when {
            pending > 0 -> resources.getQuantityString(R.plurals.status_waiting, pending, pending)
            status.text.isNullOrEmpty() -> getString(R.string.status_ready)
            else -> status.text
        }
    }

    private fun setStatus(message: String, pin: Boolean = false) {
        status.text = message
        statusPinned = pin
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val DASH_HOST = Uri.parse(Api.BASE).host?.lowercase().orEmpty()
        private val DASH_PORT = Uri.parse(Api.BASE).port
    }
}