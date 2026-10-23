package com.pumperly.app.ui.error

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.pumperly.app.R
import com.pumperly.app.core.Station
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The three error screens the shell shows instead of the page: offline (retry), certificate (go back), page (retry). */
enum class ErrorScreenKind(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    @StringRes val actionRes: Int,
) {
    OFFLINE(R.string.offline_title, R.string.offline_message, R.string.offline_retry),
    SSL(R.string.ssl_error_title, R.string.ssl_error_message, R.string.ssl_error_back),
    PAGE(R.string.page_error_title, R.string.page_error_message, R.string.page_error_retry),
}

/** The last known stations, offered when the site cannot be reached. Ported from the iOS `OfflineStations`. */
object OfflineStations {
    /** Only a connectivity failure gets the cached stations; a certificate or page error does not. */
    fun showsCache(kind: ErrorScreenKind): Boolean = kind == ErrorScreenKind.OFFLINE

    /** The snapshot the app and the widget share, if it is at most a day old. */
    fun load(context: Context): StationCache.Snapshot? = StationCache.forContext(context).load()

    /** The rows to show, in the widget's order: cheapest first, nearest first for EV. */
    fun stations(snapshot: StationCache.Snapshot): List<Station> =
        TimelineMapping.topStations(snapshot.stations, snapshot.fuel)

    /** "Last known cheapest nearby, as of <time>": the time alone for today, weekday and time before. */
    fun header(
        resources: Resources,
        snapshot: StationCache.Snapshot,
        now: Instant = Instant.now(),
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val format = if (snapshot.fuel.hasPrice) R.string.offline_header else R.string.offline_header_ev
        return resources.getString(format, TimelineMapping.asOfText(snapshot.savedAt, now, locale, zone))
    }

    /**
     * A map app at the station, with its name as the pin label: `geo:0,0?q=lat,lon(name)`.
     * The name is percent-encoded, so a parenthesis or an ampersand in it cannot end the query.
     */
    fun geoUri(station: Station): String {
        val label = URLEncoder.encode(TimelineMapping.displayName(station), "UTF-8").replace("+", "%20")
        return "geo:0,0?q=${plain(station.latitude)},${plain(station.longitude)}($label)"
    }

    /** No exponent, no trailing zeros added: 40.405278 stays 40.405278. */
    private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
