package com.musicremover.app.processing.internal

import kotlin.math.abs

/** Sink for a model whose primary stem is already the vocals. */
internal class PrimaryStemSink(private val out: PcmFileWriter) : PrimarySink {
    var peak = 0f
        private set

    override fun accept(pos: Long, primaryL: FloatArray, primaryR: FloatArray, count: Int) {
        var p = peak
        for (j in 0 until count) p = maxOf(p, abs(primaryL[j]), abs(primaryR[j]))
        peak = p
        out.write(primaryL, primaryR, 0, count)
    }
}
