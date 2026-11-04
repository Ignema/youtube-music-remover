package com.musicremover.app.processing.internal

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Per-site store on a temp dir (design §3b.1-§3b.3): migration, fail-closed index, import merge, job file. */
class CookieStoreMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var job: File
    private val legacy get() = File(root, CookieStore.FILE)
    private val index get() = File(root, CookieStore.INDEX)
    private fun siteFile(site: String) = File(root, "${CookieStore.SITES_DIR}/$site.txt")
    private fun store() = CookieStore(root, null)

    private val header = "# Netscape HTTP Cookie File"
    private val ig = ".instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\tDUMMY_INSTA_123456"
    private val igHttpOnly = "#HttpOnly_i.instagram.com\tFALSE\t/\tTRUE\t2000000000\tds_user_id\tDUMMY_INSTA_UID_1"
    private val ex = ".example.org\tTRUE\t/\tFALSE\t2000000000\texample_name\tDUMMY_EXAMPLE_123456"
    private val yt = ".youtube.com\tTRUE\t/\tTRUE\t2000000000\tPREF\tDUMMY_YT_123456"

    private fun file(vararg lines: String) = (listOf(header) + lines).joinToString("\n", postfix = "\n")
    private fun bytes(vararg lines: String) = file(*lines).toByteArray()

    @Before
    fun setUp() {
        root = tmp.newFolder("cookies")
        job = tmp.newFolder("job")
    }

    private fun jobText(s: CookieStore = store()): String? {
        val c = s.copyForJob(job) ?: return null
        return c.file.readText().also { c.file.delete() }
    }

    // --- Migration ---

    @Test
    fun legacyFileIsSplitPerSiteAllEnabledWithItsDateAndDeleted() {
        legacy.writeText(file(ig, ex, igHttpOnly, yt))
        assertTrue(legacy.setLastModified(1_700_000_000_000L))
        val sites = store().sites()
        assertEquals(listOf("example.org", "instagram.com", "youtube.com"), sites.map { it.site })
        assertTrue(sites.all { it.enabled })
        assertTrue(sites.all { it.savedAtMs == 1_700_000_000_000L })
        assertEquals(mapOf("example.org" to 1, "instagram.com" to 2, "youtube.com" to 1), sites.associate { it.site to it.count })
        assertEquals(CookieStore.SOURCE_LOGIN, sites.single { it.site == "instagram.com" }.source)
        assertEquals(CookieStore.SOURCE_IMPORT, sites.single { it.site == "example.org" }.source)
        assertTrue(sites.all { it.loginUrl == null })
        assertFalse(legacy.exists())
        assertTrue(index.isFile)
        assertEquals(file(ig, igHttpOnly), siteFile("instagram.com").readText())
        assertEquals(file(ex), siteFile("example.org").readText())
    }

    @Test
    fun invalidLegacyFileIsDeleted() {
        legacy.writeText("[{\"domain\": \".instagram.com\"}]")
        assertEquals(emptyList<CookieStore.SiteInfo>(), store().sites())
        assertFalse(legacy.exists())
        assertFalse(index.exists())
    }

    @Test
    fun constructorTouchesNoFileAndTheFirstCallMigrates() {
        legacy.writeText(file(ig))
        val s = store()
        assertTrue(legacy.exists())
        assertFalse(index.exists())
        assertFalse(File(root, CookieStore.SITES_DIR).exists())
        assertEquals(CookiesTxt.Summary(1, listOf("instagram.com")), s.summary())
        assertFalse(legacy.exists())
    }

    @Test
    fun crashBeforeTheIndexReRunsTheMigration() {
        legacy.writeText(file(ig, ex))
        // A previous run wrote one site file, then died before sites.json
        siteFile("instagram.com").apply { parentFile!!.mkdirs(); writeText(file(ig)) }
        val sites = store().sites()
        assertEquals(listOf("example.org", "instagram.com"), sites.map { it.site })
        assertTrue(sites.all { it.enabled })
        assertFalse(legacy.exists())
        assertTrue(index.isFile)
    }

    @Test
    fun indexAndLegacyBothPresentDeletesTheLegacyFileAndKeepsTheIndex() {
        assertTrue(store().importBytes(bytes(ig)) is CookieStore.ImportResult.Imported)
        val before = index.readBytes()
        legacy.writeText(file(ex))
        val sites = store().sites()
        assertEquals(listOf("instagram.com"), sites.map { it.site })
        assertFalse(legacy.exists())
        assertArrayEquals(before, index.readBytes())
    }

    @Test
    fun deleteAllAlsoRemovesALeftoverLegacyFile() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        legacy.writeText(file(yt))
        assertTrue(store().deleteAll())
        assertFalse(legacy.exists())
        assertFalse(index.exists())
        assertFalse(File(root, CookieStore.SITES_DIR).exists())
        assertEquals(emptyList<CookieStore.SiteInfo>(), store().sites())
        assertNull(store().summary())
        assertFalse(store().hasCookies())
    }

    // --- Fail closed ---

    @Test
    fun corruptIndexDisablesEverySite() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        index.writeText("{not json")
        val sites = store().sites()
        assertEquals(listOf("example.org", "instagram.com"), sites.map { it.site })
        assertTrue(sites.none { it.enabled })
        assertNull(store().summary())
        assertNull(store().copyForJob(job))
        assertFalse(File(job, CookieStore.FILE).exists())
    }

    @Test
    fun siteFileWithoutIndexEntryIsDisabled() {
        assertTrue(store().importBytes(bytes(ig)) is CookieStore.ImportResult.Imported)
        siteFile("example.org").writeText(file(ex))
        val sites = store().sites().associateBy { it.site }
        assertTrue(sites.getValue("instagram.com").enabled)
        assertFalse(sites.getValue("example.org").enabled)
        assertEquals(1, sites.getValue("example.org").count)
        val text = jobText()!!
        assertFalse("example_name" in text || "DUMMY_EXAMPLE" in text)
    }

    @Test
    fun corruptSiteFileIsSkippedAtJobTimeAndKeepsItsSwitch() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        siteFile("example.org").writeText("garbage, not a cookie file")
        val text = jobText()
        assertNotNull(text)
        assertEquals(file(ig), text)
        val corrupt = store().sites().single { it.site == "example.org" }
        assertEquals(0, corrupt.count)
        assertTrue(corrupt.enabled)
        assertEquals(CookiesTxt.Summary(1, listOf("instagram.com")), store().summary())
        // Only the corrupt site enabled: no job file
        assertTrue(store().setEnabled("instagram.com", false))
        assertNull(store().copyForJob(job))
    }

    // --- Job file ---

    @Test
    fun disabledSiteNamesAndValuesAreAbsentFromTheJobFile() {
        assertTrue(store().importBytes(bytes(ig, igHttpOnly, ex)) is CookieStore.ImportResult.Imported)
        assertTrue(store().setEnabled("example.org", false))
        val c = store().copyForJob(job)!!
        val text = c.file.readText()
        assertEquals(File(job, CookieStore.FILE), c.file)
        assertEquals(file(ig, igHttpOnly), text)
        assertFalse("example_name" in text)
        assertFalse("DUMMY_EXAMPLE" in text)
        assertFalse("DUMMY_EXAMPLE_123456" in c.secrets)
        assertTrue("DUMMY_INSTA_123456" in c.secrets)
        assertEquals(CookiesTxt.Summary(2, listOf("instagram.com")), store().summary())
    }

    @Test
    fun jobFileConcatenatesEnabledSitesInSiteOrder() {
        assertTrue(store().importBytes(bytes(yt, ig, ex)) is CookieStore.ImportResult.Imported)
        assertEquals(file(ex, ig, yt), jobText())
        assertEquals(CookiesTxt.Summary(3, listOf("example.org", "instagram.com", "youtube.com")), store().summary())
    }

    // --- Import ---

    @Test
    fun importSummaryDescribesTheImportedFileOnly() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        val r = store().importBytes(bytes(yt))
        assertEquals(CookieStore.ImportResult.Imported(CookiesTxt.Summary(1, listOf("youtube.com"))), r)
    }

    @Test
    fun importMergeKeepsSwitchesAndEnablesNewSites() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        assertTrue(store().setEnabled("example.org", false))
        val ex2 = ".example.org\tTRUE\t/\tFALSE\t2000000000\tsecond\tDUMMY_EXAMPLE_SECOND"
        val exReplaced = ".example.org\tTRUE\t/\tFALSE\t2100000000\texample_name\tDUMMY_EXAMPLE_REPLACED"
        assertTrue(store().importBytes(bytes(ex2, exReplaced, yt)) is CookieStore.ImportResult.Imported)
        val sites = store().sites().associateBy { it.site }
        assertFalse(sites.getValue("example.org").enabled)
        assertTrue(sites.getValue("instagram.com").enabled)
        assertTrue(sites.getValue("youtube.com").enabled)
        assertEquals(file(ex2, exReplaced), siteFile("example.org").readText())
        assertEquals(2, sites.getValue("example.org").count)
    }

    @Test
    fun importUpdatesTheDateOfTouchedSitesOnly() {
        legacy.writeText(file(ig, ex))
        assertTrue(legacy.setLastModified(1_600_000_000_000L))
        val before = System.currentTimeMillis()
        assertTrue(store().importBytes(bytes(yt, ex)) is CookieStore.ImportResult.Imported)
        val sites = store().sites().associateBy { it.site }
        assertEquals(1_600_000_000_000L, sites.getValue("instagram.com").savedAtMs)
        assertTrue(sites.getValue("example.org").savedAtMs >= before)
        assertTrue(sites.getValue("youtube.com").savedAtMs >= before)
    }

    @Test
    fun invalidImportIsRejectedAndKeepsTheStore() {
        assertTrue(store().importBytes(bytes(ig)) is CookieStore.ImportResult.Imported)
        val before = siteFile("instagram.com").readBytes()
        assertEquals(
            CookieStore.ImportResult.Rejected(CookiesTxt.Invalid.MALFORMED_LINE, 1),
            store().importBytes("[{\"domain\": \".instagram.com\"}]".toByteArray()),
        )
        assertEquals(
            CookieStore.ImportResult.Rejected(CookiesTxt.Invalid.EMPTY, 0),
            store().importBytes("$header\n".toByteArray()),
        )
        assertArrayEquals(before, siteFile("instagram.com").readBytes())
    }

    @Test
    fun storeOverOneMegabyteInTotalIsRejected() {
        val big = "x".repeat(1000)
        fun lines(domain: String) = (0 until 600).map { "$domain\tTRUE\t/\tTRUE\t2000000000\tc$it\t$big" }.toTypedArray()
        assertTrue(store().importBytes(bytes(*lines(".a-site.com"))) is CookieStore.ImportResult.Imported)
        assertEquals(
            CookieStore.ImportResult.Rejected(CookiesTxt.Invalid.TOO_LARGE, 0),
            store().importBytes(bytes(*lines(".b-site.com"))),
        )
        assertEquals(listOf("a-site.com"), store().sites().map { it.site })
        assertFalse(siteFile("b-site.com").exists())
    }

    // --- Toggle, delete, login ---

    @Test
    fun toggleAndDeletePersist() {
        assertTrue(store().importBytes(bytes(ig, ex)) is CookieStore.ImportResult.Imported)
        assertTrue(store().setEnabled("instagram.com", false))
        assertFalse(store().sites().single { it.site == "instagram.com" }.enabled)
        assertTrue(store().setEnabled("instagram.com", true))
        assertTrue(store().sites().single { it.site == "instagram.com" }.enabled)
        assertFalse(store().setEnabled("unknown.com", true))
        assertTrue(store().deleteSite("example.org"))
        assertEquals(listOf("instagram.com"), store().sites().map { it.site })
        assertFalse(siteFile("example.org").exists())
        assertFalse(store().deleteSite("example.org"))
        assertFalse(store().deleteSite("../x"))
    }

    @Test
    fun loginSavesIntoOneSiteAndRecordsTheCustomUrl() {
        assertTrue(store().importBytes(bytes(ex)) is CookieStore.ImportResult.Imported)
        assertTrue(store().setEnabled("example.org", false))
        val custom = LoginSites.custom("https://www.example.org/login")!!
        val e = CookiesTxt.Entry(custom.cookieDomain, "login_cookie", "DUMMY_LOGIN_123456", 2_000_000_000L)
        val r = store().saveCaptured(custom, listOf(e))
        assertTrue("$r", r is CookieStore.SaveResult.Saved)
        val site = store().sites().single()
        assertEquals("example.org", site.site)
        assertTrue(site.enabled)
        assertEquals(CookieStore.SOURCE_LOGIN, site.source)
        assertEquals("https://www.example.org/login", site.loginUrl)
        assertEquals(2, site.count)
        val ie = CookiesTxt.Entry(".instagram.com", "sessionid", "DUMMY_SESSION_123456", 2_000_000_000L)
        val r2 = store().saveCaptured(LoginSites.INSTAGRAM, listOf(ie)) as CookieStore.SaveResult.Saved
        assertEquals(CookiesTxt.Summary(1, listOf("instagram.com")), r2.captured)
        assertEquals(CookiesTxt.Summary(3, listOf("example.org", "instagram.com")), r2.summary)
        assertNull(store().sites().single { it.site == "instagram.com" }.loginUrl)
        assertEquals(CookieStore.SaveResult.NoCookies, store().saveCaptured(LoginSites.INSTAGRAM, emptyList()))
    }

    @Test
    fun indexUsesTheDocumentedFieldNames() {
        assertTrue(store().importBytes(bytes(ig)) is CookieStore.ImportResult.Imported)
        val json = index.readText()
        for (k in listOf("\"version\":1", "\"site\":\"instagram.com\"", "\"enabled\":true", "\"saved_at\":", "\"source\":\"import\"")) {
            assertTrue("$k in $json", k in json)
        }
        assertFalse("DUMMY_" in json)
    }
}
