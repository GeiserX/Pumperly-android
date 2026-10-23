package com.pumperly.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pumperly.app.core.FuelType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The picker on its own, with its state passed in. */
@RunWith(RobolectricTestRunner::class)
class FuelPickerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<FuelType>()
    private var doneTaps = 0

    private fun show(selected: FuelType = FuelType.B7, isFirstRun: Boolean) {
        compose.setContent {
            SettingsTheme {
                FuelPickerScreen(
                    selected = selected,
                    isFirstRun = isFirstRun,
                    onSelect = { picked += it },
                    onDone = { doneTaps++ },
                )
            }
        }
    }

    private fun scrollTo(fuel: FuelType) =
        compose.onNodeWithTag(FuelPickerTags.LIST).performScrollToNode(hasTestTag(FuelPickerTags.fuel(fuel)))

    @Test
    fun firstRunShowsTheIntroAndEveryFuel() {
        show(isFirstRun = true)
        compose.onNodeWithTag(FuelPickerTags.INTRO).assertIsDisplayed()
        for (fuel in FuelType.entries) {
            scrollTo(fuel)
            compose.onNodeWithTag(FuelPickerTags.fuel(fuel)).assertIsDisplayed()
        }
    }

    @Test
    fun aLaterVisitHasNoIntro() {
        show(isFirstRun = false)
        compose.onNodeWithTag(FuelPickerTags.INTRO).assertDoesNotExist()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun onlyTheChosenFuelIsSelected() {
        show(selected = FuelType.B10, isFirstRun = false)
        scrollTo(FuelType.B10)
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.B10)).assertIsSelected()
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.B7)).assertIsNotSelected()
        scrollTo(FuelType.EV)
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.EV)).assertIsNotSelected()
    }

    @Test
    fun tappingAFuelReportsItAndDoneReportsDone() {
        show(isFirstRun = true)
        scrollTo(FuelType.EV)
        compose.onNodeWithTag(FuelPickerTags.fuel(FuelType.EV)).performClick()
        compose.onNodeWithTag(FuelPickerTags.DONE).performClick()
        assertEquals(listOf(FuelType.EV), picked)
        assertEquals(1, doneTaps)
    }
}
