package com.pumperly.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.Coordinate
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Draws the widget in both sizes for each of the five states and checks what a person would see and tap. */
@RunWith(RobolectricTestRunner::class)
class CheapestNearbyWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val english = Locale.US
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val madrid = Coordinate(40.417, -3.704)
    private val sizes = listOf(CheapestNearbyWidget.SMALL, CheapestNearbyWidget.MEDIUM)

    /** One refresh with a fake API, as the worker would store it. */
    private fun state(
        fuel: FuelType = FuelType.B7,
        position: Coordinate? = madrid,
        api: FakeNearestApi = FakeNearestApi(Fixture.stations("nearest-b7")),
        cachedAt: Instant? = null,
    ): CheapestNearbyEntry = runBlocking {
        val cache = StationCache(Files.createTempDirectory("widget-render").toFile())
        if (cachedAt != null) {
            cache.save(StationCache.Snapshot(cachedAt, fuel, madrid.latitude, madrid.longitude, Fixture.stations("nearest-b7")))
        }
        WidgetState.refresh(now, fuel, position, api, cache).entry(english)!!
    }

    private fun render(entry: CheapestNearbyEntry?, size: DpSize, checks: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(size)
            provideComposable { CheapestNearbyContent(entry, entry?.fuel ?: FuelType.B7) }
            checks()
        }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun smallShowsTheCheapestStationAndOpensIt() {
        val entry = state()
        val url = (entry.content as CheapestNearbyEntry.Content.Stations).rows.first().url
        render(entry, CheapestNearbyWidget.SMALL) {
            onNode(hasText("€1.949")).assertExists()
            onNode(hasText("Shell Madrid")).assertExists()
            onNode(hasText("1.5 km")).assertExists()
            onNode(hasText("Diesel")).assertExists()
            onNode(hasText("Repsol Madrid")).assertDoesNotExist()
            onNode(hasStartActivityClickAction(WidgetIntents.page(context, url))).assertExists()
        }
    }

    @Test
    fun mediumShowsThreeTappableRowsUnderAFuelHeaderThatOpensSettings() {
        val entry = state()
        val rows = (entry.content as CheapestNearbyEntry.Content.Stations).rows
        render(entry, CheapestNearbyWidget.MEDIUM) {
            onNode(hasText(string(com.pumperly.app.R.string.widget_title))).assertExists()
            rows.forEachIndexed { index, row ->
                onNode(hasText(row.name)).assertExists()
                // The cheapest station is also where a tap on the rest of the widget goes.
                onAllNodes(hasStartActivityClickAction(WidgetIntents.page(context, row.url)))
                    .assertCountEquals(if (index == 0) 2 else 1)
            }
            onAllNodes(hasText("€1.9")).assertCountEquals(3)
            onAllNodes(hasStartActivityClickAction(WidgetIntents.settings(context))).assertCountEquals(1)
        }
    }

    @Test
    fun evShowsChargerPower() {
        val entry = state(FuelType.EV, api = FakeNearestApi(Fixture.stations("nearest-ev")))
        render(entry, CheapestNearbyWidget.SMALL) {
            onNode(hasText("360 kW")).assertExists()
            onNode(hasText("EV charging")).assertExists()
        }
    }

    @Test
    fun needsLocationAsksToOpenTheAppInBothSizes() {
        val entry = state(position = null)
        for (size in sizes) render(entry, size) {
            onNode(hasText(string(com.pumperly.app.R.string.widget_needs_location))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.home(context))).assertExists()
        }
    }

    @Test
    fun emptyOpensTheFuelSettingInBothSizes() {
        val entry = state(api = FakeNearestApi(emptyList()))
        for (size in sizes) render(entry, size) {
            onNode(hasText(string(com.pumperly.app.R.string.widget_empty))).assertExists()
            // The whole widget opens the fuel setting; in 4x2 the fuel header does too.
            onAllNodes(hasStartActivityClickAction(WidgetIntents.settings(context)))
                .assertCountEquals(if (size == CheapestNearbyWidget.MEDIUM) 2 else 1)
        }
    }

    @Test
    fun unavailableSaysItWillTryAgainInBothSizes() {
        val entry = state(api = FakeNearestApi(fail = true))
        for (size in sizes) render(entry, size) {
            onNode(hasText(string(com.pumperly.app.R.string.widget_unavailable))).assertExists()
            onNode(hasText("€1.949")).assertDoesNotExist()
        }
    }

    @Test
    fun cachedShowsTheStationsWithTheTimeTheyWereFetched() {
        val entry = state(api = FakeNearestApi(fail = true), cachedAt = now.minus(Duration.ofMinutes(30)))
        val asOf = TimelineMapping.asOfText(now.minus(Duration.ofMinutes(30)), now = entry.date, locale = english)
        for (size in sizes) render(entry, size) {
            onNode(hasText("€1.949")).assertExists()
            // The time is its own text, so a long fuel name is cut before it is.
            onNode(hasTextEqualTo(" · 🕒 $asOf")).assertExists()
            onNode(hasText(string(com.pumperly.app.R.string.widget_unavailable))).assertDoesNotExist()
        }
    }

    @Test
    fun beforeTheFirstRefreshItSaysItIsUpdating() {
        for (size in sizes) render(null, size) {
            onNode(hasText(string(com.pumperly.app.R.string.widget_loading))).assertExists()
            onAllNodes(hasStartActivityClickAction(WidgetIntents.home(context))).assertCountEquals(1)
        }
    }
}
