package com.pumperly.app.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one backend call the native surfaces make. An interface so callers can be tested with a fake. */
fun interface NearestApi {
    /** Stations near the position, nearest first. Throws on a network, HTTP or decoding failure. */
    suspend fun fetchNearest(latitude: Double, longitude: Double, fuel: FuelType): List<Station>
}

/**
 * [NearestApi] over `HttpURLConnection`: the API is one GET returning JSON, so no HTTP client
 * library is needed. Coordinates are rounded to 3 decimals by [StationsApi.nearestUrl].
 */
class HttpNearestApi(
    private val base: String = AppConfig.BASE_URL,
    private val userAgent: String = UserAgent.widgetUserAgent(AppConfig.appVersion),
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
) : NearestApi {
    override suspend fun fetchNearest(latitude: Double, longitude: Double, fuel: FuelType): List<Station> =
        withContext(Dispatchers.IO) {
            val url = URL(StationsApi.nearestUrl(latitude, longitude, fuel, base))
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", userAgent)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw IOException("nearest stations: HTTP $status")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                StationsApi.decode(body)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
