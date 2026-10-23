package com.pumperly.app.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last stations fetched near the user, kept on the device so the app and the widget can still
 * show them with no network. One JSON file in the app's private files; it never leaves the device.
 */
class StationCache(directory: File) {
    val file: File = File(directory, FILE_NAME)

    @ConsistentCopyVisibility
    data class Snapshot private constructor(
        val savedAt: Instant,
        val fuel: FuelType,
        /** Rounded to 3 decimals (about 110 m), like every coordinate the app sends. */
        val latitude: Double,
        val longitude: Double,
        val stations: List<Station>,
    ) {
        /**
         * Fresh enough and for the fuel asked for. A snapshot dated more than a few minutes in the
         * future (the clock moved back) counts as stale rather than showing a time that has not
         * happened yet.
         */
        fun isUsable(fuel: FuelType? = null, maxAge: Duration = MAX_AGE, now: Instant = Instant.now()): Boolean {
            val age = Duration.between(savedAt, now)
            return age <= maxAge && age >= CLOCK_TOLERANCE.negated() && (fuel == null || fuel == this.fuel)
        }

        /** True when the snapshot was taken close enough to this position for its stations to still be "nearby". */
        fun isNear(latitude: Double, longitude: Double, withinKm: Double = MAX_DISTANCE_KM): Boolean =
            distanceKm(this.latitude, this.longitude, latitude, longitude) <= withinKm

        companion object {
            operator fun invoke(
                savedAt: Instant,
                fuel: FuelType,
                latitude: Double,
                longitude: Double,
                stations: List<Station>,
            ): Snapshot = Snapshot(savedAt, fuel, StationsApi.round(latitude), StationsApi.round(longitude), stations)
        }
    }

    /** The file format: seconds would lose nothing, but milliseconds keep `Instant` round trips exact. */
    @Serializable
    private data class StoredSnapshot(
        val savedAtMillis: Long,
        val fuel: String,
        val latitude: Double,
        val longitude: Double,
        val stations: List<Station>,
    )

    /** Writes the snapshot to a temporary file and renames it over the old one, so a reader never sees half a file. */
    fun save(snapshot: Snapshot) {
        val stored = StoredSnapshot(
            snapshot.savedAt.toEpochMilli(), snapshot.fuel.code, snapshot.latitude, snapshot.longitude, snapshot.stations,
        )
        val directory = file.absoluteFile.parentFile ?: error("no directory for $file")
        directory.mkdirs()
        val temp = File.createTempFile("nearest-cache", ".tmp", directory)
        try {
            temp.writeText(json.encodeToString(StoredSnapshot.serializer(), stored), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    /**
     * The saved snapshot if it is fresh enough (and for [fuel], when given). A missing or
     * unreadable file reads as no snapshot.
     */
    fun load(fuel: FuelType? = null, maxAge: Duration = MAX_AGE, now: Instant = Instant.now()): Snapshot? {
        val stored = runCatching {
            json.decodeFromString(StoredSnapshot.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val storedFuel = FuelType.fromCode(stored.fuel) ?: return null
        val snapshot = Snapshot(
            Instant.ofEpochMilli(stored.savedAtMillis), storedFuel, stored.latitude, stored.longitude, stored.stations,
        )
        return snapshot.takeIf { it.isUsable(fuel, maxAge, now) }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "nearest-cache.json"
        val MAX_AGE: Duration = Duration.ofHours(24)
        val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)

        /** Half the search radius: further away than this, the cached stations are not nearby any more. */
        const val MAX_DISTANCE_KM = StationsApi.RADIUS_KM / 2

        private val json = Json { ignoreUnknownKeys = true }

        /** The cache the app, the widget and the background refresh share (one process, one file). */
        fun forContext(context: Context): StationCache = StationCache(context.filesDir)

        /** Great-circle distance in kilometres. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val radians = Math.PI / 180
            val dLat = (lat2 - lat1) * radians
            val dLon = (lon2 - lon1) * radians
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * radians) * cos(lat2 * radians) * sin(dLon / 2) * sin(dLon / 2)
            return 6371 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
