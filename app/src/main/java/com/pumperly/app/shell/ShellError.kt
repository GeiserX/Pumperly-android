package com.pumperly.app.shell

import android.webkit.WebViewClient
import androidx.annotation.StringRes
import com.pumperly.app.R

/** The three error screens of the shell: offline (retry), certificate (go back), page (retry). */
enum class ShellError(
    @param:StringRes @get:StringRes val title: Int,
    @param:StringRes @get:StringRes val message: Int,
    @param:StringRes @get:StringRes val action: Int,
) {
    OFFLINE(R.string.offline_title, R.string.offline_message, R.string.offline_retry),
    SSL(R.string.ssl_error_title, R.string.ssl_error_message, R.string.ssl_error_back),
    PAGE(R.string.page_error_title, R.string.page_error_message, R.string.page_error_retry);

    companion object {
        /**
         * Maps a main-frame `WebViewClient.onReceivedError` code to the screen to show, or null
         * when it is not a failure the user should see: a URL the WebView cannot show follows a
         * decision the app made itself (the iOS WebKit 101/102/204 case).
         */
        fun classify(errorCode: Int): ShellError? = when (errorCode) {
            WebViewClient.ERROR_UNSUPPORTED_SCHEME -> null
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO -> OFFLINE
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> SSL
            else -> PAGE
        }

        /** A main-frame HTTP status: 4xx and 5xx show the page error, anything else nothing. */
        fun classifyHttpStatus(status: Int): ShellError? = if (status >= 400) PAGE else null

        /** The saved-state name back to a screen; an unknown name shows nothing. */
        fun fromName(name: String?): ShellError? = entries.find { it.name == name }
    }
}
