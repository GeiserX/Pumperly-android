package com.pumperly.app.core

import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One station from `GET /api/stations/nearest` (a GeoJSON feature, flattened). */
@Serializable
data class Station(
    val id: String,
    val externalId: String,
    val country: String,
    val name: String,
    val brand: String? = null,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val price: Double? = null,
    val currency: String,
    val distanceKm: Double,
    val powerKw: Double? = null,
)

@Serializable
private data class FeatureCollection(val features: List<Feature>) {
    @Serializable
    data class Feature(val geometry: Geometry, val properties: Properties)

    @Serializable
    data class Geometry(val coordinates: List<Double>)

    @Serializable
    data class Properties(
        val id: String,
        val externalId: String,
        val country: String,
        val name: String,
        val brand: String? = null,
        val city: String,
        val price: Double? = null,
        val currency: String,
        val distanceKm: Double,
        val powerKw: Double? = null,
    )
}

object StationsApi {
    const val RADIUS_KM = 10.0

    /**
     * The API's maximum. It returns stations nearest first, so "cheapest nearby" means the
     * cheapest of the 50 nearest stations within the radius.
     */
    const val LIMIT = 50

    private val json = Json { ignoreUnknownKeys = true }

    /** Rounds a coordinate to 3 decimals (about 110 m) before it leaves the device. */
    fun round(value: Double): Double = (value * 1000).roundHalfAwayFromZero() / 1000

    fun nearestUrl(latitude: Double, longitude: Double, fuel: FuelType, base: String = AppConfig.BASE_URL): String =
        "$base/api/stations/nearest?" + query(
            "lat" to plain(round(latitude)),
            "lon" to plain(round(longitude)),
            "radius_km" to RADIUS_KM.toInt().toString(),
            "fuel" to fuel.code,
            "limit" to LIMIT.toString(),
        )

    /**
     * Stations in the API's order. Features without both coordinates are skipped; a body that
     * is not a FeatureCollection (an error response) throws.
     */
    fun decode(body: String): List<Station> =
        json.decodeFromString(FeatureCollection.serializer(), body).features.mapNotNull { feature ->
            val coords = feature.geometry.coordinates
            if (coords.size < 2) return@mapNotNull null
            val p = feature.properties
            Station(
                id = p.id, externalId = p.externalId, country = p.country, name = p.name,
                brand = p.brand, city = p.city, latitude = coords[1], longitude = coords[0],
                price = p.price, currency = p.currency, distanceKm = p.distanceKm, powerKw = p.powerKw,
            )
        }

    /**
     * The station's page on the site: `/?station=CC:externalId&lat=..&lng=..&fuel=..` (the share
     * format in GeiserX/Pumperly `src/lib/share-url.ts`). The fuel makes the site open the layer
     * the station was listed for, so an EV charger opens on the EV layer.
     */
    fun pageUrl(station: Station, fuel: FuelType, base: String = AppConfig.BASE_URL): String =
        "$base/?" + query(
            "station" to "${station.country.uppercase()}:${station.externalId}",
            "lat" to plain((station.latitude * 1e5).roundHalfAwayFromZero() / 1e5),
            "lng" to plain((station.longitude * 1e5).roundHalfAwayFromZero() / 1e5),
            "fuel" to fuel.code,
        )

    private fun query(vararg items: Pair<String, String>): String =
        items.joinToString("&") { (name, value) -> "$name=${encode(value)}" }

    /** Percent-encodes a query value, keeping `:` as the site's share links do. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20").replace("%3A", ":")

    /** The shortest decimal form, never scientific notation (40.417, -3.7085, 40.0). */
    private fun plain(value: Double): String {
        val text = value.toString()
        return if ('E' in text) java.math.BigDecimal(text).toPlainString() else text
    }

    /** Swift's `rounded()`: halves go away from zero (Kotlin's `round` goes to even). */
    private fun Double.roundHalfAwayFromZero(): Double {
        val whole = kotlin.math.truncate(this)
        return if (kotlin.math.abs(this - whole) >= 0.5) whole + kotlin.math.sign(this) else whole
    }
}
