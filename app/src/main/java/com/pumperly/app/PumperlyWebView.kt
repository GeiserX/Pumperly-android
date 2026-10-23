package com.pumperly.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import com.pumperly.app.shell.NavigationPolicy
import com.pumperly.app.shell.ShellError

/**
 * Keeps pumperly.com navigation in the app, hands other links to the system, and never follows
 * the schemes [NavigationPolicy] blocks. The decisions live in [NavigationPolicy] and
 * [ShellError] so they can be tested without a WebView.
 *
 * The suppression on the constructor is for the lint check's constructor rule, which is meant
 * for a bare `WebViewClient()` and also fires on Kotlin's `: WebViewClient()` supertype call.
 * The class rule still checks that this class overrides [onRenderProcessGone].
 */
class PumperlyWebViewClient @SuppressLint("MissingOnRenderProcessGone") constructor(
    private val progressBar: ProgressBar,
    private val onError: (type: ShellError, failingUrl: String?) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onRendererGone: (view: WebView) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return when (NavigationPolicy.decide(url.toString(), request.isForMainFrame)) {
            NavigationPolicy.Decision.LOAD_IN_APP -> false
            NavigationPolicy.Decision.CANCEL -> true
            NavigationPolicy.Decision.OPEN_EXTERNALLY -> {
                // External https, mailto:, tel:, geo: and the like go to the app that owns them.
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    view.context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    // No handler for this scheme: ignore silently
                }
                true
            }
        }
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
            ShellError.classify(error.errorCode)?.let { onError(it, request.url?.toString()) }
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            ShellError.classifyHttpStatus(errorResponse.statusCode)?.let { onError(it, request.url?.toString()) }
        }
    }

    @Suppress("deprecation")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onError(ShellError.SSL, error.url)
    }

    /**
     * The renderer crashed or was killed to free memory. Without this override the whole app
     * dies with it. The dead WebView can never be used again, so the Activity replaces it and
     * reloads the page (the iOS app reloads on `webViewWebContentProcessDidTerminate`).
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(view)
        return true
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
