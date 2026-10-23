package com.pumperly.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.pumperly.app.core.AppConfig
import com.pumperly.app.core.TapTarget

/** Where the widget and the "Cheapest nearby" screen send a tap. Explicit intents, so no custom URL scheme. */
object WidgetIntents {
    /**
     * The fuel setting screen. Named by class so the widget does not depend on the settings code;
     * the manifest declares the Activity.
     */
    const val SETTINGS_ACTIVITY = "com.pumperly.app.ui.settings.SettingsActivity"

    /** A pumperly.com page, opened inside this app rather than whichever browser handles the link. */
    fun page(context: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settings(context: Context): Intent =
        Intent().setComponent(ComponentName(context.packageName, SETTINGS_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun home(context: Context): Intent = page(context, AppConfig.BASE_URL)

    fun forTarget(context: Context, target: TapTarget): Intent = when (target) {
        is TapTarget.Station -> page(context, target.url)
        TapTarget.Settings -> settings(context)
        is TapTarget.Home -> page(context, target.url)
    }
}
