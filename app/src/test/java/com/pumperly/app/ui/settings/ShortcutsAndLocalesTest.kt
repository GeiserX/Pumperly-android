package com.pumperly.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.pumperly.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/** The launcher shortcut and the per-app language list, read from the compiled resources. */
@RunWith(RobolectricTestRunner::class)
class ShortcutsAndLocalesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Shortcut(val id: String, val shortLabel: Int, val longLabel: Int, val icon: Int) {
        val intents = mutableListOf<Triple<String?, String?, String?>>()
        val action get() = intents.lastOrNull()?.first
        val targetPackage get() = intents.lastOrNull()?.second
        val targetClass get() = intents.lastOrNull()?.third
    }

    private fun shortcuts(): List<Shortcut> {
        val parser: XmlResourceParser = context.resources.getXml(R.xml.shortcuts)
        val out = mutableListOf<Shortcut>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> out += Shortcut(
                    id = parser.getAttributeValue(ANDROID, "shortcutId"),
                    shortLabel = parser.getAttributeResourceValue(ANDROID, "shortcutShortLabel", 0),
                    longLabel = parser.getAttributeResourceValue(ANDROID, "shortcutLongLabel", 0),
                    icon = parser.getAttributeResourceValue(ANDROID, "icon", 0),
                )
                "intent" -> out.last().intents += Triple(
                    parser.getAttributeValue(ANDROID, "action"),
                    parser.getAttributeValue(ANDROID, "targetPackage"),
                    parser.getAttributeValue(ANDROID, "targetClass"),
                )
            }
        }
        return out
    }

    @Test
    fun theWidgetFuelShortcutOpensTheSettingsScreen() {
        val shortcut = shortcuts().single { it.id == SettingsActivity.SHORTCUT_ID }
        assertEquals("com.pumperly.app.widget-fuel", shortcut.id)
        assertEquals("android.intent.action.VIEW", shortcut.action)
        assertEquals(context.packageName, shortcut.targetPackage)
        assertEquals(SettingsActivity::class.java.name, shortcut.targetClass)
        val target = Class.forName(shortcut.targetClass!!)
        assertTrue(Activity::class.java.isAssignableFrom(target))
        assertEquals(R.string.settings_shortcut_short, shortcut.shortLabel)
        assertEquals(R.string.settings_shortcut_long, shortcut.longLabel)
        assertEquals(R.drawable.ic_shortcut_widget_fuel, shortcut.icon)
        assertNotEquals(null, context.getDrawable(shortcut.icon))
        // The app sits under the picker, so leaving it lands in the app, not on the launcher.
        assertEquals(2, shortcut.intents.size)
        assertEquals(
            Triple("android.intent.action.MAIN", context.packageName, "com.pumperly.app.MainActivity"),
            shortcut.intents.first(),
        )
    }

    private fun configuredLocales(): Set<String> {
        val parser = context.resources.getXml(R.xml.locales_config)
        val out = mutableSetOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                out += parser.getAttributeValue(ANDROID, "name")
            }
        }
        return out
    }

    @Test
    fun localesConfigListsEveryTranslatedLanguage() {
        // English lives in values/; each values-<lang>/ holding strings is one more language.
        val translated = File("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && Regex("values-[a-z]{2,3}(-r[A-Z]{2})?").matches(it.name) }
            .filter { dir -> dir.listFiles().orEmpty().any { it.name.startsWith("strings") } }
            .map { it.name.removePrefix("values-").replace("-r", "-") }
            .toSet()
        assertEquals(translated + "en", configuredLocales())
        assertEquals(setOf("en", "es"), configuredLocales())
    }
}
