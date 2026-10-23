package com.pumperly.app.core

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** [HttpNearestApi] against a real local HTTP server, so the request and the error paths are exercised for real. */
class NearestApiTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var body = Fixture.text("nearest-b7")
    private val requests = mutableListOf<Pair<String, Map<String, String?>>>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString() to mapOf(
                "User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                "Accept" to exchange.requestHeaders.getFirst("Accept"),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun api() = HttpNearestApi(base = "http://127.0.0.1:${server.address.port}", userAgent = "PumperlyAndroid/9.9.9 (Widget)")

    @Test
    fun sendsTheRoundedQueryAndTheWidgetUserAgent() = runTest {
        val stations = api().fetchNearest(40.416775, -3.703790, FuelType.B7)
        assertEquals(5, stations.size)
        assertEquals("4508", stations.first().externalId)
        val (uri, headers) = requests.single()
        assertEquals("/api/stations/nearest?lat=40.417&lon=-3.704&radius_km=10&fuel=B7&limit=50", uri)
        assertEquals("PumperlyAndroid/9.9.9 (Widget)", headers["User-Agent"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun aNon200AnswerThrows() = runTest {
        status = 400
        body = """{"error":"Invalid parameters"}"""
        try {
            api().fetchNearest(40.4, -3.7, FuelType.B7)
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("400"))
        }
    }

    @Test
    fun theDefaultClientSendsTheAppVersionUserAgent() = runTest {
        HttpNearestApi(base = "http://127.0.0.1:${server.address.port}").fetchNearest(40.4, -3.7, FuelType.B7)
        assertEquals(UserAgent.widgetUserAgent(AppConfig.appVersion), requests.single().second["User-Agent"])
    }

    @Test
    fun aRedirectIsFollowedLikeOnIos() = runTest {
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/api/stations/nearest?moved=1")
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        val stations = HttpNearestApi(base = "http://127.0.0.1:${server.address.port}/moved", userAgent = "UA")
            .fetchNearest(40.4, -3.7, FuelType.B7)
        assertEquals(5, stations.size)
        assertEquals("/api/stations/nearest?moved=1", requests.single().first)
    }
}
