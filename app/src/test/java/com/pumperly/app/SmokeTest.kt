package com.pumperly.app

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the toolchain the native lanes build on: each test fails if its
 * piece (plain JVM tests, the serialization plugin, Robolectric, the Compose
 * compiler, Glance) is missing or misconfigured.
 */
class SmokeTest {
    @Serializable
    private data class Probe(val fuel: String, val price: Double)

    @Test
    fun applicationIdIsPumperly() {
        assertEquals("com.pumperly.app", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun serializationPluginGeneratesSerializers() {
        val json = Json.encodeToString(Probe.serializer(), Probe("B7", 1.459))
        assertEquals("""{"fuel":"B7","price":1.459}""", json)
        assertEquals(Probe("B7", 1.459), Json.decodeFromString(Probe.serializer(), json))
    }
}

@RunWith(RobolectricTestRunner::class)
class AndroidSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun robolectricLoadsTheAppResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.pumperly.app", context.packageName)
        assertEquals("Pumperly", context.getString(R.string.app_name))
    }

    @Test
    fun composeRendersOnRobolectric() {
        compose.setContent { Text("Cheapest nearby") }
        compose.onNodeWithText("Cheapest nearby").assertIsDisplayed()
    }

    @Test
    fun glanceRendersOnRobolectric() = runGlanceAppWidgetUnitTest {
        provideComposable { androidx.glance.text.Text("B7 1.459") }
        onNode(hasText("B7 1.459")).assertExists()
    }
}
