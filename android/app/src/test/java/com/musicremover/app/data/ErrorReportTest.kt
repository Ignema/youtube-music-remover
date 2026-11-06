package com.musicremover.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ErrorReportTest {
    private val info = ErrorReport.Info(
        appVersion = "4.1.0", versionCode = 62, mode = "internal", model = "UVR-MDX-NET-Inst_HQ_3.onnx",
        device = "Google Pixel 7", abi = "arm64-v8a", android = "15", sdk = 35,
    )

    @Test
    fun sanitizeRemovesLinksAndStoragePaths() {
        val s = ErrorReport.sanitize(
            "yt-dlp: ERROR https://www.instagram.com/reel/abc?igsh=x failed\n" +
                "open /storage/emulated/0/Movies/My holiday.mp4 failed\n" +
                "uri content://com.android.providers.media.documents/document/video%3A12",
        )
        assertFalse(s.contains("instagram"))
        assertFalse(s.contains("emulated"))
        assertFalse(s.contains("providers.media"))
        assertTrue(s.contains("<link>"))
        assertTrue(s.contains("<path>"))
    }

    @Test
    fun sanitizeCapsLength() {
        val s = ErrorReport.sanitize("x".repeat(10_000))
        assertTrue(s.length < ErrorReport.MAX_DETAIL + 30)
        assertTrue(s.endsWith("(truncated)"))
    }

    @Test
    fun titlesByKind() {
        assertEquals("[Crash] java.lang.IllegalStateException: boom",
            ErrorReport.title(ErrorReport.Kind.Crash, "java.lang.IllegalStateException: boom\n\tat a.b(C.kt:1)"))
        assertEquals("[Bug] Processing failed: Out of memory", ErrorReport.title(ErrorReport.Kind.JobFailed, "Out of memory"))
        assertEquals("", ErrorReport.title(ErrorReport.Kind.Manual, null))
        // A long message keeps its first sentence instead of stopping mid-word
        assertEquals(
            "[Bug] Processing failed: Acceleration failed on this phone.",
            ErrorReport.title(
                ErrorReport.Kind.JobFailed,
                "Acceleration failed on this phone. Set Settings → Performance → Acceleration to CPU and try again.",
            ),
        )
        val long = ErrorReport.shorten("word ".repeat(30).trim())
        assertTrue(long.length <= 80 && long.endsWith("…") && !long.contains("wor…"))
    }

    @Test
    fun bodyHasDetailsAndSanitizedError() {
        val body = ErrorReport.body(ErrorReport.Kind.JobFailed, info, "Download failed for https://youtu.be/xyz")
        assertTrue(body.contains("Murem 4.1.0 (62)"))
        assertTrue(body.contains("Internal (on-device)"))
        assertTrue(body.contains("Google Pixel 7 (arm64-v8a)"))
        assertTrue(body.contains("Android: 15 (API 35)"))
        assertTrue(body.contains("Download failed for <link>"))
        assertFalse(body.contains("youtu.be"))
    }

    @Test
    fun manualReportHasNoErrorBlock() {
        val body = ErrorReport.body(ErrorReport.Kind.Manual, info.copy(mode = null, model = null), null)
        assertFalse(body.contains("```"))
        assertFalse(body.contains("Mode:"))
    }

    @Test
    fun issueUrlIsEncodedAndShortEnough() {
        val url = ErrorReport.issueUrl(ErrorReport.Kind.Crash, info, "java.lang.RuntimeException: x\n" + "\tat a.b(C.kt:1)\n".repeat(600))
        assertTrue(url.startsWith(ErrorReport.ISSUES_NEW_URL + "?title="))
        assertFalse(url.contains(" "))
        assertFalse(url.contains("\n"))
        // GitHub rejects new-issue URLs over about 8 KB
        assertTrue("url length ${url.length}", url.length < 8000)
        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        assertTrue(body.contains("**Stack trace**"))
    }
}
