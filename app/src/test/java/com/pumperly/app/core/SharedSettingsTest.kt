package com.pumperly.app.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Ported from pumperly-ios PumperlyTests/SharedSettingsTests.swift (5 tests). */
class SharedSettingsTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SharedSettings
    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") }
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("shared-settings").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SharedSettings(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun fuelDefaultsToDieselAndPersists() = runTest {
        assertEquals(FuelType.B7, settings.fuel())
        settings.setFuel(FuelType.EV)
        assertEquals(FuelType.EV, SharedSettings(store).fuel())
    }

    @Test
    fun firstRunFlagAndReset() = runTest {
        assertFalse(settings.hasChosenFuel())
        settings.setFuel(FuelType.E5)
        settings.setHasChosenFuel(true)
        settings.saveLocation(40.0, -3.0)
        settings.reset()
        assertFalse(settings.hasChosenFuel())
        assertEquals(FuelType.B7, settings.fuel())
        assertNull(settings.lastLocation())
    }

    @Test
    fun clearLocationKeepsTheFuel() = runTest {
        settings.setFuel(FuelType.LPG)
        settings.saveLocation(40.0, -3.0)
        settings.clearLocation()
        assertNull(settings.lastLocation())
        assertEquals(FuelType.LPG, settings.fuel())
    }

    @Test
    fun unknownStoredFuelFallsBackToDefault() = runTest {
        store.edit { it[SharedSettings.FUEL] = "KEROSENE" }
        assertEquals(FuelType.B7, SharedSettings(store).fuel())
    }

    @Test
    fun savedLocationIsRoundedAndExpires() = runTest {
        val saved = Instant.ofEpochSecond(1_790_000_000)
        settings.saveLocation(40.416775, -3.703790, at = saved)
        val location = settings.lastLocation(now = saved.plusSeconds(60))!!
        assertEquals(40.417, location.latitude, 0.0)
        assertEquals(-3.704, location.longitude, 0.0)
        assertNull(settings.lastLocation(now = saved.plus(SharedSettings.LAST_LOCATION_MAX_AGE).plusSeconds(1)))
    }
}
