package com.pumperly.app.widget

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.glance.state.GlanceStateDefinition
import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.Coordinate
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.NearestApi
import com.pumperly.app.core.Station
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the widget shows, as the last refresh left it. It keeps the raw stations rather than the
 * formatted rows, so prices and distances follow the current locale when the widget is drawn.
 * One state for every widget on the home screen: they all show the same fuel and position.
 */
@Serializable
data class WidgetState(
    /** When the refresh ran; null until the first one has. */
    val updatedAtMillis: Long? = null,
    val fuel: String = FuelType.DEFAULT.code,
    val kind: Kind = Kind.NOT_LOADED,
    /** The stations the rows show, in order: live for [Kind.STATIONS], from the offline cache for [Kind.CACHED]. */
    val stations: List<Station> = emptyList(),
    /** For [Kind.CACHED]: when the cached stations were fetched. */
    val asOfMillis: Long? = null,
) {
    enum class Kind { NOT_LOADED, STATIONS, NEEDS_LOCATION, EMPTY, UNAVAILABLE, CACHED }

    val fuelType: FuelType get() = FuelType.fromCode(fuel) ?: FuelType.DEFAULT

    /** The entry the widget draws, or null before the first refresh. */
    fun entry(locale: Locale = Locale.getDefault()): CheapestNearbyEntry? {
        val date = Instant.ofEpochMilli(updatedAtMillis ?: return null)
        val fuel = fuelType
        val content = when (kind) {
            Kind.NOT_LOADED -> return null
            Kind.STATIONS, Kind.CACHED -> {
                val rows = TimelineMapping.rows(stations, fuel, locale)
                if (rows.isEmpty()) CheapestNearbyEntry.Content.Unavailable else CheapestNearbyEntry.Content.Stations(rows)
            }
            Kind.NEEDS_LOCATION -> CheapestNearbyEntry.Content.NeedsLocation
            Kind.EMPTY -> CheapestNearbyEntry.Content.Empty
            Kind.UNAVAILABLE -> CheapestNearbyEntry.Content.Unavailable
        }
        val asOf = if (kind == Kind.CACHED) asOfMillis?.let(Instant::ofEpochMilli) else null
        return CheapestNearbyEntry(date, fuel, content, asOf)
    }

    /** True when the next refresh should come after [TimelineMapping.retryInterval] instead of the hourly one. */
    val wantsRetry: Boolean get() = kind == Kind.UNAVAILABLE || kind == Kind.CACHED

    companion object {
        /**
         * One refresh, ported from the iOS widget's `loadEntry`. Android widgets get no location
         * of their own (that would need background location), so [position] is the one the app
         * saved in the foreground, or null when there is none or location is not allowed; then
         * nothing is sent. A good answer is cached on the device. A failed fetch falls back to
         * the cached stations when they are for this fuel, at most a day old and taken near here.
         */
        suspend fun refresh(
            now: Instant,
            fuel: FuelType,
            position: Coordinate?,
            api: NearestApi,
            cache: StationCache,
        ): WidgetState {
            if (position == null) return WidgetState(now.toEpochMilli(), fuel.code, Kind.NEEDS_LOCATION)
            val result = try {
                Result.success(api.fetchNearest(position.latitude, position.longitude, fuel))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { stations ->
                // Losing the offline copy is not worth failing the refresh for.
                runCatching { cache.save(StationCache.Snapshot(now, fuel, position.latitude, position.longitude, stations)) }
            }
            val cached = if (result.isFailure) cache.load(fuel, now = now) else null
            val entry = TimelineMapping.entry(now, fuel, result, cached, position)
            val kind = when {
                entry.asOf != null -> Kind.CACHED
                entry.content is CheapestNearbyEntry.Content.Stations -> Kind.STATIONS
                entry.content == CheapestNearbyEntry.Content.Empty -> Kind.EMPTY
                entry.content == CheapestNearbyEntry.Content.NeedsLocation -> Kind.NEEDS_LOCATION
                else -> Kind.UNAVAILABLE
            }
            val source = when (kind) {
                Kind.CACHED -> cached?.stations.orEmpty()
                Kind.STATIONS -> result.getOrNull().orEmpty()
                else -> emptyList()
            }
            return WidgetState(
                updatedAtMillis = now.toEpochMilli(),
                fuel = fuel.code,
                kind = kind,
                stations = TimelineMapping.topStations(source, fuel),
                asOfMillis = if (kind == Kind.CACHED) cached?.savedAt?.toEpochMilli() else null,
            )
        }
    }
}

/** Reads and writes [WidgetState] as JSON. A corrupt file reads as the empty state. */
internal object WidgetStateSerializer : Serializer<WidgetState> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: WidgetState = WidgetState()

    override suspend fun readFrom(input: InputStream): WidgetState = try {
        json.decodeFromString(WidgetState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("widget state", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("widget state", e)
    }

    override suspend fun writeTo(t: WidgetState, output: OutputStream) {
        output.write(json.encodeToString(WidgetState.serializer(), t).encodeToByteArray())
    }
}

/**
 * The widget's Glance state: one store for every widget id, so a widget added later shows the
 * latest refresh at once. Glance deletes [getLocation] when a widget is removed; that path is a
 * per-widget name nothing is written to, so removing one widget never wipes the others' state.
 */
object WidgetStateDefinition : GlanceStateDefinition<WidgetState> {
    private const val FILE = "widget-state.json"

    @Volatile
    private var store: DataStore<WidgetState>? = null

    /** The one store of the app process (DataStore allows a single instance per file). */
    fun store(context: Context): DataStore<WidgetState> = store ?: synchronized(this) {
        store ?: create { File(context.applicationContext.filesDir, "datastore/$FILE") }.also { store = it }
    }

    /** A store at [file] with the same format and corruption handling. For tests. */
    internal fun create(
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        file: () -> File,
    ): DataStore<WidgetState> = DataStoreFactory.create(
        serializer = WidgetStateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { WidgetState() },
        scope = scope,
        produceFile = file,
    )

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<WidgetState> = store(context)

    override fun getLocation(context: Context, fileKey: String): File =
        File(context.applicationContext.filesDir, "datastore/widget-unused-$fileKey")
}
