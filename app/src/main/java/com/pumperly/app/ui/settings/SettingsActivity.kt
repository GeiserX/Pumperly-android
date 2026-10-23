package com.pumperly.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.SharedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts [FuelPickerScreen]. Opened on the first launch (while `widget.fuelChosen` is false), from
 * the launcher shortcut `com.pumperly.app.widget-fuel`, and from the widget header.
 *
 * As on iOS, leaving the screen in any way counts as having chosen: the default fuel is a choice
 * too, and the screen must not come back on every launch.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var settings: SharedSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = settingsProvider(this)
        setContent {
            SettingsTheme {
                val fuel by settings.fuelFlow.collectAsState(initial = null)
                // Read once when the screen opens, like iOS: the intro stays while the user picks.
                var isFirstRun by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    if (isFirstRun == null) isFirstRun = needsFirstRun(settings)
                }
                val current = fuel
                val firstRun = isFirstRun
                if (current != null && firstRun != null) {
                    FuelPickerScreen(
                        selected = current,
                        isFirstRun = firstRun,
                        onSelect = { option -> if (option != current) select(option) },
                        onDone = ::done,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Back, the Done button and a finish from elsewhere all end here. The write outlives the
        // activity, so it runs outside lifecycleScope.
        if (isFinishing) persistScope.launch { settings.setHasChosenFuel(true) }
    }

    private fun select(fuel: FuelType) {
        // Outside lifecycleScope, like onStop: a rotation or a finish right after the tap must not
        // drop the write or the widget refresh.
        persistScope.launch {
            settings.setFuel(fuel)
            settings.setHasChosenFuel(true)
            (application as? WidgetFuelListener)?.onWidgetFuelChanged(fuel)
        }
    }

    private fun done() {
        lifecycleScope.launch {
            settings.setHasChosenFuel(true)
            finish()
        }
    }

    companion object {
        /** The id of the static launcher shortcut in `res/xml/shortcuts.xml`; the iOS quick action type. */
        const val SHORTCUT_ID = "com.pumperly.app.widget-fuel"

        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Where the screen reads and writes the fuel; tests swap in a store of their own. */
        @VisibleForTesting
        internal var settingsProvider: (Context) -> SharedSettings = SharedSettings::forContext

        /** The intent that opens this screen, for the app's first launch and the widget header. */
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)

        /** True until the user has left this screen once; the app opens it on launch while true. */
        suspend fun needsFirstRun(settings: SharedSettings): Boolean = !settings.hasChosenFuel()
    }
}
