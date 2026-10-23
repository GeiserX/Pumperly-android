package com.pumperly.app.ui.settings

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pumperly.app.R
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.SharedSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An application that listens for fuel changes, as the app's own will. */
class ListeningApp : Application(), WidgetFuelListener {
    val changes = mutableListOf<FuelType>()
    override fun onWidgetFuelChanged(fuel: FuelType) {
        changes += fuel
    }
}

/**
 * The screen against a real DataStore file: what it shows on the first launch, and what it writes.
 * The manifest entry for the activity comes with the app wiring, so the test registers it itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ListeningApp::class)
class SettingsActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: ListeningApp = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SharedSettings

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("settings-activity").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SharedSettings(SharedSettings.store(scope) { File(directory, "settings.preferences_pb") })
        SettingsActivity.settingsProvider = { settings }
        shadowOf(app.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = SettingsActivity::class.java.name
                packageName = app.packageName
                theme = R.style.Theme_Pumperly
            },
        )
    }

    @After
    fun tearDown() {
        SettingsActivity.settingsProvider = SharedSettings::forContext
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun launch(): ActivityScenario<SettingsActivity> =
        ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(app)).also { compose.waitForIdle() }

    /** Lets the main looper run (Robolectric pauses it) until [condition] holds. */
    private fun waitFor(what: String, condition: suspend () -> Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { condition() }
        }
        assertTrue(what, runBlocking { condition() })
    }

    @Test
    fun theFirstLaunchShowsThePicker() {
        assertTrue(runBlocking { SettingsActivity.needsFirstRun(settings) })
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(FuelPickerTags.INTRO) }
        compose.onNodeWithTag(FuelPickerTags.INTRO).assertIsDisplayed()
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.B7)).assertIsSelected()
    }

    @Test
    fun choosingB10PersistsItAndMarksTheFuelChosen() {
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(FuelPickerTags.LIST) }
        compose.onNodeWithTag(FuelPickerTags.LIST)
            .performScrollToNode(hasTestTag(FuelPickerTags.fuel(FuelType.B10)))
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.B10)).performClick()

        waitFor("B10 is saved") { settings.fuel() == FuelType.B10 }
        waitFor("the fuel is marked chosen") { settings.hasChosenFuel() }
        waitFor("the app hears about it") { app.changes == listOf(FuelType.B10) }
        assertFalse(runBlocking { SettingsActivity.needsFirstRun(settings) })
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.B10)).assertIsSelected()
    }

    @Test
    fun aLaterLaunchHasNoIntro() {
        runBlocking { settings.setHasChosenFuel(true) }
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(FuelPickerTags.LIST) }
        compose.onNodeWithTag(FuelPickerTags.INTRO).assertDoesNotExist()
    }

    @Test
    fun doneKeepsTheDefaultAndCloses() {
        val scenario = launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(FuelPickerTags.DONE) }
        compose.onNodeWithTag(FuelPickerTags.DONE).performClick()

        waitFor("the default counts as chosen") { settings.hasChosenFuel() }
        assertEquals(FuelType.B7, runBlocking { settings.fuel() })
        assertTrue(app.changes.isEmpty())
        waitFor("the screen closes") { scenario.isFinishing() }
    }

    @Test
    fun goingBackAlsoCountsAsChosen() {
        val scenario = launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(FuelPickerTags.LIST) }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("back closes the screen") { scenario.isFinishing() }
        assertFalse(runBlocking { settings.hasChosenFuel() })
        // Robolectric does not stop a finishing activity by itself; closing the scenario does.
        scenario.close()
        waitFor("back counts as chosen") { settings.hasChosenFuel() }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

private fun ActivityScenario<SettingsActivity>.isFinishing(): Boolean {
    var finishing = false
    onActivity { finishing = it.isFinishing }
    return finishing
}
