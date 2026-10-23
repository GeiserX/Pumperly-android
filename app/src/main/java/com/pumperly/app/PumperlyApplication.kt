package com.pumperly.app

import android.app.Application
import com.pumperly.app.core.FuelType
import com.pumperly.app.ui.settings.WidgetFuelListener
import com.pumperly.app.widget.WidgetRefreshWorker

/**
 * Hears the widget fuel change from the settings screen, also when that screen is the first
 * thing the process starts (the launcher shortcut, the widget header), and redraws the widget.
 */
class PumperlyApplication : Application(), WidgetFuelListener {
    override fun onWidgetFuelChanged(fuel: FuelType) {
        WidgetRefreshWorker.refreshNow(this)
    }
}
