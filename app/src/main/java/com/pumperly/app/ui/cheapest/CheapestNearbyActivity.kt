package com.pumperly.app.ui.cheapest

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pumperly.app.core.Coordinate
import com.pumperly.app.core.HttpNearestApi
import com.pumperly.app.core.Locator
import com.pumperly.app.core.SharedSettings
import com.pumperly.app.core.StationCache
import com.pumperly.app.widget.WidgetIntents
import com.pumperly.app.widget.WidgetState
import com.pumperly.app.widget.locale
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opened from the "Cheapest nearby" launcher shortcut. It runs the widget's lookup once, with a
 * live fix when one comes in time and the position the app saved otherwise, and shows the answer.
 */
class CheapestNearbyActivity : ComponentActivity() {
    private var state by mutableStateOf<CheapestNearbyUiState?>(null)
    private var lookup: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                state?.let { current ->
                    CheapestNearbyScreen(
                        state = current,
                        onOpenStation = { open(WidgetIntents.page(this, it)) },
                        onRetry = ::load,
                        onChangeFuel = { open(WidgetIntents.settings(this)) },
                        onOpenApp = { open(WidgetIntents.home(this)) },
                    )
                }
            }
        }
    }

    /** Every return here looks again: the fuel or the position may have changed meanwhile. */
    override fun onStart() {
        super.onStart()
        load()
    }

    /** A lookup never outlives the screen: no fix request or fetch keeps running in the background. */
    override fun onStop() {
        lookup?.cancel()
        super.onStop()
    }

    private fun load() {
        lookup?.cancel()
        lookup = lifecycleScope.launch {
            val settings = SharedSettings.forContext(this@CheapestNearbyActivity)
            val fuel = settings.fuel()
            state = CheapestNearbyUiState.Loading(fuel)
            val position = when (val found = position(this@CheapestNearbyActivity, settings)) {
                Position.Unavailable -> {
                    state = CheapestNearbyUiState.LocationUnavailable(fuel)
                    return@launch
                }
                Position.Denied -> null
                is Position.At -> found.coordinate
            }
            val result = WidgetState.refresh(
                Instant.now(), fuel, position, HttpNearestApi(), StationCache.forContext(this@CheapestNearbyActivity),
            )
            result.entry(locale())?.let { state = CheapestNearbyUiState.Ready(it) }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing to open it with; stay on this screen.
        }
    }

    /** Where to look from; Denied is answered like the widget (open the app), Unavailable with a retry. */
    private sealed interface Position {
        data class At(val coordinate: Coordinate) : Position

        data object Unavailable : Position

        data object Denied : Position
    }

    private companion object {
        /** A live fix first, saved like every fix the app gets; without one, the position saved earlier. */
        suspend fun position(context: Context, settings: SharedSettings): Position =
            when (val outcome = Locator.locate(context)) {
                is Locator.Outcome.Fix -> {
                    settings.saveLocation(outcome.latitude, outcome.longitude)
                    Position.At(Coordinate(outcome.latitude, outcome.longitude))
                }
                Locator.Outcome.Unavailable -> settings.lastLocation()?.let(Position::At) ?: Position.Unavailable
                Locator.Outcome.Denied -> Position.Denied
            }
    }
}
