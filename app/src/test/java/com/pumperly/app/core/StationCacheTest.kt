package com.pumperly.app.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ported from pumperly-ios PumperlyTests/StationCacheTests.swift. Its four offline-screen tests
 * (which errors show the cache, the header text, the maps link, the Offline strings) belong to the
 * offline-screen lane; the station order they check is covered here by
 * [offlineScreenStationsKeepTheWidgetOrder]. [saveLeavesNoTemporaryFiles] is new: Android writes
 * the file itself instead of through Foundation's atomic option.
 */
class StationCacheTest {
    private val english = Locale.US
    private val madrid = ZoneId.of("Europe/Madrid")
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private lateinit var directory: File
    private lateinit var cache: StationCache

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("station-cache").toFile()
        cache = StationCache(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun snapshot(
        fuel: FuelType = FuelType.B7,
        savedAt: Instant = now,
        stations: List<Station> = Fixture.stations("nearest-b7"),
        latitude: Double = 40.416775,
        longitude: Double = -3.703790,
    ) = StationCache.Snapshot(savedAt, fuel, latitude, longitude, stations)

    // Cache file

    @Test
    fun roundTripKeepsStationsAndRoundsThePosition() {
        val saved = snapshot()
        cache.save(saved)
        assertEquals("nearest-cache.json", cache.file.name)
        val loaded = cache.load(now = now)!!
        assertEquals(saved, loaded)
        assertEquals(5, loaded.stations.size)
        assertEquals(40.417, loaded.latitude, 0.0)
        assertEquals(-3.704, loaded.longitude, 0.0)
        assertEquals(now, loaded.savedAt)
    }

    @Test
    fun snapshotsOlderThanADayAreIgnored() {
        cache.save(snapshot())
        assertNotNull(cache.load(now = now.plusSeconds(23 * 3600)))
        assertNotNull(cache.load(now = now.plusSeconds(24 * 3600)))
        assertNull(cache.load(now = now.plusSeconds(24 * 3600 + 1)))
        // A snapshot from the future (the clock moved back) is not shown either.
        assertNull(cache.load(now = now.minusSeconds(3600)))
    }

    @Test
    fun anotherFuelIsIgnored() {
        cache.save(snapshot(fuel = FuelType.B7))
        assertNull(cache.load(fuel = FuelType.E5, now = now))
        assertNotNull(cache.load(fuel = FuelType.B7, now = now))
    }

    @Test
    fun missingOrCorruptFileReadsAsNoSnapshot() {
        assertNull(cache.load(now = now))
        cache.file.writeText("{\"savedAt\": \"not a date\"")
        assertNull(cache.load(now = now))
        cache.file.writeBytes(byteArrayOf(0xFF.toByte(), 0x00, 0x13))
        assertNull(cache.load(now = now))
    }

    @Test
    fun clearRemovesTheFile() {
        cache.save(snapshot())
        cache.clear()
        assertFalse(cache.file.exists())
        assertNull(cache.load(now = now))
        cache.clear() // clearing twice is harmless
    }

    @Test
    fun distanceAndNearness() {
        // Puerta del Sol to Atocha station is about 1.7 km.
        assertEquals(1.66, StationCache.distanceKm(40.4169, -3.7035, 40.4066, -3.6892), 0.05)
        val saved = snapshot()
        assertTrue(saved.isNear(40.42, -3.70))
        assertFalse(saved.isNear(41.39, 2.17)) // Barcelona
    }

    @Test
    fun saveLeavesNoTemporaryFiles() {
        cache.save(snapshot())
        cache.save(snapshot(fuel = FuelType.E5))
        assertEquals(listOf("nearest-cache.json"), directory.list()!!.toList())
        assertEquals(FuelType.E5, cache.load(now = now)!!.fuel)
    }

    // Widget entries from the cache

    @Test
    fun entryFromSnapshotSetsAsOfAndKeepsTheWidgetOrder() {
        val saved = snapshot()
        val entry = TimelineMapping.entry(now.plusSeconds(600), saved, english)
        assertEquals(now, entry.asOf)
        assertEquals(FuelType.B7, entry.fuel)
        val rows = (entry.content as CheapestNearbyEntry.Content.Stations).rows
        assertEquals(TimelineMapping.rows(saved.stations, FuelType.B7, english), rows)
        assertEquals(listOf("Shell Madrid", "Repsol Madrid", "Blanca Madrid"), rows.map { it.name })
    }

    @Test
    fun emptySnapshotReadsAsUnavailable() {
        val entry = TimelineMapping.entry(now, snapshot(stations = emptyList()))
        assertEquals(CheapestNearbyEntry.Content.Unavailable, entry.content)
    }

    @Test
    fun failedFetchFallsBackToAFreshCache() {
        val saved = snapshot()
        val entry = TimelineMapping.entry(
            now.plusSeconds(3600), FuelType.B7, Result.failure(IOException("offline")),
            cached = saved, near = Coordinate(40.417, -3.704), locale = english,
        )
        val rows = (entry.content as CheapestNearbyEntry.Content.Stations).rows
        assertEquals(3, rows.size)
        assertEquals(now, entry.asOf)
        // Cached rows still mean the fetch failed, so the widget tries again soon.
        assertEquals(entry.date.plusSeconds(15 * 60), TimelineMapping.nextRefresh(entry))
    }

    @Test
    fun failedFetchWithStaleCacheIsUnavailable() {
        val stale = snapshot(savedAt = now.minusSeconds(25 * 3600))
        val entry = TimelineMapping.entry(
            now, FuelType.B7, Result.failure(IOException("timed out")), cached = stale, near = Coordinate(40.417, -3.704),
        )
        assertEquals(CheapestNearbyEntry.Content.Unavailable, entry.content)
        assertNull(entry.asOf)
    }

    @Test
    fun fallbackNeedsTheSameFuelAndANearbyPosition() {
        val failure = Result.failure<List<Station>>(IOException("timed out"))
        val otherFuel = TimelineMapping.entry(now, FuelType.E5, failure, cached = snapshot(fuel = FuelType.B7))
        assertEquals(CheapestNearbyEntry.Content.Unavailable, otherFuel.content)
        val farAway = TimelineMapping.entry(now, FuelType.B7, failure, cached = snapshot(), near = Coordinate(41.39, 2.17))
        assertEquals(CheapestNearbyEntry.Content.Unavailable, farAway.content)
        val noCache = TimelineMapping.entry(now, FuelType.B7, failure, cached = null)
        assertEquals(CheapestNearbyEntry.Content.Unavailable, noCache.content)
    }

    @Test
    fun liveResultsIgnoreTheCache() {
        val saved = snapshot()
        val live = TimelineMapping.entry(now, FuelType.B7, Result.success(emptyList()), cached = saved)
        assertEquals(CheapestNearbyEntry.Content.Empty, live.content)
        assertNull(live.asOf)
        val noLocation = TimelineMapping.entry(now, FuelType.B7, null, cached = saved)
        assertEquals(CheapestNearbyEntry.Content.NeedsLocation, noLocation.content)
    }

    @Test
    fun existingEntriesHaveNoAsOf() {
        assertNull(TimelineMapping.sample(now).asOf)
        assertNull(CheapestNearbyEntry(now, FuelType.B7, CheapestNearbyEntry.Content.Empty).asOf)
    }

    @Test
    fun asOfShowsTheTimeTodayAndTheWeekdayBefore() {
        val spanish = Locale.forLanguageTag("es-ES")
        // 1_790_000_000 is 2026-09-21 16:13:20 in Madrid.
        assertEquals("16:13", TimelineMapping.asOfText(now, now.plusSeconds(600), spanish, madrid))
        val yesterday = TimelineMapping.asOfText(now, now.plusSeconds(20 * 3600), spanish, madrid)
        assertTrue(yesterday, yesterday.contains("16:13"))
        assertTrue(yesterday, yesterday.lowercase().contains("lun"))
    }

    @Test
    fun offlineScreenStationsKeepTheWidgetOrder() {
        val saved = snapshot()
        assertEquals(listOf("4711", "3218", "4508"), TimelineMapping.topStations(saved.stations, saved.fuel).map { it.externalId })
        val ev = snapshot(fuel = FuelType.EV, stations = Fixture.stations("nearest-ev").reversed())
        assertEquals(listOf(360.0, 50.0, 7.0), TimelineMapping.topStations(ev.stations, ev.fuel).map { it.powerKw })
    }

    // App prefetch throttle

    @Test
    fun prefetchThrottle() {
        val last = NearbyPrefetcher.Attempt(now, 40.4169, -3.7035)
        fun check(afterSeconds: Long, latitude: Double = 40.4169, longitude: Double = -3.7035) =
            NearbyPrefetcher.shouldFetch(latitude, longitude, now.plusSeconds(afterSeconds), last)
        assertTrue(NearbyPrefetcher.shouldFetch(40.4, -3.7, now, null))
        assertFalse("same place, 5 minutes later", check(5 * 60))
        assertTrue("15 minutes later", check(15 * 60))
        assertFalse("moved about 450 m", check(5 * 60, latitude = 40.4210))
        assertTrue("moved about 1.7 km", check(2 * 60, latitude = 40.4066, longitude = -3.6892))
        assertFalse("moved, but within a minute", check(30, latitude = 40.4066, longitude = -3.6892))
        assertTrue("the clock moved back", check(-3600))
    }
}
