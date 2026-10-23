package com.pumperly.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's own store, as the first-run check and the widget open it at launch. */
@RunWith(RobolectricTestRunner::class)
class SharedSettingsAppStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aCorruptSettingsFileDoesNotBreakLaunch() = runTest {
        val file = File(app.filesDir, "datastore/shared_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        val settings = SharedSettings.forContext(app)
        assertEquals(FuelType.B7, settings.fuel())
        assertFalse(settings.hasChosenFuel())
    }
}
