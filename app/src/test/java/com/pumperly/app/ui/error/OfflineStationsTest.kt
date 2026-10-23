package com.pumperly.app.ui.error

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.StationCache
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Ported from the iOS StationCacheTests "Offline screen" section, with the Maps link as a geo: URI. */
@RunWith(RobolectricTestRunner::class)
class OfflineStationsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 2026-09-21 16:13:20 in Madrid, a Monday. */
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun snapshot(fuel: FuelType = FuelType.B7, name: String = "nearest-b7", savedAt: Instant = now) =
        StationCache.Snapshot(savedAt, fuel, 40.4169, -3.7035, Fixture.stations(name))

    @Test
    fun onlyTheOfflineScreenShowsTheCache() {
        assertTrue(OfflineStations.showsCache(ErrorScreenKind.OFFLINE))
        assertFalse(OfflineStations.showsCache(ErrorScreenKind.SSL))
        assertFalse(OfflineStations.showsCache(ErrorScreenKind.PAGE))
    }

    @Test
    fun listsUpToThreeStationsInWidgetOrder() {
        assertEquals(listOf("4711", "3218", "4508"), OfflineStations.stations(snapshot()).map { it.externalId })
        val ev = StationCache.Snapshot(now, FuelType.EV, 40.4169, -3.7035, Fixture.stations("nearest-ev").reversed())
        assertEquals(listOf(360.0, 50.0, 7.0), OfflineStations.stations(ev).map { it.powerKw })
    }

    @Test
    fun headerNamesTheTimeAloneForToday() {
        val header = OfflineStations.header(app.resources, snapshot(), now.plusSeconds(60), Locale.US, madrid)
        assertTrue(header, header.startsWith("Last known cheapest nearby, as of "))
        assertFalse(header, header.contains("%"))
        assertTrue(header, header.endsWith("4:13\u202FPM") || header.endsWith("4:13 PM"))
    }

    @Test
    @Config(qualifiers = "es")
    fun headerAddsTheWeekdayForAnOlderDay() {
        val spanish = Locale.forLanguageTag("es-ES")
        assertEquals(
            "Lo más barato cerca, último dato: 16:13",
            OfflineStations.header(app.resources, snapshot(), now.plusSeconds(600), spanish, madrid),
        )
        val older = OfflineStations.header(app.resources, snapshot(), now.plusSeconds(20 * 3600), spanish, madrid)
        assertTrue(older, older.startsWith("Lo más barato cerca, último dato: "))
        assertTrue(older, older.contains("16:13"))
        assertTrue(older, older.lowercase(spanish).contains("lun"))
    }

    @Test
    fun evHeaderTalksAboutChargers() {
        val ev = snapshot(FuelType.EV, "nearest-ev")
        val header = OfflineStations.header(app.resources, ev, now.plusSeconds(60), Locale.US, madrid)
        assertTrue(header, header.startsWith("Last known chargers nearby, as of "))
    }

    @Test
    fun mapButtonOpensAGeoUriAtTheStation() {
        val station = Fixture.stations("nearest-b7").first()
        assertEquals("geo:0,0?q=40.405278,-3.703139(Blanca%20Madrid)", OfflineStations.geoUri(station))
    }

    @Test
    fun geoUriEncodesTheLabelSoItCannotBreakTheQuery() {
        val station = Fixture.stations("nearest-b7").first().copy(name = "Gas (24h) & Café")
        assertEquals("geo:0,0?q=40.405278,-3.703139(Gas%20%2824h%29%20%26%20Caf%C3%A9)", OfflineStations.geoUri(station))
        val blank = station.copy(name = "  ")
        assertTrue(OfflineStations.geoUri(blank).endsWith("(Blanca)"))
    }

    @Test
    fun openMapStartsAViewIntentWithTheGeoUri() {
        val station = Fixture.stations("nearest-b7").first()
        openMap(app, station)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(OfflineStations.geoUri(station), started.data.toString())
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun loadReadsTheSharedCacheFileAndIgnoresAStaleOne() {
        val cache = StationCache.forContext(app)
        cache.save(snapshot(savedAt = Instant.now().minusSeconds(20 * 60)))
        assertEquals(3, OfflineStations.stations(OfflineStations.load(app)!!).size)
        cache.save(snapshot(savedAt = Instant.now().minusSeconds(25 * 3600)))
        assertEquals(null, OfflineStations.load(app))
        cache.clear()
    }
}
