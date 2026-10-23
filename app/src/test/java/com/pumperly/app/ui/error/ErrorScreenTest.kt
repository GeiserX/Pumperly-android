package com.pumperly.app.ui.error

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.Station
import com.pumperly.app.core.StationCache
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The three error screens rendered for real: which ones list cached stations, and what the rows do. */
@RunWith(RobolectricTestRunner::class)
class ErrorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val madrid = ZoneId.of("Europe/Madrid")
    private val opened = mutableListOf<Station>()
    private var actions = 0

    private fun snapshot(fuel: FuelType = FuelType.B7, name: String = "nearest-b7") =
        StationCache.Snapshot(now, fuel, 40.4169, -3.7035, Fixture.stations(name))

    private var shown: MutableState<Pair<ErrorScreenKind, StationCache.Snapshot?>>? = null

    /** The first call sets the content; later calls swap the state, since a rule takes one setContent. */
    private fun show(kind: ErrorScreenKind, snapshot: StationCache.Snapshot?) {
        shown?.let { it.value = kind to snapshot; compose.waitForIdle(); return }
        val state = mutableStateOf(kind to snapshot)
        shown = state
        compose.setContent {
            ErrorContent(
                kind = state.value.first,
                snapshot = state.value.second,
                onAction = { actions++ },
                now = now.plusSeconds(60),
                zone = madrid,
                onOpenMap = { _, station -> opened += station },
            )
        }
    }

    private fun tagged(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun rowTags(): List<String> = compose.onAllNodes(tagged(TAG_ROW_PREFIX)).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix(TAG_ROW_PREFIX) }

    @Test
    fun offlineWithACacheListsThreeRowsUnderTheHeader() {
        show(ErrorScreenKind.OFFLINE, snapshot())
        compose.onNodeWithText("No Connection").assertIsDisplayed()
        compose.onNodeWithTag(TAG_HEADER).assertIsDisplayed()
        val expected = OfflineStations.header(app.resources, snapshot(), now.plusSeconds(60), Locale.getDefault(), madrid)
        compose.onNodeWithText(expected).assertIsDisplayed()
        assertEquals(listOf("4711", "3218", "4508"), rowTags())
        // Robolectric's default screen is 320x470 dp, so the button sits below the rows.
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sslAndPageScreensListNoStationsEvenWithACache() {
        for ((kind, title) in listOf(ErrorScreenKind.SSL to "Connection Not Secure", ErrorScreenKind.PAGE to "Page Not Available")) {
            show(kind, snapshot())
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithTag(TAG_LIST).assertDoesNotExist()
            assertEquals(emptyList<String>(), rowTags())
        }
    }

    @Test
    fun offlineWithoutUsableRowsShowsNoList() {
        show(ErrorScreenKind.OFFLINE, null)
        compose.onNodeWithTag(TAG_LIST).assertDoesNotExist()
        // A priced fuel with no priced station (EV chargers have no price) leaves no rows to show.
        show(ErrorScreenKind.OFFLINE, StationCache.Snapshot(now, FuelType.B7, 40.4, -3.7, Fixture.stations("nearest-ev")))
        compose.onNodeWithTag(TAG_LIST).assertDoesNotExist()
    }

    @Test
    fun evRowsShowChargerPower() {
        show(ErrorScreenKind.OFFLINE, snapshot(FuelType.EV, "nearest-ev"))
        compose.onNodeWithText("Last known chargers nearby", substring = true).assertIsDisplayed()
        compose.onNodeWithText("360 kW").assertExists()
    }

    @Test
    fun mapButtonOpensThatRowsStation() {
        show(ErrorScreenKind.OFFLINE, snapshot())
        compose.onNodeWithTag(TAG_MAP_PREFIX + "3218").performScrollTo().performClick()
        assertEquals(listOf("3218"), opened.map { it.externalId })
    }

    @Test
    fun actionButtonRunsTheAction() {
        show(ErrorScreenKind.SSL, null)
        compose.onNodeWithText("Go Back").performClick()
        assertEquals(1, actions)
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishScreenUsesSpanishStrings() {
        show(ErrorScreenKind.OFFLINE, snapshot())
        compose.onNodeWithText("Sin conexión").assertIsDisplayed()
        compose.onNodeWithText("Lo más barato cerca, último dato:", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TAG_MAP_PREFIX + "4711").assertExists()
        assertEquals(3, compose.onAllNodesWithText("Mapa").fetchSemanticsNodes().size)
    }

    @Test
    fun theStatefulScreenLoadsTheCacheOnlyWhenOffline() {
        val loads = mutableListOf<ErrorScreenKind>()
        val kind = mutableStateOf(ErrorScreenKind.SSL)
        val cached = StationCache.Snapshot(Instant.now(), FuelType.B7, 40.4169, -3.7035, Fixture.stations("nearest-b7"))
        compose.setContent {
            val current = kind.value
            ErrorScreen(kind = current, onAction = {}, loadSnapshot = { loads += current; cached })
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_LIST).assertDoesNotExist()
        assertEquals(emptyList<ErrorScreenKind>(), loads)

        kind.value = ErrorScreenKind.OFFLINE
        compose.waitUntil(5_000) { rowTags().size == 3 }
        assertEquals(listOf(ErrorScreenKind.OFFLINE), loads)
    }
}
