package com.pumperly.app.core

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Ported in spirit from the iOS OneShotLocator: never prompts, recent fixes only, and a timeout ends the wait. */
@RunWith(RobolectricTestRunner::class)
class LocatorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)

    @Test
    fun aFixMustBeUnderFifteenMinutesOld() {
        val now = 1_790_000_000_000L
        assertTrue(Locator.isRecent(now - 14 * 60_000, now))
        assertFalse(Locator.isRecent(now - 15 * 60_000, now))
        assertFalse("a fix from the future", Locator.isRecent(now + 60_000, now))
    }

    @Test
    fun withoutPermissionTheAnswerIsDenied() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(Locator.Outcome.Denied, Locator.locate(app))
    }

    @Test
    fun aRecentLastKnownFixIsUsedAtOnce() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val fix = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 40.4169
            longitude = -3.7035
            time = System.currentTimeMillis() - 60_000
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
        assertEquals(Locator.Outcome.Fix(40.4169, -3.7035), Locator.locate(app))
    }

    @Test
    fun anOldFixWithNoProviderIsUnavailable() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val old = Location(LocationManager.PASSIVE_PROVIDER).apply {
            latitude = 40.4169
            longitude = -3.7035
            time = System.currentTimeMillis() - 60 * 60_000
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.PASSIVE_PROVIDER, old)
        assertEquals(Locator.Outcome.Unavailable, Locator.locate(app))
    }

    @Test
    fun noAnswerFromTheProviderTimesOutAsUnavailable() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        assertEquals(Locator.Outcome.Unavailable, Locator.locate(app, Duration.ofMillis(300)))
    }
}
