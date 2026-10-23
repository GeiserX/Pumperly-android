package com.pumperly.app.ui.error

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.pumperly.app.R
import com.pumperly.app.core.Station
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.TimelineMapping
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The screen shown instead of the page when it cannot load. Only the offline screen reads the
 * station cache (off the main thread) and lists up to three of the last known stations.
 */
@Composable
fun ErrorScreen(
    kind: ErrorScreenKind,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    loadSnapshot: (Context) -> StationCache.Snapshot? = OfflineStations::load,
) {
    val context = LocalContext.current.applicationContext
    val snapshot by produceState<StationCache.Snapshot?>(initialValue = null, kind) {
        value = if (OfflineStations.showsCache(kind)) withContext(Dispatchers.IO) { loadSnapshot(context) } else null
    }
    ErrorContent(kind, snapshot, onAction, modifier)
}

/** The stateless screen: [snapshot] is ignored unless [kind] is the offline screen. */
@Composable
fun ErrorContent(
    kind: ErrorScreenKind,
    snapshot: StationCache.Snapshot?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    onOpenMap: (Context, Station) -> Unit = ::openMap,
) {
    val context = LocalContext.current
    val green = colorResource(R.color.pumperly_green)
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = green) else lightColorScheme(primary = green)
    MaterialTheme(colorScheme = colors) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Centred, and scrolls only when the cached rows do not fit (large text, small screens).
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(kind.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(TAG_TITLE),
                    )
                    Text(
                        stringResource(kind.messageRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (snapshot != null && OfflineStations.showsCache(kind) && OfflineStations.stations(snapshot).isNotEmpty()) {
                        CachedStationsList(snapshot, now, zone) { onOpenMap(context, it) }
                    }
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
                        modifier = Modifier.padding(top = 8.dp).testTag(TAG_ACTION),
                    ) {
                        Text(stringResource(kind.actionRes), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Up to three cached stations under the "Last known … as of <time>" header, each with a map button. */
@Composable
fun CachedStationsList(
    snapshot: StationCache.Snapshot,
    now: Instant,
    zone: ZoneId,
    onOpenMap: (Station) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalContext.current.resources
    val stations = OfflineStations.stations(snapshot)
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag(TAG_LIST),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                OfflineStations.header(resources, snapshot, now, locale, zone),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(TAG_HEADER),
            )
            Text(
                stringResource(snapshot.fuel.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        stations.forEachIndexed { index, station ->
            val row = TimelineMapping.row(station, snapshot.fuel, locale)
            Row(Modifier.fillMaxWidth().testTag(TAG_ROW_PREFIX + station.externalId), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.distanceText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    row.valueText ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                val description = stringResource(R.string.offline_open_map_description, row.name)
                OutlinedButton(
                    onClick = { onOpenMap(station) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag(TAG_MAP_PREFIX + station.externalId).semantics { contentDescription = description },
                ) {
                    Text(stringResource(R.string.offline_open_map))
                }
            }
            if (index < stations.lastIndex) HorizontalDivider()
        }
    }
}

/** Opens the station in whichever map app handles `geo:`; with none installed the tap does nothing. */
fun openMap(context: Context, station: Station) {
    val intent = Intent(Intent.ACTION_VIEW, OfflineStations.geoUri(station).toUri())
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No map app: nothing sensible to open instead, and the row already shows name and distance.
    }
}

const val TAG_TITLE = "error.title"
const val TAG_ACTION = "error.action"
const val TAG_LIST = "offline.list"
const val TAG_HEADER = "offline.header"
const val TAG_ROW_PREFIX = "offline.row."
const val TAG_MAP_PREFIX = "offline.map."
