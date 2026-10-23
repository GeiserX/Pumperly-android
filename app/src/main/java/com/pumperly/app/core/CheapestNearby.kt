package com.pumperly.app.core

import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** One line of the widget: a station with its price (or charger power) and the page it opens. */
data class StationRow(
    val id: String,
    val name: String,
    val distanceText: String,
    /** Price for priced fuels, charger power for EV, null when the API gave neither. */
    val valueText: String?,
    val url: String,
)

/** What the widget, the offline screen and the "Cheapest nearby" screen show at one moment. */
data class CheapestNearbyEntry(
    val date: Instant,
    val fuel: FuelType,
    val content: Content,
    /** Set when the rows come from the offline cache: the time they were fetched. */
    val asOf: Instant? = null,
) {
    sealed interface Content {
        data class Stations(val rows: List<StationRow>) : Content

        /** No location yet: the user has to open the app and allow it. */
        data object NeedsLocation : Content

        /** The request worked but nothing sells this fuel within the radius. */
        data object Empty : Content

        /** Network or server failure; the next refresh comes sooner. */
        data object Unavailable : Content
    }
}

/** Where a tap on the whole widget goes. */
sealed interface TapTarget {
    data class Station(val url: String) : TapTarget

    /** Nothing sells this fuel nearby: open the fuel setting so the user can pick another. */
    data object Settings : TapTarget

    data class Home(val url: String = AppConfig.BASE_URL) : TapTarget
}

/**
 * Pure mapping from API results to what the native surfaces show, kept apart from Glance,
 * WorkManager and location so it can be unit tested. Ported from the iOS `TimelineMapping`.
 */
object TimelineMapping {
    const val MAX_ROWS = 3
    val refreshInterval: Duration = Duration.ofMinutes(60)
    val retryInterval: Duration = Duration.ofMinutes(15)

    /**
     * Priced fuels: cheapest first, ties broken by distance, stations without a price dropped.
     * EV: nearest first, since the API returns no charging price.
     */
    fun rows(
        stations: List<Station>,
        fuel: FuelType,
        locale: Locale = Locale.getDefault(),
        limit: Int = MAX_ROWS,
    ): List<StationRow> = topStations(stations, fuel, limit).map { row(it, fuel, locale) }

    fun row(station: Station, fuel: FuelType, locale: Locale = Locale.getDefault()): StationRow = StationRow(
        id = station.id,
        name = displayName(station),
        distanceText = formatDistance(station.distanceKm, locale),
        valueText = if (fuel.hasPrice) {
            station.price?.let { formatPrice(it, station.currency, locale) }
        } else {
            station.powerKw?.let { formatPower(it, locale) }
        },
        url = StationsApi.pageUrl(station, fuel),
    )

    /** The stations the rows show, in the same order. */
    fun topStations(stations: List<Station>, fuel: FuelType, limit: Int = MAX_ROWS): List<Station> {
        val sorted = if (fuel.hasPrice) {
            stations.filter { it.price != null }
                .sortedWith(compareBy<Station>({ it.price }, { it.distanceKm }))
        } else {
            stations.sortedBy { it.distanceKm }
        }
        return sorted.take(limit)
    }

    /** A null result means there was no position to ask with. */
    fun entry(
        date: Instant,
        fuel: FuelType,
        result: Result<List<Station>>?,
        locale: Locale = Locale.getDefault(),
    ): CheapestNearbyEntry {
        val content = when {
            result == null -> CheapestNearbyEntry.Content.NeedsLocation
            result.isFailure -> CheapestNearbyEntry.Content.Unavailable
            else -> {
                val rows = rows(result.getOrThrow(), fuel, locale)
                if (rows.isEmpty()) CheapestNearbyEntry.Content.Empty else CheapestNearbyEntry.Content.Stations(rows)
            }
        }
        return CheapestNearbyEntry(date, fuel, content)
    }

    /**
     * The last known stations, marked with the time they were fetched. A snapshot with no rows
     * for its fuel says nothing useful offline, so it reads as unavailable.
     */
    fun entry(date: Instant, snapshot: StationCache.Snapshot, locale: Locale = Locale.getDefault()): CheapestNearbyEntry {
        val rows = rows(snapshot.stations, snapshot.fuel, locale)
        val content = if (rows.isEmpty()) CheapestNearbyEntry.Content.Unavailable else CheapestNearbyEntry.Content.Stations(rows)
        return CheapestNearbyEntry(date, snapshot.fuel, content, asOf = snapshot.savedAt)
    }

    /**
     * Like [entry] with a result, but a failed fetch falls back to the cached stations when they
     * are for the same fuel, at most a day old and taken near [near] (when a position is known).
     */
    fun entry(
        date: Instant,
        fuel: FuelType,
        result: Result<List<Station>>?,
        cached: StationCache.Snapshot?,
        near: Coordinate? = null,
        locale: Locale = Locale.getDefault(),
    ): CheapestNearbyEntry {
        if (result != null && result.isFailure && cached != null && cached.isUsable(fuel, now = date) &&
            (near == null || cached.isNear(near.latitude, near.longitude))
        ) {
            val fallback = entry(date, cached, locale)
            if (fallback.content is CheapestNearbyEntry.Content.Stations) return fallback
        }
        return entry(date, fuel, result, locale)
    }

    /** About hourly; sooner after a failure, including one covered by cached rows. */
    fun nextRefresh(after: CheapestNearbyEntry): Instant {
        val failed = after.content == CheapestNearbyEntry.Content.Unavailable || after.asOf != null
        return after.date.plus(if (failed) retryInterval else refreshInterval)
    }

    /** When cached rows were fetched: the time for today, weekday and time for an older day. */
    fun asOfText(
        date: Instant,
        now: Instant = Instant.now(),
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        val sameDay = date.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
        val pattern = if (sameDay) time else "EEE $time"
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(date)
    }

    /**
     * Where a tap on the whole widget goes: the cheapest station, the settings when nothing sells
     * this fuel nearby (so the user can pick another), the start page otherwise.
     */
    fun tapTarget(entry: CheapestNearbyEntry): TapTarget = when (val content = entry.content) {
        is CheapestNearbyEntry.Content.Stations ->
            content.rows.firstOrNull()?.let { TapTarget.Station(it.url) } ?: TapTarget.Home()
        CheapestNearbyEntry.Content.Empty -> TapTarget.Settings
        CheapestNearbyEntry.Content.NeedsLocation, CheapestNearbyEntry.Content.Unavailable -> TapTarget.Home()
    }

    /** A blank name falls back to the brand, then the city. */
    fun displayName(station: Station): String {
        val name = station.name.trim()
        return name.ifEmpty { station.brand ?: station.city }
    }

    /** Price with 3 decimals in the locale's currency format. */
    fun formatPrice(price: Double, currency: String, locale: Locale): String {
        val code = runCatching { Currency.getInstance(currency) }.getOrNull()
            ?: return String.format(Locale.ROOT, "%.3f %s", price, currency)
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = code
            minimumFractionDigits = 3
            maximumFractionDigits = 3
        }.format(price)
    }

    /** Distance with 1 decimal, then " km". */
    fun formatDistance(km: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(km) + " km"

    /** Charger power with no decimals, then " kW". */
    fun formatPower(kw: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 0
    }.format(kw) + " kW"

    /** Shown in the widget picker and while the first refresh runs. */
    fun sample(date: Instant = Instant.now(), fuel: FuelType = FuelType.B7, locale: Locale = Locale.getDefault()) =
        entry(date, fuel, Result.success(sampleStations), locale)

    val sampleStations: List<Station> = listOf(
        Station("1", "4508", "ES", "Blanca Madrid", "Blanca", "Madrid", 40.40528, -3.70314, 1.459, "EUR", 0.4),
        Station("2", "3217", "ES", "Repsol Madrid", "Repsol", "Madrid", 40.40442, -3.70539, 1.472, "EUR", 1.1),
        Station("3", "1234", "ES", "Cepsa Atocha", "Cepsa", "Madrid", 40.40701, -3.69102, 1.489, "EUR", 2.3),
    )
}

/** A position in degrees. */
data class Coordinate(val latitude: Double, val longitude: Double)
