package com.pumperly.app.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The prefetcher end to end with a fake API: the throttle, one request at a time, and what lands in the cache. */
class NearbyPrefetcherTest {
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SharedSettings
    private lateinit var cache: StationCache
    private val calls = mutableListOf<Triple<Double, Double, FuelType>>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("prefetcher").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SharedSettings(SharedSettings.store(scope) { File(directory, "s.preferences_pb") })
        cache = StationCache(directory)
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun prefetcher(api: NearestApi) = NearbyPrefetcher(settings, cache, api, scope)

    private val fixtureApi = NearestApi { lat, lon, fuel ->
        synchronized(calls) { calls += Triple(lat, lon, fuel) }
        Fixture.stations("nearest-b7")
    }

    @Test
    fun firstFixFetchesTheWidgetFuelAndCachesIt() = runTest {
        settings.setFuel(FuelType.B10)
        prefetcher(fixtureApi).positionDelivered(40.416775, -3.703790, now)!!.join()
        assertEquals(listOf(Triple(40.416775, -3.703790, FuelType.B10)), calls)
        val saved = cache.load(now = now)!!
        assertEquals(FuelType.B10, saved.fuel)
        assertEquals(now, saved.savedAt)
        assertEquals(40.417, saved.latitude, 0.0)
        assertEquals(5, saved.stations.size)
    }

    @Test
    fun aSecondFixInThePlaceIsThrottled() = runTest {
        val prefetcher = prefetcher(fixtureApi)
        prefetcher.positionDelivered(40.4169, -3.7035, now)!!.join()
        assertNull(prefetcher.positionDelivered(40.4169, -3.7035, now.plusSeconds(5 * 60)))
        prefetcher.positionDelivered(40.4169, -3.7035, now.plusSeconds(15 * 60))!!.join()
        assertEquals(2, calls.size)
    }

    @Test
    fun onlyOneRequestAtATime() = runTest {
        val release = CompletableDeferred<Unit>()
        val prefetcher = prefetcher { _, _, _ ->
            release.await()
            Fixture.stations("nearest-b7")
        }
        val first = prefetcher.positionDelivered(40.4169, -3.7035, now)!!
        // Far away and much later would be due, but the first request is still running.
        assertNull(prefetcher.positionDelivered(41.39, 2.17, now.plusSeconds(3600)))
        release.complete(Unit)
        first.join()
        assertNotNull(prefetcher.positionDelivered(41.39, 2.17, now.plusSeconds(3600)))
    }

    @Test
    fun aFailedFetchKeepsTheOldCache() = runTest {
        val old = StationCache.Snapshot(now.minusSeconds(600), FuelType.B7, 40.417, -3.704, Fixture.stations("nearest-b7"))
        cache.save(old)
        prefetcher { _, _, _ -> throw IOException("offline") }.positionDelivered(40.4169, -3.7035, now)!!.join()
        assertEquals(old, cache.load(now = now))
    }

    @Test
    fun aBrokenSettingsStoreDoesNotEscapeTheJob() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("corrupt preferences") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("corrupt preferences")
        }
        val job = NearbyPrefetcher(SharedSettings(broken), cache, fixtureApi, scope).positionDelivered(40.4169, -3.7035, now)!!
        job.join()
        // A job that threw ends cancelled; this one must end normally, with nothing fetched.
        assertFalse(job.isCancelled)
        assertEquals(0, calls.size)
    }
}
