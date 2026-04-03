package com.pumperly.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar

/**
 * Custom WebViewClient that keeps pumperly.com navigation in-app
 * and opens external links in the system browser.
 */
enum class WebViewError { OFFLINE, SSL, PAGE }

class PumperlyWebViewClient(
    private val progressBar: ProgressBar,
    private val onError: (type: WebViewError, failingUrl: String?) -> Unit,
    private val onPageStarted: () -> Unit
) : WebViewClient() {

    companion object {
        private val PUMPERLY_HOSTS = setOf(
            "pumperly.com",
            "www.pumperly.com"
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        val host = url.host?.lowercase()

        // Allow pumperly.com HTTPS navigation in-app
        if (scheme == "https" && host in PUMPERLY_HOSTS) {
            return false
        }

        // Everything else (external HTTPS, mailto:, tel:, geo:, etc.) → system handler
        try {
            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // No handler for this scheme — ignore silently
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        progressBar.visibility = ProgressBar.VISIBLE
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        progressBar.visibility = ProgressBar.GONE
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            val type = if (error.errorCode == ERROR_HOST_LOOKUP || error.errorCode == ERROR_CONNECT ||
                error.errorCode == ERROR_TIMEOUT || error.errorCode == ERROR_IO
            ) WebViewError.OFFLINE else WebViewError.PAGE
            onError(type, request.url?.toString())
        }
    }

    @Suppress("deprecation")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onError(WebViewError.SSL, error.url)
    }
}

/**
 * Custom WebChromeClient that handles geolocation permission callbacks
 * and file chooser for input[type=file].
 */
class PumperlyWebChromeClient(
    private val progressBar: ProgressBar,
    private val onGeolocationRequest: (origin: String, callback: GeolocationPermissions.Callback) -> Unit,
    private val onFileChooserRequest: (filePathCallback: ValueCallback<Array<Uri>>, intent: Intent) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        progressBar.progress = newProgress
        if (newProgress == 100) {
            progressBar.visibility = ProgressBar.GONE
        } else {
            progressBar.visibility = ProgressBar.VISIBLE
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        onGeolocationRequest(origin, callback)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        return try {
            val intent = fileChooserParams.createIntent()
            onFileChooserRequest(filePathCallback, intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            filePathCallback.onReceiveValue(null)
            false
        }
    }
}
