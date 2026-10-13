package com.theveloper.pixelplay.i18n

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Keeps every supported language in step with the base English strings of the device sheet. */
class SonosStringsTest {

    private val resDir = File("src/main/res")
    private val locales = listOf("ar", "de", "es", "fr", "in", "it", "ko", "nb", "ru", "tr", "zh-rCN")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private class Entry(val kind: String, val text: String, val quantities: Map<String, String>)

    private fun parse(file: File): Map<String, Entry> {
        if (!file.exists()) return emptyMap()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = mutableMapOf<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> result[name] = Entry("string", node.textContent, emptyMap())
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    val quantities = (0 until items.length).map { items.item(it) as Element }
                        .associate { it.getAttribute("quantity") to it.textContent }
                    result[name] = Entry("plurals", "", quantities)
                }
            }
        }
        return result
    }

    private fun allEntries(dir: File): Map<String, Entry> =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { parse(it).entries }.associate { it.key to it.value }

    private fun placeholdersOf(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `the base file exists and is not empty`() {
        assertThat(parse(File(resDir, "values/strings_sonos.xml"))).isNotEmpty()
    }

    @Test
    fun `every language translates every Sonos string`() {
        val base = parse(File(resDir, "values/strings_sonos.xml"))

        for (locale in locales) {
            val translated = parse(File(resDir, "values-$locale/strings_sonos.xml"))
            assertWithMessage("values-$locale is missing keys").that(base.keys - translated.keys).isEmpty()
            assertWithMessage("values-$locale has keys the base does not").that(translated.keys - base.keys).isEmpty()
        }
    }

    @Test
    fun `translations keep the placeholders of the base text`() {
        val base = parse(File(resDir, "values/strings_sonos.xml"))

        for (locale in locales) {
            val translated = parse(File(resDir, "values-$locale/strings_sonos.xml"))
            for ((key, entry) in base) {
                val other = translated[key] ?: continue
                if (entry.kind == "string") {
                    assertWithMessage("$locale/$key").that(placeholdersOf(other.text)).isEqualTo(placeholdersOf(entry.text))
                }
            }
        }
    }

    @Test
    fun `the common plural forms keep the number placeholder`() {
        for (locale in locales) {
            val plural = parse(File(resDir, "values-$locale/strings_sonos.xml"))["sonos_rooms_count"] ?: continue
            for ((quantity, text) in plural.quantities) {
                // Singular, dual and zero forms may spell the number out in words.
                if (quantity in setOf("zero", "one", "two")) continue
                assertWithMessage("$locale/$quantity").that(placeholdersOf(text)).containsExactly("%d")
            }
        }
    }

    @Test
    fun `plurals have a quantity for other and no blank text`() {
        for (locale in locales) {
            val translated = parse(File(resDir, "values-$locale/strings_sonos.xml"))
            for ((key, entry) in translated) {
                if (entry.kind == "plurals") {
                    assertWithMessage("$locale/$key").that(entry.quantities.keys).contains("other")
                    assertWithMessage("$locale/$key").that(entry.quantities.values.all { it.isNotBlank() }).isTrue()
                }
                if (entry.kind == "string") assertWithMessage("$locale/$key").that(entry.text).isNotEmpty()
            }
        }
    }

    @Test
    fun `every language has all the strings of the device sheet`() {
        val baseCastKeys = allEntries(File(resDir, "values")).keys.filter { it.startsWith("cast_") }.toSet()

        for (locale in locales) {
            val keys = allEntries(File(resDir, "values-$locale")).keys
            assertWithMessage("values-$locale is missing device sheet strings").that(baseCastKeys - keys).isEmpty()
        }
    }
}
