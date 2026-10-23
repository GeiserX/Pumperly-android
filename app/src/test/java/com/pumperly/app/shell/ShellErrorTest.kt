package com.pumperly.app.shell

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ported from the iOS ShellErrorTests, with the WebView's error codes in place of NSURLError. */
@RunWith(RobolectricTestRunner::class)
class ShellErrorTest {
    @Test
    fun networkFailuresShowTheOfflineScreen() {
        for (code in listOf(
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO,
        )) {
            assertEquals("code $code", ShellError.OFFLINE, ShellError.classify(code))
        }
    }

    @Test
    fun certificateFailuresShowTheSslScreen() {
        assertEquals(ShellError.SSL, ShellError.classify(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
    }

    @Test
    fun loadsTheAppStoppedItselfShowNothing() {
        assertNull(ShellError.classify(WebViewClient.ERROR_UNSUPPORTED_SCHEME))
        assertNull(ShellError.classifyHttpStatus(200))
        assertNull(ShellError.classifyHttpStatus(304))
        assertNull(ShellError.fromName("NOT_A_SCREEN"))
        assertEquals(ShellError.SSL, ShellError.fromName("SSL"))
    }

    @Test
    fun anythingElseIsAPageError() {
        for (code in listOf(
            WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_BAD_URL,
            WebViewClient.ERROR_FILE_NOT_FOUND, WebViewClient.ERROR_UNSAFE_RESOURCE,
        )) {
            assertEquals("code $code", ShellError.PAGE, ShellError.classify(code))
        }
        assertEquals(ShellError.PAGE, ShellError.classifyHttpStatus(404))
        assertEquals(ShellError.PAGE, ShellError.classifyHttpStatus(503))
    }

    @Test
    fun everyScreenHasEnglishAndSpanishText() {
        val app: Application = ApplicationProvider.getApplicationContext()
        fun context(language: String): Context =
            app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
        val en = context("en")
        val es = context("es")
        for (error in ShellError.entries) {
            for (res in listOf(error.title, error.message, error.action)) {
                val english = en.getString(res)
                val spanish = es.getString(res)
                assertTrue("$error en is blank", english.isNotBlank())
                assertTrue("$error es is blank", spanish.isNotBlank())
                assertNotEquals("$error is not translated", english, spanish)
            }
        }
    }
}
