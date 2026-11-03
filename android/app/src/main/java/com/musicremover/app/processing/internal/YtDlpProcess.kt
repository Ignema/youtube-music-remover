package com.musicremover.app.processing.internal

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MuremYtDlp"
private const val KILL_CAP_MS = 15_000L
private const val KILL_INTERVAL_MS = 200L

/**
 * Runs one blocking yt-dlp call ([block], usually `YoutubeDL.execute(req, processId, cb)`) on IO under the
 * read side of [lock], so "Update yt-dlp" never swaps the binary while it runs. The read lock is taken and
 * released on the same IO thread.
 *
 * If the caller is cancelled while [block] waits for the lock (an update holds the write lock), [block] is
 * never started: `ensureActive()` runs right after the lock is acquired. If the caller is cancelled while
 * [block] runs, [destroy] is called every 200 ms until [block] returns, because `destroyProcessById`
 * returns false while the process is not yet registered. An exception from [block] (for example
 * `YoutubeDL.CanceledException` or `InterruptedException`) becomes [CancellationException] only when the
 * caller is no longer active; while it is active it is a real failure and propagates unchanged.
 *
 * After 15 s the kill loop stops and logs W. runYtDlp still returns only when block has returned
 * (coroutineScope semantics), so workDir is never deleted under a live process. Besides the Python process,
 * yt-dlp may run a short-lived QuickJS child, which writes nothing into workDir.
 *
 * [lock] and [destroy] are parameters for tests; the defaults touch [YoutubeDL] only when called.
 */
internal suspend fun <T> runYtDlp(
    processId: String,
    lock: ReentrantReadWriteLock = YtDlpRuntime.lock,
    destroy: (String) -> Unit = { YoutubeDL.destroyProcessById(it) },
    block: () -> T,
): T = coroutineScope {
    val d = async(Dispatchers.IO) {
        try {
            lock.read {
                ensureActive()
                block()
            }
        } catch (e: Exception) {
            if (!isActive) throw CancellationException("cancelled", e) else throw e
        }
    }
    try {
        d.await()
    } catch (e: CancellationException) {
        // A real failure of block cancels this scope too; d is then already completed
        if (!d.isCompleted) Log.i(TAG, "yt-dlp cancelled: $processId")
        withContext(NonCancellable) {
            val done = withTimeoutOrNull(KILL_CAP_MS) {
                while (!d.isCompleted) {
                    destroy(processId)
                    delay(KILL_INTERVAL_MS)
                }
            }
            if (done == null) Log.w(TAG, "yt-dlp $processId still running after 15 s")
        }
        throw e
    }
}
