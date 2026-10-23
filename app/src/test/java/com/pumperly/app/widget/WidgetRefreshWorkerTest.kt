package com.pumperly.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pumperly.app.core.Fixture
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.SharedSettings
import com.pumperly.app.core.StationCache
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The refresh worker against a fake API and WorkManager's test runtime: what it stores, what it
 * draws, and when it runs next (hourly after a good answer, 15 minutes after a failed or cached one).
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshWorkerTest {
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SharedSettings
    private lateinit var cache: StationCache
    private lateinit var deps: WidgetRefreshWorker.Dependencies
    private val api = FakeNearestApi(Fixture.stations("nearest-b7"))
    private var allowed = true
    private var widgets = true
    private var redraws = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        directory = Files.createTempDirectory("widget-worker").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SharedSettings(SharedSettings.store(scope) { File(directory, "settings.preferences_pb") })
        cache = StationCache(directory)
        deps = WidgetRefreshWorker.Dependencies(
            settings = settings,
            cache = cache,
            api = api,
            store = WidgetStateDefinition.create(scope) { File(directory, "widget-state.json") },
            isLocationAllowed = { allowed },
            now = { now },
            redraw = { redraws++ },
            hasWidgets = { widgets },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun run(tags: List<String> = listOf(WidgetRefreshWorker.NOW)): WidgetState {
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context)
            .setTags(tags)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    WidgetRefreshWorker(appContext, workerParameters, deps)
            })
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        return deps.store.data.first()
    }

    private fun work(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun pending(name: String): List<WorkInfo> = work(name).filter { !it.state.isFinished }

    private fun assertHourly() {
        val periodic = pending(WidgetRefreshWorker.PERIODIC).single()
        assertEquals(Duration.ofMinutes(60).toMillis(), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
    }

    private fun assertRetryIn15Minutes() {
        val retry = pending(WidgetRefreshWorker.RETRY).single()
        assertEquals(WorkInfo.State.ENQUEUED, retry.state)
        assertEquals(Duration.ofMinutes(15).toMillis(), retry.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, retry.constraints.requiredNetworkType)
    }

    @Test
    fun successStoresTheStationsAndRefreshesHourly() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        val state = run()
        assertEquals(WidgetState.Kind.STATIONS, state.kind)
        assertEquals(1, redraws)
        assertHourly()
        assertTrue(pending(WidgetRefreshWorker.RETRY).isEmpty())
    }

    @Test
    fun theWidgetFuelIsWhatItAsksFor() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        settings.setFuel(FuelType.EV)
        api.stations = Fixture.stations("nearest-ev")
        val state = run()
        assertEquals(FuelType.EV, state.fuelType)
        assertEquals(FuelType.EV, api.calls.single().third)
    }

    @Test
    fun failureRetriesIn15Minutes() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        api.fail = true
        val state = run()
        assertEquals(WidgetState.Kind.UNAVAILABLE, state.kind)
        assertRetryIn15Minutes()
        assertHourly()
    }

    @Test
    fun aCachedAnswerAlsoRetriesIn15Minutes() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        cache.save(StationCache.Snapshot(now.minusSeconds(3600), FuelType.B7, 40.417, -3.704, Fixture.stations("nearest-b7")))
        api.fail = true
        val state = run()
        assertEquals(WidgetState.Kind.CACHED, state.kind)
        assertRetryIn15Minutes()
    }

    @Test
    fun aGoodAnswerDropsAPendingRetry() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        api.fail = true
        run()
        assertRetryIn15Minutes()
        api.fail = false
        run()
        assertTrue(pending(WidgetRefreshWorker.RETRY).isEmpty())
    }

    @Test
    fun theRetryRunChainsTheNextRetryInsteadOfCancellingItself() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        api.fail = true
        // The retry that is running now: REPLACE would cancel it, APPEND keeps it and queues one after it.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetRefreshWorker.RETRY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(15, TimeUnit.MINUTES)
                .addTag(WidgetRefreshWorker.RETRY)
                .build(),
        ).result.get()
        val running = pending(WidgetRefreshWorker.RETRY).single().id
        run(tags = listOf(WidgetRefreshWorker.RETRY))
        val retries = pending(WidgetRefreshWorker.RETRY)
        assertEquals(2, retries.size)
        assertTrue(retries.any { it.id == running })
        val next = retries.single { it.id != running }
        assertEquals(WorkInfo.State.BLOCKED, next.state)
        assertEquals(Duration.ofMinutes(15).toMillis(), next.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
    }

    @Test
    fun noSavedPositionNeedsLocationWithoutARequest() = runTest {
        val state = run()
        assertEquals(WidgetState.Kind.NEEDS_LOCATION, state.kind)
        assertTrue(api.calls.isEmpty())
        assertHourly()
        assertTrue(pending(WidgetRefreshWorker.RETRY).isEmpty())
    }

    @Test
    fun aPositionOlderThanSevenDaysIsNotUsed() = runTest {
        settings.saveLocation(40.417, -3.704, now.minus(Duration.ofDays(8)))
        assertEquals(WidgetState.Kind.NEEDS_LOCATION, run().kind)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun withoutLocationPermissionTheSavedPositionIsNotSent() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        allowed = false
        assertEquals(WidgetState.Kind.NEEDS_LOCATION, run().kind)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun withNoWidgetNothingIsFetchedOrScheduled() = runTest {
        settings.saveLocation(40.417, -3.704, now)
        WidgetRefreshWorker.ensurePeriodic(context)
        widgets = false
        assertEquals(WidgetState(), run())
        assertTrue(api.calls.isEmpty())
        assertEquals(0, redraws)
        assertTrue(pending(WidgetRefreshWorker.PERIODIC).isEmpty())
        assertTrue(pending(WidgetRefreshWorker.RETRY).isEmpty())
    }

    @Test
    fun removingOneWidgetNeverDeletesTheSharedState() {
        val shared = File(context.filesDir, "datastore/widget-state.json")
        assertNotEquals(shared, WidgetStateDefinition.getLocation(context, "appWidget-1"))
    }
}
