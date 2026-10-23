package com.pumperly.app.ui.settings

import com.pumperly.app.core.FuelType

/**
 * Told when the user picks a new widget fuel, so the widget can refresh for it.
 *
 * The settings screen calls this on the [android.app.Application] when the application implements
 * it. Going through the application, not a static callback, means the call also arrives when the
 * screen is the first thing the process starts (the launcher shortcut, the widget header).
 */
fun interface WidgetFuelListener {
    fun onWidgetFuelChanged(fuel: FuelType)
}
