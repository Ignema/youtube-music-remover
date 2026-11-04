package com.musicremover.app.processing.internal

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp3ArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val out = File("/data/user/0/app/cache/results/.tmp_local-1")

    private fun expected(kbps: String) = listOf(
        "-hide_banner", "-loglevel", "error",
        "-f", "s16le", "-ar", "44100", "-ac", "2", "-i", "pipe:0",
        "-c:a", "libmp3lame", "-b:a", kbps,
        "-f", "mp3", "-y", out.path,
    )

    @Test
    fun cbr128() = assertEquals(expected("128k"), Mp3Args.build(128_000, out))

    @Test
    fun cbr192() = assertEquals(expected("192k"), Mp3Args.build(192_000, out))

    @Test
    fun cbr320() = assertEquals(expected("320k"), Mp3Args.build(320_000, out))

    @Test
    fun writesLittleEndianInterleavedS16WithGainAndClipping() {
        val pcm = tmp.newFile("vocals.f32")
        val samples = floatArrayOf(0.5f, -0.5f, 2f, -2f, 0.25f, 0f)
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putFloat(it) }
        pcm.writeBytes(bb.array())

        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        Mp3Encoder.writePcm16(pcm, 0.5f, sink) { progress += it }

        val got = ByteBuffer.wrap(sink.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(got.remaining()).also { got.get(it) }
        val want = samples.map { VocalsEncoder.toPcm16(it, 0.5f) }.toShortArray()
        assertEquals(want.toList(), shorts.toList())
        assertEquals(listOf(8191, -8191, 32767, -32767, 4095, 0), shorts.map { it.toInt() })
        assertEquals(listOf(100), progress)
    }
}
