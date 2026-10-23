package com.pumperly.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.ui.cheapest.CheapestNearbyActivity
import com.pumperly.app.ui.settings.SettingsActivity
import com.pumperly.app.ui.settings.WidgetFuelListener
import com.pumperly.app.widget.CheapestNearbyReceiver
import com.pumperly.app.widget.WidgetIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/** The native surfaces are reachable: what the merged manifest and shortcuts.xml declare. */
@RunWith(RobolectricTestRunner::class)
class ManifestWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun activity(cls: Class<*>): ActivityInfo =
        pm.getActivityInfo(ComponentName(context, cls), PackageManager.GET_META_DATA)

    @Test
    fun theApplicationHearsWidgetFuelChanges() {
        // Without it the settings screen has nobody to tell, and the widget keeps the old fuel.
        assertTrue(context is PumperlyApplication)
        assertTrue(context is WidgetFuelListener)
    }

    @Test
    fun theWidgetReceiverIsDeclaredWithItsProvider() {
        val receivers = pm.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        val receiver = receivers.single { it.activityInfo.name == CheapestNearbyReceiver::class.java.name }.activityInfo
        assertTrue("the launcher must be able to bind it", receiver.exported)
        assertEquals(R.xml.widget_cheapest_nearby, receiver.metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))
    }

    @Test
    fun theSettingsScreenHasThePlainThemeAndOpensOnce() {
        val info = activity(SettingsActivity::class.java)
        assertFalse(info.exported)
        // The splash theme of <application> would show its background under the picker.
        assertEquals(R.style.Theme_Pumperly, info.theme)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        // The widget names the screen by class, so the constant must point at the declared activity.
        assertEquals(SettingsActivity::class.java.name, WidgetIntents.SETTINGS_ACTIVITY)
    }

    @Test
    fun theCheapestNearbyScreenIsDeclared() {
        val info = activity(CheapestNearbyActivity::class.java)
        assertTrue("the assistant opens it through OPEN_APP_FEATURE", info.exported)
        assertEquals(R.style.Theme_Pumperly, info.theme)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun theLauncherActivityPublishesTheShortcuts() {
        assertEquals(R.xml.shortcuts, activity(MainActivity::class.java).metaData.getInt("android.app.shortcuts"))
    }

    @Test
    fun theCheapestNearbyShortcutOpensItsScreen() {
        val parser = context.resources.getXml(R.xml.shortcuts)
        var inCheapest = false
        var target: String? = null
        var capabilityTarget: String? = null
        var inCapability = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name in setOf("shortcut", "capability")) {
                inCheapest = false
                inCapability = false
            }
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> inCheapest = parser.getAttributeValue(ANDROID, "shortcutId") == "com.pumperly.app.cheapest-nearby"
                "capability" -> inCapability = parser.getAttributeValue(ANDROID, "name") == "actions.intent.OPEN_APP_FEATURE"
                "intent" -> {
                    val cls = parser.getAttributeValue(ANDROID, "targetClass")
                    if (inCheapest) target = cls
                    if (inCapability) capabilityTarget = cls
                }
            }
        }
        assertEquals(CheapestNearbyActivity::class.java.name, target)
        assertEquals(CheapestNearbyActivity::class.java.name, capabilityTarget)
    }

    @Test
    fun theAppOffersItsLanguages() {
        // android:localeConfig is read by the system only; check the attribute is in the manifest.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
    }
}
