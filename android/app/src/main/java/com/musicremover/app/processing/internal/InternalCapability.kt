package com.musicremover.app.processing.internal

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.musicremover.app.R

/**
 * Refuses devices that cannot run the ONNX model (about 1.0-1.2 GB of native memory per segment).
 * On Android the process is usually killed instead of getting an OutOfMemoryError, so this is checked up front.
 *
 * [MIN_TOTAL_MEM] is 3.4e9 bytes: `MemoryInfo.totalMem` leaves out kernel-reserved memory, so "4 GB" phones
 * report about 3.6-3.8e9 and must pass, while 3 GB phones (about 2.7-2.9e9) are still refused. The per-run
 * [MIN_AVAIL_MEM] check in the worker is the real out-of-memory guard.
 */
object InternalCapability {
    const val MIN_TOTAL_MEM = 3_400_000_000L
    const val MIN_AVAIL_MEM = 1_500_000_000L

    enum class Refusal { NOT_64_BIT, LOW_RAM_DEVICE, TOTAL_MEM }

    /** Pure. null = supported. */
    fun check(is64Bit: Boolean, totalMemBytes: Long, isLowRam: Boolean): Refusal? = when {
        !is64Bit -> Refusal.NOT_64_BIT
        isLowRam -> Refusal.LOW_RAM_DEVICE
        totalMemBytes < MIN_TOTAL_MEM -> Refusal.TOTAL_MEM
        else -> null
    }

    private class Memo(val reason: String?)

    @Volatile
    private var cached: Memo? = null

    /** The localized refusal reason, or null if the device is supported. Read once per process. */
    fun current(context: Context): String? {
        cached?.let { return it.reason }
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val refusal = check(Process.is64Bit(), mi.totalMem, am.isLowRamDevice)
        val reason = refusal?.let {
            Log.w(TAG, "On-device processing refused: $it (totalMem=${mi.totalMem / 1_000_000} MB)")
            context.getString(R.string.internal_device_unsupported)
        }
        cached = Memo(reason)
        return reason
    }

    private const val TAG = "MuremInternal"
}
