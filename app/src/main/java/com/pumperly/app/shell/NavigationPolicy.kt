package com.pumperly.app.shell

import com.pumperly.app.core.AppConfig
import java.net.URI

/**
 * Where a navigation may go. Ported from the iOS `NavigationPolicy`: only https pumperly.com
 * loads in the app, a few schemes are never followed at all, and everything else leaves the app.
 *
 * URLs are parsed with [java.net.URI], here and in the WebView client alike, so the parser the
 * tests exercise is the one that decides in the app. A URL it cannot parse is never trusted.
 */
object NavigationPolicy {
    enum class Decision { LOAD_IN_APP, OPEN_EXTERNALLY, CANCEL }

    /** Schemes the page may never navigate to, in the app or outside it. */
    val blockedSchemes: Set<String> = setOf("javascript", "file", "data", "blob")

    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun scheme(url: String?): String? = url?.let { schemePattern.find(it.trim())?.groupValues?.get(1)?.lowercase() }

    private fun host(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** True only for https on pumperly.com or www.pumperly.com. */
    fun isAllowed(url: String?): Boolean {
        if (url == null || scheme(url) != "https") return false
        return host(url) in AppConfig.allowedHosts
    }

    fun decide(url: String?, isMainFrame: Boolean): Decision {
        val scheme = scheme(url) ?: return Decision.CANCEL
        if (scheme in blockedSchemes) return Decision.CANCEL
        if (scheme == "about") return Decision.LOAD_IN_APP
        if (isAllowed(url)) return Decision.LOAD_IN_APP
        // A frame inside a pumperly.com page (an embed) may load https content in place,
        // but it never sends the user out of the app.
        if (!isMainFrame) return if (scheme == "https") Decision.LOAD_IN_APP else Decision.CANCEL
        // Other sites, http, mailto:, tel:, geo: go to the browser or the app that owns the scheme.
        return Decision.OPEN_EXTERNALLY
    }

    /** Only a top-level https pumperly.com page may read the location. */
    fun mayShareLocation(isMainFrame: Boolean, scheme: String, host: String): Boolean =
        isMainFrame && scheme.lowercase() == "https" && host.lowercase() in AppConfig.allowedHosts
}
