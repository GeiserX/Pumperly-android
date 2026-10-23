package com.pumperly.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.pumperly.app.R
import com.pumperly.app.core.CheapestNearbyEntry
import com.pumperly.app.core.FuelType
import com.pumperly.app.core.StationRow
import com.pumperly.app.core.TimelineMapping
import java.util.Locale

/**
 * "Cheapest nearby" home-screen widget, the Android port of the iOS small and medium widgets:
 * 2x2 shows the cheapest station, 4x2 shows up to three tappable rows under a fuel header that
 * opens the fuel setting. [WidgetRefreshWorker] fetches; this only draws [WidgetState].
 */
class CheapestNearbyWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override val stateDefinition: GlanceStateDefinition<*> = WidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = currentState<WidgetState>()
            GlanceTheme {
                CheapestNearbyContent(state.entry(LocalContext.current.locale()), state.fuelType)
            }
        }
    }

    companion object {
        /** 2x2 cells. */
        val SMALL = DpSize(110.dp, 110.dp)

        /** 4x2 cells. */
        val MEDIUM = DpSize(250.dp, 110.dp)
    }
}

internal fun Context.locale(): Locale = resources.configuration.locales.get(0) ?: Locale.getDefault()

/** A clock before the time cached rows were fetched, as the iOS widget shows. */
private const val CLOCK = "\uD83D\uDD52"

/** Pumperly green, darker on a light background so the price stays readable. */
private val brandGreen = ColorProvider(day = Color(0xFF047857), night = Color(0xFF34D399))

/**
 * The widget body for one entry. A null entry means no refresh has finished yet. Taps on the
 * whole widget follow [TimelineMapping.tapTarget]; in 4x2 each row opens its own station.
 */
@Composable
fun CheapestNearbyContent(entry: CheapestNearbyEntry?, fuel: FuelType = entry?.fuel ?: FuelType.DEFAULT) {
    val context = LocalContext.current
    val tap = entry?.let { TimelineMapping.tapTarget(it) }
    val open = actionStartActivity(if (tap != null) WidgetIntents.forTarget(context, tap) else WidgetIntents.home(context))
    val medium = LocalSize.current.width >= CheapestNearbyWidget.MEDIUM.width
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(open),
    ) {
        val content = entry?.content
        when {
            content is CheapestNearbyEntry.Content.Stations && !medium && content.rows.isNotEmpty() ->
                SmallStation(entry, content.rows.first())
            content is CheapestNearbyEntry.Content.Stations -> MediumStations(entry, content.rows)
            else -> Message(entry, fuel, medium, messageText(context, content))
        }
    }
}

private fun messageText(context: Context, content: CheapestNearbyEntry.Content?): String = context.getString(
    when (content) {
        null -> R.string.widget_loading
        CheapestNearbyEntry.Content.NeedsLocation -> R.string.widget_needs_location
        CheapestNearbyEntry.Content.Empty -> R.string.widget_empty
        CheapestNearbyEntry.Content.Unavailable -> R.string.widget_unavailable
        is CheapestNearbyEntry.Content.Stations -> R.string.widget_unavailable
    },
)

/** Fuel name and, for cached rows, the time they were fetched. */
@Composable
private fun Header(entry: CheapestNearbyEntry?, fuel: FuelType, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val asOf = entry?.asOf?.let { TimelineMapping.asOfText(it, now = entry.date, locale = context.locale()) }
    val label = context.getString(fuel.labelRes)
    val text = if (asOf != null) "$label · $CLOCK $asOf" else label
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        modifier = modifier.semantics {
            contentDescription = if (asOf != null) "$label, ${context.getString(R.string.widget_as_of, asOf)}" else label
        },
    )
}

@Composable
private fun SmallStation(entry: CheapestNearbyEntry, row: StationRow) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(entry, entry.fuel)
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = row.valueText ?: "—",
            maxLines = 1,
            style = TextStyle(color = brandGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            text = row.name,
            maxLines = 2,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = row.distanceText,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}

@Composable
private fun MediumStations(entry: CheapestNearbyEntry, rows: List<StationRow>) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.widget_title),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            // The fuel name opens the fuel setting, as on iOS.
            Header(entry, entry.fuel, GlanceModifier.clickable(actionStartActivity(WidgetIntents.settings(context))))
        }
        Spacer(GlanceModifier.height(4.dp))
        rows.forEachIndexed { index, row ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clickable(actionStartActivity(WidgetIntents.page(context, row.url))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = row.name,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = row.distanceText,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = row.valueText ?: "—",
                    maxLines = 1,
                    style = TextStyle(
                        color = if (index == 0) brandGreen else GlanceTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Message(entry: CheapestNearbyEntry?, fuel: FuelType, medium: Boolean, text: String) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (medium) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = context.getString(R.string.widget_title),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Header(entry, fuel, GlanceModifier.clickable(actionStartActivity(WidgetIntents.settings(context))))
            }
        } else {
            Header(entry, fuel)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = text,
            maxLines = 4,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
        )
    }
}
