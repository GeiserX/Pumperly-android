package com.pumperly.app.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pumperly.app.core.HttpNearestApi
import com.pumperly.app.core.Locator
import com.pumperly.app.core.NearestApi
import com.pumperly.app.core.SharedSettings
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The single refresh path of the widget, for the hourly run, the app opening and a fuel change.
 * It reads the fuel and the position the app saved, fetches, stores the [WidgetState], redraws
 * every widget, and decides when to run next: hourly after a good answer, in 15 minutes after a
 * failure or a cached one (the iOS widget's timeline policy).
 */
class WidgetRefreshWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val deps: Dependencies,
) : CoroutineWorker(context, params) {

    /** The constructor WorkManager's default factory calls. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, Dependencies.forContext(context))

    /** What a refresh reads and writes, replaceable in tests. */
    class Dependencies(
        val settings: SharedSettings,
        val cache: StationCache,
        val api: NearestApi,
        val store: DataStore<WidgetState>,
        val isLocationAllowed: () -> Boolean,
        val now: () -> Instant = Instant::now,
        val redraw: suspend () -> Unit,
        /** False when no widget is on the home screen: then nothing is fetched or scheduled. */
        val hasWidgets: suspend () -> Boolean,
    ) {
        companion object {
            fun forContext(context: Context): Dependencies {
                val app = context.applicationContext
                return Dependencies(
                    settings = SharedSettings.forContext(app),
                    cache = StationCache.forContext(app),
                    api = HttpNearestApi(),
                    store = WidgetStateDefinition.store(app),
                    isLocationAllowed = { Locator.isAllowed(app) },
                    redraw = { CheapestNearbyWidget().updateAll(app) },
                    hasWidgets = {
                        GlanceAppWidgetManager(app).getGlanceIds(CheapestNearbyWidget::class.java).isNotEmpty()
                    },
                )
            }
        }
    }

    override suspend fun doWork(): Result {
        if (!deps.hasWidgets()) {
            // The app calls refreshNow on every open; with no widget there is nothing to keep
            // fresh. This run is about to end anyway, so cancelling its own name is harmless.
            cancelAll(applicationContext)
            return Result.success()
        }
        val now = deps.now()
        val fuel = deps.settings.fuel()
        // Without permission no position is used or sent, even one saved earlier.
        val position = if (deps.isLocationAllowed()) deps.settings.lastLocation(now) else null
        val state = WidgetState.refresh(now, fuel, position, deps.api, deps.cache)
        deps.store.updateData { state }
        deps.redraw()
        scheduleAfter(applicationContext, state, fromRetry = RETRY in tags)
        return Result.success()
    }

    companion object {
        const val NOW = "widget-refresh-now"
        const val PERIODIC = "widget-refresh-periodic"
        const val RETRY = "widget-refresh-retry"

        /** Refreshes the widget now: on app open and after the fuel changed. Safe to call when no widget exists. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                // A newer request wins, so a fuel change is never answered with the old fuel.
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().addTag(NOW).build(),
            )
        }

        /** The hourly refresh, kept as it is when already scheduled. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                TimelineMapping.refreshInterval.toMinutes(), TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(PERIODIC)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * After a refresh: the hourly run stays scheduled, and a failed or cached answer adds one
         * retry in 15 minutes (WorkManager's minimum, and the iOS retry). A good answer drops a
         * pending retry. The retry run itself is still running here, so it chains the next retry
         * after itself instead of replacing (and so cancelling) itself.
         */
        internal fun scheduleAfter(context: Context, state: WidgetState, fromRetry: Boolean = false) {
            val work = WorkManager.getInstance(context)
            ensurePeriodic(context)
            if (state.wantsRetry) {
                val retry = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                    .setInitialDelay(TimelineMapping.retryInterval.toMinutes(), TimeUnit.MINUTES)
                    // Offline, a retry would fail at once and chain another every 15 minutes.
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(RETRY)
                    .build()
                val policy = if (fromRetry) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
                work.enqueueUniqueWork(RETRY, policy, retry)
            } else if (!fromRetry) {
                work.cancelUniqueWork(RETRY)
            }
        }

        /** No widget left on the home screen: stop refreshing. */
        fun cancelAll(context: Context) {
            val work = WorkManager.getInstance(context)
            listOf(NOW, PERIODIC, RETRY).forEach(work::cancelUniqueWork)
        }
    }
}
