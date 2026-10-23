package com.pumperly.app.core

import androidx.annotation.StringRes
import com.pumperly.app.R

/**
 * Fuel codes accepted by `GET /api/stations/nearest`.
 * Mirrors `FUEL_TYPE_CODES` in GeiserX/Pumperly `src/types/fuel.ts`.
 */
enum class FuelType(val code: String, val category: Category, @StringRes val labelRes: Int) {
    B7("B7", Category.DIESEL, R.string.fuel_B7),
    B7_PREMIUM("B7_PREMIUM", Category.DIESEL, R.string.fuel_B7_PREMIUM),
    B10("B10", Category.DIESEL, R.string.fuel_B10),
    B_AGRICULTURAL("B_AGRICULTURAL", Category.DIESEL, R.string.fuel_B_AGRICULTURAL),
    HVO("HVO", Category.DIESEL, R.string.fuel_HVO),
    E5("E5", Category.GASOLINE, R.string.fuel_E5),
    E5_PREMIUM("E5_PREMIUM", Category.GASOLINE, R.string.fuel_E5_PREMIUM),
    E10("E10", Category.GASOLINE, R.string.fuel_E10),
    E5_98("E5_98", Category.GASOLINE, R.string.fuel_E5_98),
    E98_E10("E98_E10", Category.GASOLINE, R.string.fuel_E98_E10),
    LPG("LPG", Category.GAS, R.string.fuel_LPG),
    CNG("CNG", Category.GAS, R.string.fuel_CNG),
    LNG("LNG", Category.GAS, R.string.fuel_LNG),
    H2("H2", Category.HYDROGEN, R.string.fuel_H2),
    EV("EV", Category.ELECTRIC, R.string.fuel_EV),
    ADBLUE("ADBLUE", Category.OTHER, R.string.fuel_ADBLUE);

    /** EV chargers have no price in the API, so they are sorted by distance. */
    val hasPrice: Boolean get() = this != EV

    /** Picker sections, in the order the site lists them. */
    enum class Category(@StringRes val labelRes: Int) {
        DIESEL(R.string.fuel_category_diesel),
        GASOLINE(R.string.fuel_category_gasoline),
        GAS(R.string.fuel_category_gas),
        HYDROGEN(R.string.fuel_category_hydrogen),
        ELECTRIC(R.string.fuel_category_electric),
        OTHER(R.string.fuel_category_other);

        val fuels: List<FuelType> get() = FuelType.entries.filter { it.category == this }
    }

    companion object {
        /** The site's default fuel (`defaultFuel` in its config). */
        val DEFAULT = B7

        /** The fuel for an API code, or null for a code the app does not know. */
        fun fromCode(code: String?): FuelType? = entries.firstOrNull { it.code == code }
    }
}
