package com.pumperly.app.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * English and Spanish must name the same strings. Lanes add their strings in pairs
 * (`values/strings_<lane>.xml` and `values-es/strings_<lane>.xml`); every such pair, and every
 * file under `values-es`, is compared key by key. Unit tests run with the module as the working
 * directory, so the resource files are read from the source tree.
 */
class StringsParityTest {
    private val res = File("src/main/res")
    private val english = File(res, "values")
    private val spanish = File(res, "values-es")

    private fun stringFiles(dir: File): Set<String> =
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().map { it.name }.toSet()

    /** name -> text of every translatable `<string>` (plurals and arrays too, by name). */
    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, String>()
        for (tag in listOf("string", "plurals", "string-array")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                out[element.getAttribute("name")] = element.textContent
            }
        }
        return out
    }

    private fun pairs(): List<String> {
        val laneFiles = stringFiles(english).filter { it.startsWith("strings_") }
        return (laneFiles + stringFiles(spanish)).distinct().sorted()
    }

    @Test
    fun coreStringsExistInBothLanguages() {
        assertTrue(File(english, "strings_core.xml").isFile)
        assertTrue(File(spanish, "strings_core.xml").isFile)
        assertTrue("strings_core.xml" in pairs())
    }

    @Test
    fun everyPairHasTheSameKeys() {
        for (name in pairs()) {
            val en = File(english, name)
            val es = File(spanish, name)
            assertTrue("values/$name is missing", en.isFile)
            assertTrue("values-es/$name is missing", es.isFile)
            assertEquals("keys of $name", strings(en).keys, strings(es).keys)
        }
    }

    @Test
    fun placeholdersMatchAcrossLanguages() {
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        for (name in pairs()) {
            val es = strings(File(spanish, name))
            for ((key, value) in strings(File(english, name))) {
                assertEquals(
                    "placeholders of $key in $name",
                    placeholder.findAll(value).map { it.value }.sorted().toList(),
                    placeholder.findAll(es[key].orEmpty()).map { it.value }.sorted().toList(),
                )
            }
        }
    }

    @Test
    fun fuelNamesAreTranslated() {
        // Codes that read the same in both languages (AdBlue, Gas) are allowed to match.
        val same = setOf("fuel_ADBLUE", "fuel_category_gas")
        val en = strings(File(english, "strings_core.xml"))
        val es = strings(File(spanish, "strings_core.xml"))
        assertEquals(FuelType.entries.size + FuelType.Category.entries.size, en.size)
        for ((key, value) in en) if (key !in same) assertNotEquals("$key is not translated", value, es[key])
    }
}
