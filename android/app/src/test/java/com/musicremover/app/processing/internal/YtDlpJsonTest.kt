package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonTest {
    private val full = """{"id":"jNQXAC9IVRw","title":"Me at the zoo","channel":"jawed","uploader":"jawed-u","duration":19.0,""" +
        """"thumbnail":"https://i.ytimg.com/vi/jNQXAC9IVRw/hq.jpg","view_count":380000000,"upload_date":"20050424",""" +
        """"description":"The first video","is_live":false,"live_status":"not_live",""" +
        """"requested_formats":[{"format_id":"137","filesize":1000},{"format_id":"140","filesize_approx":234}]}"""

    @Test
    fun mapsAllFields() {
        val info = YtDlpJson.parse(full)
        val v = info.videoInfo
        assertEquals("Me at the zoo", v.title)
        assertEquals("jawed", v.channel)
        assertEquals(19, v.duration)
        assertEquals("https://i.ytimg.com/vi/jNQXAC9IVRw/hq.jpg", v.thumbnail)
        assertEquals(380_000_000L, v.view_count)
        assertEquals("20050424", v.upload_date)
        assertEquals("The first video", v.description)
        assertFalse(info.isLive)
        assertEquals(full, info.rawJson)
        assertEquals(1234L, info.knownBytes)
    }

    @Test
    fun missingFieldsAreEmptyOrZero() {
        val info = YtDlpJson.parse("""{"id":"x"}""")
        val v = info.videoInfo
        assertEquals("", v.title)
        assertEquals("", v.channel)
        assertEquals(0, v.duration)
        assertEquals("", v.thumbnail)
        assertEquals(0L, v.view_count)
        assertEquals("", v.upload_date)
        assertEquals("", v.description)
        assertFalse(info.isLive)
        assertNull(info.knownBytes)
    }

    @Test
    fun jsonNullForEveryFieldIsEmptyOrZero() {
        val json = """{"title":null,"channel":null,"uploader":null,"duration":null,"thumbnail":null,""" +
            """"view_count":null,"upload_date":null,"description":null,"is_live":null,"live_status":null,"filesize":null}"""
        val v = YtDlpJson.parse(json).videoInfo
        assertEquals("", v.title)
        assertEquals("", v.channel)
        assertEquals(0, v.duration)
        assertEquals("", v.thumbnail)
        assertEquals(0L, v.view_count)
        assertEquals("", v.upload_date)
        assertEquals("", v.description)
        assertNull(YtDlpJson.parse(json).knownBytes)
    }

    @Test
    fun wrongTypesAreTreatedAsMissing() {
        val v = YtDlpJson.parse("""{"title":42,"duration":"19","view_count":"many","thumbnail":{"u":1}}""").videoInfo
        assertEquals("", v.title)
        assertEquals(0, v.duration)
        assertEquals(0L, v.view_count)
        assertEquals("", v.thumbnail)
    }

    @Test
    fun channelFallsBackToUploader() {
        assertEquals("up", YtDlpJson.parse("""{"uploader":"up"}""").videoInfo.channel)
        assertEquals("up", YtDlpJson.parse("""{"channel":null,"uploader":"up"}""").videoInfo.channel)
    }

    @Test
    fun durationDoubleIsTruncated() {
        assertEquals(19, YtDlpJson.parse("""{"duration":19.0}""").videoInfo.duration)
        assertEquals(212, YtDlpJson.parse("""{"duration":212.9}""").videoInfo.duration)
    }

    @Test
    fun descriptionIsCutTo300Chars() {
        val d = "x".repeat(500)
        assertEquals(300, YtDlpJson.parse("""{"description":"$d"}""").videoInfo.description.length)
    }

    @Test
    fun liveFlags() {
        assertTrue(YtDlpJson.parse("""{"is_live":true}""").isLive)
        assertTrue(YtDlpJson.parse("""{"live_status":"is_live"}""").isLive)
        assertTrue(YtDlpJson.parse("""{"live_status":"is_upcoming"}""").isLive)
        assertTrue(YtDlpJson.parse("""{"live_status":"post_live"}""").isLive)
        assertFalse(YtDlpJson.parse("""{"live_status":"was_live"}""").isLive)
    }

    @Test
    fun knownBytesFromTopLevelWhenNoRequestedFormats() {
        assertEquals(500L, YtDlpJson.parse("""{"filesize":500}""").knownBytes)
        assertEquals(700L, YtDlpJson.parse("""{"filesize":null,"filesize_approx":700.0}""").knownBytes)
        assertNull(YtDlpJson.parse("""{"requested_formats":[{"format_id":"1"}]}""").knownBytes)
    }

    @Test
    fun takesFirstJsonLineOfMultiLineStdout() {
        val out = "WARNING: something\r\n$full\n{\"title\":\"second\"}\n"
        val info = YtDlpJson.parse(out)
        assertEquals("Me at the zoo", info.videoInfo.title)
        assertEquals(full, info.rawJson)
    }

    @Test
    fun noJsonIsOther() {
        for (out in listOf("", "WARNING: nothing\n", "{not json")) {
            try {
                YtDlpJson.parse(out)
                throw AssertionError("expected YtDlpException for '$out'")
            } catch (e: YtDlpException) {
                assertEquals(YtDlpErrors.Kind.OTHER, e.kind)
            }
        }
    }
}
