package com.pumperly.app.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * When the app gets a position, also fetches the stations near it for the widget fuel and caches
 * them on the device, so the app and the widget have something to show with no network.
 * Throttled: a new fetch after 15 minutes, or sooner after moving 1 km (but never twice within a
 * minute, so driving with the map open does not send a request per kilometre). One request at a time.
 */
class NearbyPrefetcher(
    private val settings: SharedSettings,
    private val cache: StationCache,
    private val api: NearestApi,
    private val scope: CoroutineScope,
) {
    data class Attempt(val date: Instant, val latitude: Double, val longitude: Double)

    @Volatile
    private var last: Attempt? = null
    private val inFlight = AtomicBoolean(false)

    /**
     * Starts a fetch when the throttle allows it and none is running. Returns the fetch, or null
     * when it was skipped. A failed fetch leaves the old cache in place.
     */
    fun positionDelivered(latitude: Double, longitude: Double, now: Instant = Instant.now()): Job? {
        if (!shouldFetch(latitude, longitude, now, last)) return null
        if (!inFlight.compareAndSet(false, true)) return null
        last = Attempt(now, latitude, longitude)
        return scope.launch {
            try {
                val fuel = settings.fuel()
                val stations = api.fetchNearest(latitude, longitude, fuel)
                cache.save(StationCache.Snapshot(now, fuel, latitude, longitude, stations))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed settings read, fetch or write only costs this offline copy; the old
                // cache stays and a later fix tries again. Nothing escapes into the caller's scope.
            } finally {
                inFlight.set(false)
            }
        }
    }

    companion object {
        val INTERVAL: Duration = Duration.ofMinutes(15)
        val MINIMUM_INTERVAL: Duration = Duration.ofSeconds(60)
        const val DISTANCE_KM = 1.0

        /** Always on the first fix; then after 15 minutes, or after a minute and 1 km. A clock that went back counts as due. */
        fun shouldFetch(latitude: Double, longitude: Double, now: Instant, last: Attempt?): Boolean {
            if (last == null) return true
            val elapsed = Duration.between(last.date, now)
            if (elapsed >= INTERVAL || elapsed.isNegative) return true
            if (elapsed < MINIMUM_INTERVAL) return false
            return StationCache.distanceKm(last.latitude, last.longitude, latitude, longitude) >= DISTANCE_KM
        }
    }
}
