package com.pumperly.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from pumperly-ios PumperlyTests/UserAgentTests.swift. The iPhone-only and live WebView
 * checks have no Android counterpart in the core; the WebView's user agent is the shell's.
 */
class UserAgentTest {
    @Test
    fun webViewSuffixAndWidgetUserAgentCarryTheToken() {
        assertEquals("PumperlyAndroid/1.1.0", UserAgent.webViewSuffix("1.1.0"))
        assertEquals("PumperlyAndroid/1.1.0 (Widget)", UserAgent.widgetUserAgent("1.1.0"))
    }

    @Test
    fun appVersionComesFromGradleProperties() {
        assertTrue(AppConfig.appVersion, Regex("""^\d+\.\d+\.\d+$""").matches(AppConfig.appVersion))
    }
}
