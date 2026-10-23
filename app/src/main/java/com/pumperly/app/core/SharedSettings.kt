package com.pumperly.app.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sharedSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "shared_settings")

/** Settings the app writes and the widget and background refresh read. Same keys as iOS. */
class SharedSettings(private val store: DataStore<Preferences>) {
    /** The widget fuel; an unknown or missing stored code reads as [FuelType.DEFAULT]. */
    val fuelFlow: Flow<FuelType> = store.data.map { FuelType.fromCode(it[FUEL]) ?: FuelType.DEFAULT }

    suspend fun fuel(): FuelType = fuelFlow.first()

    suspend fun setFuel(fuel: FuelType) {
        store.edit { it[FUEL] = fuel.code }
    }

    /** False until the user has confirmed a fuel once; the app opens its fuel screen on first launch. */
    val hasChosenFuelFlow: Flow<Boolean> = store.data.map { it[FUEL_CHOSEN] ?: false }

    suspend fun hasChosenFuel(): Boolean = hasChosenFuelFlow.first()

    suspend fun setHasChosenFuel(chosen: Boolean) {
        store.edit { it[FUEL_CHOSEN] = chosen }
    }

    /** Saves the last position the app saw, rounded to about 110 m, for the widget and the offline screen. */
    suspend fun saveLocation(latitude: Double, longitude: Double, at: Instant = Instant.now()) {
        store.edit {
            it[LATITUDE] = StationsApi.round(latitude)
            it[LONGITUDE] = StationsApi.round(longitude)
            it[LOCATION_DATE] = at.toEpochMilli()
        }
    }

    /** The saved position, if it is at most [LAST_LOCATION_MAX_AGE] old. */
    suspend fun lastLocation(now: Instant = Instant.now()): Coordinate? {
        val prefs = store.data.first()
        val latitude = prefs[LATITUDE] ?: return null
        val longitude = prefs[LONGITUDE] ?: return null
        val stamp = prefs[LOCATION_DATE] ?: return null
        if (Duration.between(Instant.ofEpochMilli(stamp), now) > LAST_LOCATION_MAX_AGE) return null
        return Coordinate(latitude, longitude)
    }

    /** Forgets the position (permission revoked) and keeps the fuel. */
    suspend fun clearLocation() {
        store.edit {
            it.remove(LATITUDE)
            it.remove(LONGITUDE)
            it.remove(LOCATION_DATE)
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    companion object {
        val FUEL = stringPreferencesKey("widget.fuel")
        val FUEL_CHOSEN = booleanPreferencesKey("widget.fuelChosen")
        val LATITUDE = doublePreferencesKey("location.latitude")
        val LONGITUDE = doublePreferencesKey("location.longitude")
        val LOCATION_DATE = longPreferencesKey("location.date")

        /** How long a position saved by the app may stand in for a live fix. */
        val LAST_LOCATION_MAX_AGE: Duration = Duration.ofDays(7)

        /** The one settings store of the app process. */
        fun forContext(context: Context): SharedSettings = SharedSettings(context.applicationContext.sharedSettingsStore)
    }
}
