package com.pumperly.app.core

/** The token the site can use to detect the Android app: `PumperlyAndroid/<version>`. */
object UserAgent {
    const val TOKEN = "PumperlyAndroid"

    /** Appended by the WebView after its default user agent, separated by a space. */
    fun webViewSuffix(version: String): String = "$TOKEN/$version"

    /** User agent for the native API calls (widget, background refresh), which run outside the WebView. */
    fun widgetUserAgent(version: String): String = "$TOKEN/$version (Widget)"
}
