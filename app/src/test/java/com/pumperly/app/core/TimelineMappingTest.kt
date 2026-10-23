package com.pumperly.app.core

import java.io.IOException
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from pumperly-ios PumperlyTests/TimelineMappingTests.swift (10 tests). */
class TimelineMappingTest {
    private val english = Locale.US
    private val now = Instant.ofEpochSecond(1_790_000_000)

    @Test
    fun pricedFuelShowsCheapestFirst() {
        val stations = Fixture.stations("nearest-b7")
        val rows = TimelineMapping.rows(stations, FuelType.B7, english)
        // Fixture prices: 4508 1.969, 3217 1.979, 4352 1.975, 4711 1.949, 3218 1.955.
        assertEquals(listOf("Shell Madrid", "Repsol Madrid", "Blanca Madrid"), rows.map { it.name })
        assertEquals(listOf("€1.949", "€1.955", "€1.969"), rows.map { it.valueText })
        assertEquals(listOf("1.5 km", "1.6 km", "1.3 km"), rows.map { it.distanceText })
        assertEquals("https://pumperly.com/?station=ES:4711&lat=40.43&lng=-3.7085&fuel=B7", rows.first().url)
    }

    @Test
    fun equalPricesFallBackToDistance() {
        val near = station("near", price = 1.5, distance = 0.5)
        val far = station("far", price = 1.5, distance = 3.0)
        val rows = TimelineMapping.rows(listOf(far, near), FuelType.E5, english)
        assertEquals(listOf("near", "far"), rows.map { it.id })
    }

    @Test
    fun stationsWithoutPriceAreDroppedForPricedFuels() {
        val rows = TimelineMapping.rows(
            listOf(station("a", price = null, distance = 0.1), station("b", price = 1.6, distance = 2.0)),
            FuelType.B7, english,
        )
        assertEquals(listOf("b"), rows.map { it.id })
    }

    @Test
    fun evSortsByDistanceAndShowsPower() {
        val stations = Fixture.stations("nearest-ev").reversed()
        val rows = TimelineMapping.rows(stations, FuelType.EV, english)
        assertEquals(listOf("360 kW", "50 kW", "7 kW"), rows.map { it.valueText })
        assertEquals("Telpark - Plaza del Carmen", rows.first().name)
        // The link opens the EV layer, where the charger lives.
        assertTrue(rows.all { it.url.endsWith("&fuel=EV") })
    }

    @Test
    fun atMostThreeRows() {
        val stations = (0 until 10).map { station("$it", price = 1.5 + it / 100.0, distance = 1.0) }
        assertEquals(3, TimelineMapping.rows(stations, FuelType.B7, english).size)
    }

    @Test
    fun entryStates() {
        assertEquals(CheapestNearbyEntry.Content.NeedsLocation, TimelineMapping.entry(now, FuelType.B7, null).content)
        assertEquals(CheapestNearbyEntry.Content.Empty, TimelineMapping.entry(now, FuelType.B7, Result.success(emptyList())).content)
        assertEquals(
            CheapestNearbyEntry.Content.Unavailable,
            TimelineMapping.entry(now, FuelType.B7, Result.failure(IOException("offline"))).content,
        )
        val ok = TimelineMapping.entry(now, FuelType.B7, Result.success(listOf(station("a", 1.5, 1.0))), english)
        val content = ok.content as CheapestNearbyEntry.Content.Stations
        assertEquals(1, content.rows.size)
        assertEquals(FuelType.B7, ok.fuel)
        assertEquals(now, ok.date)
    }

    @Test
    fun refreshIsHourlyAndSoonerAfterAFailure() {
        val good = TimelineMapping.entry(now, FuelType.B7, Result.success(emptyList()))
        assertEquals(now.plusSeconds(3600), TimelineMapping.nextRefresh(good))
        val bad = TimelineMapping.entry(now, FuelType.B7, Result.failure(IOException("timed out")))
        assertEquals(now.plusSeconds(900), TimelineMapping.nextRefresh(bad))
    }

    @Test
    fun widgetTapTargets() {
        val stations = TimelineMapping.entry(now, FuelType.B7, Result.success(listOf(station("a", 1.5, 1.0))))
        val target = TimelineMapping.tapTarget(stations) as TapTarget.Station
        assertTrue(target.url.startsWith("https://pumperly.com/?"))
        assertTrue(target.url.contains("station=ES:a"))
        val empty = TimelineMapping.entry(now, FuelType.B7, Result.success(emptyList()))
        assertEquals(TapTarget.Settings, TimelineMapping.tapTarget(empty))
        val noLocation = TimelineMapping.entry(now, FuelType.B7, null)
        assertEquals(TapTarget.Home(AppConfig.BASE_URL), TimelineMapping.tapTarget(noLocation))
        val unavailable = TimelineMapping.entry(now, FuelType.B7, Result.failure(IOException()))
        assertEquals(TapTarget.Home(AppConfig.BASE_URL), TimelineMapping.tapTarget(unavailable))
    }

    @Test
    fun spanishFormatting() {
        val spanish = Locale.forLanguageTag("es-ES")
        val price = TimelineMapping.formatPrice(1.949, "EUR", spanish)
        assertTrue(price, price.contains("1,949"))
        assertTrue(price, price.contains("€"))
        assertEquals("1,5 km", TimelineMapping.formatDistance(1.531, spanish))
    }

    @Test
    fun blankNameFallsBackToBrand() {
        assertEquals("Brand", TimelineMapping.displayName(station("x", 1.0, 1.0, name = "  ")))
    }

    private fun station(id: String, price: Double?, distance: Double, name: String = "Station") = Station(
        id = id, externalId = id, country = "ES", name = name, brand = "Brand", city = "Madrid",
        latitude = 40.4, longitude = -3.7, price = price, currency = "EUR", distanceKm = distance, powerKw = null,
    )
}
