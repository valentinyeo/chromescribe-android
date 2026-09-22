package org.crolab.chromescribe

/**
 * Pure navigation rules, unit tested without Android.
 *
 * Only HTTPS on the dashboard's exact origin (host + effective port) or a
 * trusted Cloudflare Access login domain is loaded inside the WebView.
 */
object NavigationPolicy {

    fun isDashboard(scheme: String?, host: String?, port: Int, dashboardHost: String, dashboardPort: Int): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        val h = host?.lowercase() ?: return false
        if (h != dashboardHost.lowercase()) return false
        return effectivePort(port) == effectivePort(dashboardPort)
    }

    fun isTrustedLogin(scheme: String?, host: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        val h = host?.lowercase() ?: return false
        return h.endsWith(".cloudflareaccess.com")
    }

    fun isInternal(scheme: String?, host: String?, port: Int, dashboardHost: String, dashboardPort: Int): Boolean =
        isDashboard(scheme, host, port, dashboardHost, dashboardPort) || isTrustedLogin(scheme, host)

    private fun effectivePort(port: Int): Int = if (port == -1) 443 else port
}

/**
 * Tracks whether the latest main-frame navigation succeeded. WebView calls
 * onPageFinished even after onReceivedError, so this prevents that late
 * callback from clearing the offline state and hiding the retry path.
 */
class PageLoadTracker {
    private var failed = false

    fun onStarted() {
        failed = false
    }

    fun onMainFrameError() {
        failed = true
    }

    fun finishedSuccessfully(): Boolean = !failed
}