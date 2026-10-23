package com.pumperly.app.ui.cheapest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pumperly.app.R
import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.StationRow
import com.pumperly.app.core.TimelineMapping

/** What the "Cheapest nearby" screen shows: a lookup in progress, no position to look from, or its answer. */
sealed interface CheapestNearbyUiState {
    data class Loading(val fuel: FuelType) : CheapestNearbyUiState

    /** Location is allowed, but no fix came in time and none was saved earlier. */
    data class LocationUnavailable(val fuel: FuelType) : CheapestNearbyUiState

    data class Ready(val entry: CheapestNearbyEntry) : CheapestNearbyUiState
}

/**
 * The native "Cheapest nearby" screen: the same three rows as the 4x2 widget, from the same
 * lookup, full screen. The Android counterpart of the iOS Siri snippet card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheapestNearbyScreen(
    state: CheapestNearbyUiState,
    onOpenStation: (String) -> Unit,
    onRetry: () -> Unit,
    onChangeFuel: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fuel = when (state) {
                is CheapestNearbyUiState.Loading -> state.fuel
                is CheapestNearbyUiState.LocationUnavailable -> state.fuel
                is CheapestNearbyUiState.Ready -> state.entry.fuel
            }
            FuelHeader(fuel, (state as? CheapestNearbyUiState.Ready)?.entry, onChangeFuel)
            when (state) {
                is CheapestNearbyUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.testTag("loading"))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.cheapest_loading))
                }
                is CheapestNearbyUiState.LocationUnavailable ->
                    Message(R.string.cheapest_location_unavailable, R.string.cheapest_retry, onRetry)
                is CheapestNearbyUiState.Ready -> Answer(state.entry, onOpenStation, onRetry, onChangeFuel, onOpenApp)
            }
        }
    }
}

@Composable
private fun FuelHeader(fuel: FuelType, entry: CheapestNearbyEntry?, onChangeFuel: () -> Unit) {
    val locale = LocalConfiguration.current.locales.get(0)
    val asOf = entry?.asOf?.let { TimelineMapping.asOfText(it, now = entry.date, locale = locale) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(fuel.labelRes), style = MaterialTheme.typography.titleMedium)
            if (asOf != null) {
                Text(
                    stringResource(R.string.widget_as_of, asOf),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onChangeFuel) { Text(stringResource(R.string.cheapest_change_fuel)) }
    }
}

@Composable
private fun Answer(
    entry: CheapestNearbyEntry,
    onOpenStation: (String) -> Unit,
    onRetry: () -> Unit,
    onChangeFuel: () -> Unit,
    onOpenApp: () -> Unit,
) {
    when (val content = entry.content) {
        is CheapestNearbyEntry.Content.Stations -> Column {
            content.rows.forEachIndexed { index, row ->
                StationLine(row, cheapest = index == 0, onClick = { onOpenStation(row.url) })
                if (index < content.rows.lastIndex) HorizontalDivider()
            }
        }
        CheapestNearbyEntry.Content.NeedsLocation -> Message(R.string.widget_needs_location, R.string.cheapest_open_app, onOpenApp)
        CheapestNearbyEntry.Content.Empty -> Message(R.string.widget_empty, R.string.cheapest_change_fuel, onChangeFuel)
        CheapestNearbyEntry.Content.Unavailable -> Message(R.string.widget_unavailable, R.string.cheapest_retry, onRetry)
    }
}

@Composable
private fun StationLine(row: StationRow, cheapest: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag("row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.distanceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            row.valueText ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (cheapest) brandGreen() else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Message(text: Int, action: Int, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Button(onClick = onAction) { Text(stringResource(action)) }
    }
}

/** Pumperly green, darker on a light background so the price stays readable. */
@Composable
private fun brandGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF34D399) else Color(0xFF047857)
