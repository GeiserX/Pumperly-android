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
class PumperlyWebViewClient(
    private val progressBar: ProgressBar,
    private val onPageError: (failingUrl: String?) -> Unit,
    private val onPageStarted: () -> Unit
) : WebViewClient() {

    companion object {
        private val PUMPERLY_HOSTS = setOf(
            "pumperly.com",
            "www.pumperly.com"
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host?.lowercase() ?: return false
        if (host in PUMPERLY_HOSTS) {
            return false
        }
        // External link: open in system browser
        val intent = Intent(Intent.ACTION_VIEW, request.url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        view.context.startActivity(intent)
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
        // Only handle main frame errors
        if (request.isForMainFrame) {
            onPageError(request.url?.toString())
        }
    }

    @Suppress("deprecation")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never trust invalid certs. Cancel and show error.
        handler.cancel()
        onPageError(error.url)
    }
}

/**
 * Custom WebChromeClient that handles geolocation permission callbacks
 * and file chooser for input[type=file].
 */
class PumperlyWebChromeClient(
    private val progressBar: ProgressBar,
    private val onGeolocationRequest: (origin: String, callback: GeolocationPermissions.Callback) -> Unit,
    private val onFileChooserRequest: (filePathCallback: ValueCallback<Array<Uri>>, acceptType: String?) -> Unit
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
        val acceptTypes = fileChooserParams.acceptTypes
        val acceptType = if (acceptTypes.isNullOrEmpty()) null else acceptTypes[0]
        onFileChooserRequest(filePathCallback, acceptType)
        return true
    }
}
