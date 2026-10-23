package com.pumperly.app.core

import com.pumperly.app.BuildConfig

/** Constants shared by the app, the widget and the background refresh. */
object AppConfig {
    /** The only site the app shows. Navigation outside it opens in another app. */
    const val BASE_URL = "https://pumperly.com"

    /** Hosts that load inside the app. */
    val allowedHosts: Set<String> = setOf("pumperly.com", "www.pumperly.com")

    /** versionName from gradle.properties (VERSION_NAME), or the tag in release builds. */
    val appVersion: String get() = BuildConfig.VERSION_NAME
}
