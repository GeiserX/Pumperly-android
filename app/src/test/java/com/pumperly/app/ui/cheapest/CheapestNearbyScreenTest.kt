package com.pumperly.app.ui.cheapest

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.R
import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.TimelineMapping
import java.io.IOException
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The native "Cheapest nearby" screen: the widget's three rows, and a way out of every other state. */
@RunWith(RobolectricTestRunner::class)
class CheapestNearbyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val opened = mutableListOf<String>()
    private val actions = mutableListOf<String>()

    private fun show(state: CheapestNearbyUiState) {
        compose.setContent {
            MaterialTheme {
                CheapestNearbyScreen(
                    state = state,
                    onOpenStation = { opened += it },
                    onRetry = { actions += "retry" },
                    onChangeFuel = { actions += "fuel" },
                    onOpenApp = { actions += "app" },
                )
            }
        }
    }

    private fun entry(result: Result<List<com.pumperly.app.core.Station>>?) =
        TimelineMapping.entry(now, FuelType.B7, result, Locale.US)

    @Test
    fun stationsShowThreeRowsThatOpenTheirStation() {
        val entry = entry(Result.success(Fixture.stations("nearest-b7")))
        show(CheapestNearbyUiState.Ready(entry))
        compose.onAllNodesWithTag("row").assertCountEquals(3)
        compose.onNodeWithText("€1.949").assertExists()
        compose.onNodeWithText("Shell Madrid").performClick()
        assertEquals((entry.content as CheapestNearbyEntry.Content.Stations).rows.first().url, opened.single())
    }

    @Test
    fun loadingShowsProgressAndTheFuel() {
        show(CheapestNearbyUiState.Loading(FuelType.E5))
        compose.onNodeWithTag("loading").assertExists()
        compose.onNodeWithText(context.getString(R.string.fuel_E5)).assertExists()
    }

    @Test
    fun needsLocationOffersToOpenTheApp() {
        show(CheapestNearbyUiState.Ready(entry(null)))
        compose.onNodeWithText(context.getString(R.string.widget_needs_location)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cheapest_open_app)).performClick()
        assertEquals(listOf("app"), actions)
    }

    @Test
    fun noFixWithLocationAllowedOffersARetryNotTheApp() {
        show(CheapestNearbyUiState.LocationUnavailable(FuelType.B7))
        compose.onNodeWithText(context.getString(R.string.cheapest_location_unavailable)).assertExists()
        compose.onNodeWithText(context.getString(R.string.widget_needs_location)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cheapest_retry)).performClick()
        assertEquals(listOf("retry"), actions)
    }

    @Test
    fun emptyOffersAnotherFuel() {
        show(CheapestNearbyUiState.Ready(entry(Result.success(emptyList()))))
        compose.onNodeWithText(context.getString(R.string.widget_empty)).assertExists()
        compose.onAllNodesWithTag("row").assertCountEquals(0)
    }

    @Test
    fun unavailableOffersARetry() {
        show(CheapestNearbyUiState.Ready(entry(Result.failure(IOException("offline")))))
        compose.onNodeWithText(context.getString(R.string.widget_unavailable)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cheapest_retry)).performClick()
        assertEquals(listOf("retry"), actions)
    }

    @Test
    fun cachedRowsSayWhenTheyWereFetched() {
        val live = entry(Result.success(Fixture.stations("nearest-b7")))
        val cached = live.copy(asOf = now.minusSeconds(1800))
        show(CheapestNearbyUiState.Ready(cached))
        val asOf = TimelineMapping.asOfText(now.minusSeconds(1800), now = now, locale = Locale.US)
        compose.onNodeWithText(context.getString(R.string.widget_as_of, asOf)).assertExists()
        compose.onAllNodesWithTag("row").assertCountEquals(3)
    }
}
