package com.pumperly.app.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pumperly.app.R
import com.pumperly.app.core.FuelType

/** Test tags, the same identifiers the iOS UI tests use. */
object FuelPickerTags {
    const val LIST = "settings.list"
    const val INTRO = "settings.intro"
    const val DONE = "settings.done"
    fun fuel(fuel: FuelType) = "settings.fuel.${fuel.code}"
}

private val BrandGreen = Color(0xFF34D399)
private val BrandGreenDark = Color(0xFF2BB584)

/**
 * The one setting the widget needs: which fuel's price it shows. Ported from pumperly-ios
 * `Pumperly/Settings/SettingsView.swift`: an intro line on the first run, the 16 fuels in their
 * 6 sections, the chosen one marked, and Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelPickerScreen(
    selected: FuelType,
    isFirstRun: Boolean,
    onSelect: (FuelType) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                actions = {
                    TextButton(onClick = onDone, modifier = Modifier.testTag(FuelPickerTags.DONE)) {
                        Text(stringResource(R.string.settings_done))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.testTag(FuelPickerTags.LIST).selectableGroup(),
        ) {
            if (isFirstRun) {
                item(key = "intro") {
                    Text(
                        stringResource(R.string.settings_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(FuelPickerTags.INTRO),
                    )
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.settings_fuel_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            for (category in FuelType.Category.entries) {
                item(key = "category-${category.name}") {
                    Text(
                        stringResource(category.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(category.fuels, key = { it.code }) { option ->
                    val isSelected = option == selected
                    ListItem(
                        headlineContent = { Text(stringResource(option.labelRes)) },
                        // The row carries the click and the selected state; the radio is the visual only.
                        trailingContent = { RadioButton(selected = isSelected, onClick = null) },
                        modifier = Modifier
                            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                            .testTag(FuelPickerTags.fuel(option)),
                    )
                }
            }
        }
    }
}

/** Material 3 in the brand green, light or dark with the system. */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = BrandGreen, onPrimary = Color.Black)
    } else {
        lightColorScheme(primary = BrandGreenDark, onPrimary = Color.Black)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
