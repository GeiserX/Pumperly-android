package com.pumperly.app.shell

import com.pumperly.app.core.AppConfig
import java.net.URI

/**
 * Where a navigation may go. Ported from the iOS `NavigationPolicy`: only https pumperly.com
 * loads in the app, a few schemes are never followed at all, and everything else leaves the app.
 *
 * Hosts come from [java.net.URI]. Chromium hands over URLs that URI rejects (a raw `|` or `{` in
 * a query is legal for the WebView), so when URI throws the host is read from the authority by
 * [authorityHost] instead. That fallback treats `\` as a delimiter, as Chromium does, so it never
 * reports pumperly.com for a URL the WebView would load from another host.
 */
object NavigationPolicy {
    enum class Decision { LOAD_IN_APP, OPEN_EXTERNALLY, CANCEL }

    /** Schemes the page may never navigate to, in the app or outside it. */
    val blockedSchemes: Set<String> = setOf("javascript", "file", "data", "blob")

    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun scheme(url: String?): String? = url?.let { schemePattern.find(it.trim())?.groupValues?.get(1)?.lowercase() }

    // scheme://[userinfo@]host — userinfo and host stop at `/`, `\`, `?`, `#` (and host at `:`, `@`).
    private val authorityHost = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/\\?#@]*@)?([^/\\?#:@]+)""")

    private fun host(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (_: Exception) {
        authorityHost.find(url.trim())?.groupValues?.get(1)?.lowercase()
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
