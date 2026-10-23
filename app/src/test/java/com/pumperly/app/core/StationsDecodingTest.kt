package com.pumperly.app.core

import java.net.URI
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from pumperly-ios PumperlyTests/StationsDecodingTests.swift (6 tests). */
class StationsDecodingTest {
    @Test
    fun decodesLiveFuelResponse() {
        val stations = Fixture.stations("nearest-b7")
        assertEquals(5, stations.size)
        val first = stations.first()
        assertEquals("4508", first.externalId)
        assertEquals("ES", first.country)
        assertEquals("Blanca Madrid", first.name)
        assertEquals("Blanca", first.brand)
        assertEquals(1.969, first.price!!, 0.0)
        assertEquals("EUR", first.currency)
        assertEquals(1.308, first.distanceKm, 0.0001)
        // GeoJSON order is [longitude, latitude].
        assertEquals(40.405278, first.latitude, 0.000001)
        assertEquals(-3.703139, first.longitude, 0.000001)
        assertNull(first.powerKw)
    }

    @Test
    fun decodesLiveEvResponseWithoutPrices() {
        val stations = Fixture.stations("nearest-ev")
        assertEquals(3, stations.size)
        assertTrue(stations.all { it.price == null })
        assertEquals(360.0, stations.first().powerKw!!, 0.0)
    }

    @Test
    fun errorBodyThrows() {
        assertThrows(SerializationException::class.java) {
            StationsApi.decode("""{"error":"Invalid parameters"}""")
        }
    }

    @Test
    fun requestUrlRoundsCoordinatesAndNamesTheFuel() {
        val uri = URI(StationsApi.nearestUrl(40.416775, -3.703790, FuelType.E5))
        assertEquals("https", uri.scheme)
        assertEquals("pumperly.com", uri.host)
        assertEquals("/api/stations/nearest", uri.path)
        val query = uri.rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals(mapOf("lat" to "40.417", "lon" to "-3.704", "radius_km" to "10", "fuel" to "E5", "limit" to "50"), query)
    }

    @Test
    fun stationPageUsesTheSiteShareFormat() {
        val station = Fixture.stations("nearest-b7").first()
        val url = StationsApi.pageUrl(station, FuelType.B7)
        assertEquals("https://pumperly.com/?station=ES:4508&lat=40.40528&lng=-3.70314&fuel=B7", url)
        // iOS also checks NavigationPolicy.isAllowed here; that policy is the shell lane's.
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertTrue(uri.host in AppConfig.allowedHosts)
    }

    @Test
    fun fuelCodesMatchTheSite() {
        assertEquals(
            listOf(
                "ADBLUE", "B10", "B7", "B7_PREMIUM", "B_AGRICULTURAL", "CNG", "E10", "E5", "E5_98",
                "E5_PREMIUM", "E98_E10", "EV", "H2", "HVO", "LNG", "LPG",
            ),
            FuelType.entries.map { it.code }.sorted(),
        )
        assertEquals(FuelType.B7, FuelType.DEFAULT)
        assertEquals(FuelType.entries.size, FuelType.Category.entries.flatMap { it.fuels }.size)
        // Every enum constant's name is its API code, so the two can never drift apart.
        assertTrue(FuelType.entries.all { it.name == it.code })
    }
}
