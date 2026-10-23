package com.pumperly.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Registers [CheapestNearbyWidget] with the launcher and starts or stops its refresh. */
class CheapestNearbyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CheapestNearbyWidget()

    /** A widget was added or restored: draw it with fresh data and keep the hourly refresh. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefreshWorker.refreshNow(context)
    }

    /** The last widget was removed. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancelAll(context)
    }
}
