package org.crolab.chromescribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val host = "chromescribe-dashboard.ancient-glitter-9ac9.workers.dev"

    @Test fun httpsDashboardDefaultPortIsInternal() {
        assertTrue(NavigationPolicy.isDashboard("https", host, -1, host, -1))
        assertTrue(NavigationPolicy.isDashboard("https", host, 443, host, -1))
        assertTrue(NavigationPolicy.isInternal("https", host, -1, host, -1))
    }

    @Test fun httpAndWrongPortAndSuffixHostsAreNotInternal() {
        assertFalse(NavigationPolicy.isDashboard("http", host, -1, host, -1))
        assertFalse(NavigationPolicy.isDashboard("https", host, 8443, host, -1))
        assertFalse(NavigationPolicy.isDashboard("https", "evil.$host", -1, host, -1))
        assertFalse(NavigationPolicy.isDashboard("https", "evil.com", -1, host, -1))
    }

    @Test fun trustedCloudflareLoginIsInternal() {
        assertTrue(NavigationPolicy.isTrustedLogin("https", "vwo-analyzer.cloudflareaccess.com"))
        assertTrue(NavigationPolicy.isInternal("https", "vwo-analyzer.cloudflareaccess.com", -1, host, -1))
        assertFalse(NavigationPolicy.isTrustedLogin("http", "vwo-analyzer.cloudflareaccess.com"))
        assertFalse(NavigationPolicy.isTrustedLogin("https", "cloudflareaccess.com.evil.com"))
    }
}

class PageLoadTrackerTest {

    @Test fun lateOnPageFinishedAfterErrorDoesNotCountAsSuccess() {
        val tracker = PageLoadTracker()
        tracker.onStarted()
        tracker.onMainFrameError()
        assertFalse(tracker.finishedSuccessfully())
    }

    @Test fun newNavigationResetsFailure() {
        val tracker = PageLoadTracker()
        tracker.onMainFrameError()
        tracker.onStarted()
        assertTrue(tracker.finishedSuccessfully())
    }

    @Test fun cleanLoadIsSuccess() {
        val tracker = PageLoadTracker()
        tracker.onStarted()
        assertTrue(tracker.finishedSuccessfully())
    }
}