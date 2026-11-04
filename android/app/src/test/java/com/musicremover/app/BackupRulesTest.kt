package com.musicremover.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Cookies, job work dirs (temp cookie copies) and WebView data never go into a backup or device transfer. */
class BackupRulesTest {
    // Gradle runs unit tests with the module dir (android/app) as the working dir
    private val xml = File("src/main/res/xml")

    @Test
    fun fullBackupExcludesSecrets() {
        val doc = parse(File(xml, "backup_rules.xml"))
        assertExcludes("full-backup-content", excludes(doc.documentElement))
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeSecrets() {
        val doc = parse(File(xml, "data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertTrue("$section missing", nodes.length == 1)
            assertExcludes(section, excludes(nodes.item(0) as Element))
        }
    }

    private fun assertExcludes(where: String, found: Set<Pair<String, String>>) {
        for (expected in REQUIRED) assertTrue("$where lacks $expected: $found", expected in found)
    }

    private fun excludes(parent: Element): Set<Pair<String, String>> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).mapTo(mutableSetOf()) { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private companion object {
        val REQUIRED = listOf("file" to "cookies/", "file" to "work/", "root" to "app_webview/")
    }
}
