package com.musicremover.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** User-facing codec errors name the video format through `%1$s`, in English and in every translation. */
class StringResourcesTest {
    // Gradle runs unit tests with the module dir (android/app) as the working dir
    private val res = File("src/main/res")

    @Test
    fun codecErrorsKeepMimePlaceholderInDefaultStrings() {
        val strings = parse(File(res, "values/strings_internal.xml"))
        for (key in PLACEHOLDER_KEYS) {
            val text = strings[key]
            assertNotNull("$key missing in values/strings_internal.xml", text)
            assertTrue("$key lost %1\$s: $text", text!!.contains("%1\$s"))
        }
    }

    @Test
    fun codecErrorsKeepMimePlaceholderInEveryLocale() {
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        assertTrue("no values dirs under ${res.absolutePath}", dirs.isNotEmpty())
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            for (file in files) {
                val strings = parse(file)
                for (key in PLACEHOLDER_KEYS) {
                    val text = strings[key] ?: continue
                    assertTrue("$key in ${dir.name}/${file.name} lost %1\$s: $text", text.contains("%1\$s"))
                }
            }
        }
    }

    private fun parse(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private companion object {
        val PLACEHOLDER_KEYS = listOf("internal_video_codec_unsupported", "internal_video_convert_failed")
    }
}
