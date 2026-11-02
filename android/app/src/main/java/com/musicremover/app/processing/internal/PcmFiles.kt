package com.musicremover.app.processing.internal

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val BYTES_PER_FRAME = 8 // stereo float32
private const val IO_FRAMES = 64 * 1024

/** Reads interleaved little-endian float32 stereo PCM with positional reads. */
class PcmFileReader(file: File) : StereoSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    private val buf = ByteBuffer.allocate(IO_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)

    override val frames: Long = channel.size() / BYTES_PER_FRAME

    override fun read(pos: Long, l: FloatArray, r: FloatArray, off: Int, count: Int) {
        require(pos >= 0 && pos + count <= frames) { "read past end of PCM file" }
        var done = 0
        while (done < count) {
            val n = minOf(IO_FRAMES, count - done)
            buf.clear()
            buf.limit(n * BYTES_PER_FRAME)
            var filePos = (pos + done) * BYTES_PER_FRAME
            while (buf.hasRemaining()) {
                val got = channel.read(buf, filePos)
                if (got < 0) throw EOFException("PCM file truncated")
                filePos += got
            }
            buf.flip()
            for (j in 0 until n) {
                l[off + done + j] = buf.getFloat()
                r[off + done + j] = buf.getFloat()
            }
            done += n
        }
    }

    override fun close() = raf.close()
}

/** Writes interleaved little-endian float32 stereo PCM through a 1 MB buffer. */
class PcmFileWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    private val buf = ByteBuffer.allocate(IO_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)

    var frames = 0L
        private set

    fun write(l: FloatArray, r: FloatArray, off: Int, count: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(IO_FRAMES, count - done)
            buf.clear()
            for (j in 0 until n) {
                buf.putFloat(l[off + done + j])
                buf.putFloat(r[off + done + j])
            }
            out.write(buf.array(), 0, n * BYTES_PER_FRAME)
            done += n
        }
        frames += count
    }

    override fun close() = out.close()
}

/**
 * Writes the secondary stem `inputScale·mix − compensate·primary` (vocals for an instrumental model)
 * for each span of primary output, reading the matching span of [mix].
 */
class VocalsSink(
    private val mix: PcmFileReader,
    private val out: PcmFileWriter,
    private val inputScale: Float,
    private val compensate: Float,
) : PrimarySink {
    private var mixL = FloatArray(0)
    private var mixR = FloatArray(0)

    var peakVocals = 0f
        private set

    override fun accept(pos: Long, primaryL: FloatArray, primaryR: FloatArray, count: Int) {
        if (mixL.size < count) {
            mixL = FloatArray(count)
            mixR = FloatArray(count)
        }
        mix.read(pos, mixL, mixR, 0, count)
        var peak = peakVocals
        for (j in 0 until count) {
            val vl = inputScale * mixL[j] - compensate * primaryL[j]
            val vr = inputScale * mixR[j] - compensate * primaryR[j]
            mixL[j] = vl
            mixR[j] = vr
            peak = maxOf(peak, abs(vl), abs(vr))
        }
        peakVocals = peak
        out.write(mixL, mixR, 0, count)
    }
}
