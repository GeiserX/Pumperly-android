package com.pumperly.app.widget

import com.pumperly.app.core.FuelType
import com.pumperly.app.core.NearestApi
import com.pumperly.app.core.Station
import java.io.IOException

/** A [NearestApi] that answers with [stations], or throws when [fail] is set, and counts its calls. */
class FakeNearestApi(
    var stations: List<Station> = emptyList(),
    var fail: Boolean = false,
) : NearestApi {
    val calls = mutableListOf<Triple<Double, Double, FuelType>>()

    override suspend fun fetchNearest(latitude: Double, longitude: Double, fuel: FuelType): List<Station> {
        calls += Triple(latitude, longitude, fuel)
        if (fail) throw IOException("offline")
        return stations
    }
}
