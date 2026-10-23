package com.pumperly.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import java.time.Duration
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One foreground location fix with a timeout. It never asks for permission: without it the answer
 * is [Outcome.Denied], and the Activity asks when the user is looking. Uses only the platform
 * LocationManager (through androidx.core), so no Google Play services are needed.
 */
object Locator {
    sealed interface Outcome {
        data class Fix(val latitude: Double, val longitude: Double) : Outcome

        /** Location is not allowed: use no position at all. */
        data object Denied : Outcome

        /** Allowed, but no recent fix arrived in time. */
        data object Unavailable : Outcome
    }

    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** "Near me" needs a fix from the last 15 minutes; an older one is ignored. */
    val MAX_FIX_AGE: Duration = Duration.ofMinutes(15)

    fun isAllowed(context: Context): Boolean = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** A fix taken at [fixTimeMillis] (wall clock) is recent when it is under [MAX_FIX_AGE] old and not from the future. */
    fun isRecent(fixTimeMillis: Long, nowMillis: Long): Boolean {
        val age = nowMillis - fixTimeMillis
        return age in 0 until MAX_FIX_AGE.toMillis()
    }

    suspend fun locate(context: Context, timeout: Duration = DEFAULT_TIMEOUT): Outcome {
        if (!isAllowed(context)) return Outcome.Denied
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return Outcome.Unavailable
        val providers = providers(manager)
        try {
            lastKnown(manager, providers)?.let { return it }
            val provider = providers.firstOrNull() ?: return Outcome.Unavailable
            return withTimeoutOrNull(timeout.toMillis()) { current(context, manager, provider) }
                ?: lastKnown(manager, providers)
                ?: Outcome.Unavailable
        } catch (_: SecurityException) {
            // Revoked between the check and the call.
            return Outcome.Denied
        }
    }

    /** Network first: a city-block fix is enough for "nearby" and arrives faster than GPS. */
    private fun providers(manager: LocationManager): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    @Suppress("MissingPermission") // locate() checks the permission first and catches SecurityException.
    private fun lastKnown(manager: LocationManager, providers: List<String>): Outcome? {
        val now = System.currentTimeMillis()
        return (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { isRecent(it.time, now) }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    @Suppress("MissingPermission")
    private suspend fun current(context: Context, manager: LocationManager, provider: String): Outcome? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                location: Location? ->
                if (continuation.isActive) continuation.resume(location?.toFix())
            }
        }

    private fun Location.toFix() = Outcome.Fix(latitude, longitude)
}
