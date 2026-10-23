package com.pumperly.app.widget

import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.Coordinate
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The five widget states from the iOS fixtures: stations, needsLocation, empty, unavailable and
 * cached, as one refresh produces them and as the widget later reads them back.
 */
class WidgetStateTest {
    private val english = Locale.US
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val madrid = Coordinate(40.417, -3.704)
    private lateinit var directory: File
    private lateinit var cache: StationCache

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("widget-state").toFile()
        cache = StationCache(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun content(state: WidgetState) = state.entry(english)!!.content

    @Test
    fun stationsAreTheCheapestThreeAndAreCached() = runTest {
        val api = FakeNearestApi(Fixture.stations("nearest-b7"))
        val state = WidgetState.refresh(now, FuelType.B7, madrid, api, cache)
        assertEquals(WidgetState.Kind.STATIONS, state.kind)
        assertEquals(listOf("4711", "3218", "4508"), state.stations.map { it.externalId })
        val rows = (content(state) as CheapestNearbyEntry.Content.Stations).rows
        assertEquals(listOf("Shell Madrid", "Repsol Madrid", "Blanca Madrid"), rows.map { it.name })
        assertEquals(listOf("€1.949", "€1.955", "€1.969"), rows.map { it.valueText })
        assertNull(state.entry(english)!!.asOf)
        // Asked with the saved position and the widget fuel.
        assertEquals(listOf(Triple(40.417, -3.704, FuelType.B7)), api.calls)
        // The full answer is cached for the offline screen.
        assertEquals(5, cache.load(FuelType.B7, now = now)!!.stations.size)
    }

    @Test
    fun evStationsAreTheNearestWithTheirPower() = runTest {
        val api = FakeNearestApi(Fixture.stations("nearest-ev").reversed())
        val state = WidgetState.refresh(now, FuelType.EV, madrid, api, cache)
        val rows = (content(state) as CheapestNearbyEntry.Content.Stations).rows
        assertEquals(listOf("360 kW", "50 kW", "7 kW"), rows.map { it.valueText })
        assertEquals(FuelType.EV, state.fuelType)
    }

    @Test
    fun noPositionNeedsLocationAndSendsNothing() = runTest {
        val api = FakeNearestApi(Fixture.stations("nearest-b7"))
        val state = WidgetState.refresh(now, FuelType.B7, null, api, cache)
        assertEquals(WidgetState.Kind.NEEDS_LOCATION, state.kind)
        assertEquals(CheapestNearbyEntry.Content.NeedsLocation, content(state))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun nothingSellingThisFuelIsEmpty() = runTest {
        val unpriced = Fixture.stations("nearest-b7").map { it.copy(price = null) }
        val state = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(unpriced), cache)
        assertEquals(WidgetState.Kind.EMPTY, state.kind)
        assertEquals(CheapestNearbyEntry.Content.Empty, content(state))
    }

    @Test
    fun aFailureWithNoCacheIsUnavailable() = runTest {
        val state = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)
        assertEquals(WidgetState.Kind.UNAVAILABLE, state.kind)
        assertEquals(CheapestNearbyEntry.Content.Unavailable, content(state))
    }

    @Test
    fun aFailureShowsTheCachedStationsWithTheirTime() = runTest {
        val savedAt = now.minus(Duration.ofHours(2))
        cache.save(StationCache.Snapshot(savedAt, FuelType.B7, 40.418, -3.705, Fixture.stations("nearest-b7")))
        val state = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)
        assertEquals(WidgetState.Kind.CACHED, state.kind)
        val entry = state.entry(english)!!
        assertEquals(savedAt, entry.asOf)
        assertEquals(now, entry.date)
        val rows = (entry.content as CheapestNearbyEntry.Content.Stations).rows
        assertEquals("Shell Madrid", rows.first().name)
        assertEquals(3, rows.size)
    }

    @Test
    fun theCacheIsIgnoredForAnotherFuelOrFarAway() = runTest {
        cache.save(StationCache.Snapshot(now, FuelType.E5, 40.418, -3.705, Fixture.stations("nearest-b7")))
        val otherFuel = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)
        assertEquals(WidgetState.Kind.UNAVAILABLE, otherFuel.kind)

        cache.save(StationCache.Snapshot(now, FuelType.B7, 41.388, 2.170, Fixture.stations("nearest-b7")))
        val barcelona = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)
        assertEquals(WidgetState.Kind.UNAVAILABLE, barcelona.kind)
    }

    @Test
    fun liveResultsIgnoreTheCache() = runTest {
        cache.save(StationCache.Snapshot(now, FuelType.B7, 40.418, -3.705, Fixture.stations("nearest-b7")))
        val state = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(emptyList()), cache)
        assertEquals(WidgetState.Kind.EMPTY, state.kind)
        assertNull(state.asOfMillis)
    }

    @Test
    fun refreshIsHourlyAndSoonerAfterAFailureOrACachedAnswer() = runTest {
        cache.save(StationCache.Snapshot(now, FuelType.B7, 40.418, -3.705, Fixture.stations("nearest-b7")))
        val good = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(Fixture.stations("nearest-b7")), cache)
        val cached = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)
        val noLocation = WidgetState.refresh(now, FuelType.B7, null, FakeNearestApi(), cache)
        StationCache(directory).clear()
        val bad = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(fail = true), cache)

        fun next(state: WidgetState) = Duration.between(now, TimelineMapping.nextRefresh(state.entry(english)!!))
        assertEquals(Duration.ofMinutes(60), next(good))
        assertEquals(Duration.ofMinutes(60), next(noLocation))
        assertEquals(Duration.ofMinutes(15), next(cached))
        assertEquals(Duration.ofMinutes(15), next(bad))
        assertFalse(good.wantsRetry)
        assertFalse(noLocation.wantsRetry)
        assertTrue(cached.wantsRetry)
        assertTrue(bad.wantsRetry)
    }

    @Test
    fun beforeTheFirstRefreshThereIsNoEntry() {
        assertNull(WidgetState().entry(english))
        assertFalse(WidgetState().wantsRetry)
    }

    @Test
    fun anUnknownStoredFuelReadsAsDiesel() {
        assertEquals(FuelType.B7, WidgetState(fuel = "KEROSENE").fuelType)
    }

    @Test
    fun theStoreKeepsTheStateAndRecoversFromCorruption() = runTest {
        val file = File(directory, "widget-state.json")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = WidgetState.refresh(now, FuelType.B7, madrid, FakeNearestApi(Fixture.stations("nearest-b7")), cache)
            WidgetStateDefinition.create(scope) { file }.updateData { state }
            assertTrue(file.length() > 0)
        } finally {
            scope.cancel()
        }
        val reread = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val back = WidgetStateDefinition.create(reread) { file }.data.first()
            assertEquals(WidgetState.Kind.STATIONS, back.kind)
            assertEquals(3, back.stations.size)
        } finally {
            reread.cancel()
        }
        file.writeText("{not json")
        val corrupt = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            assertEquals(WidgetState(), WidgetStateDefinition.create(corrupt) { file }.data.first())
        } finally {
            corrupt.cancel()
        }
    }
}
