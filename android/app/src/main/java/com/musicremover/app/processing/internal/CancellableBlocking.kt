package com.musicremover.app.processing.internal

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * Runs a blocking [block] on [dispatcher] so that cancelling the caller interrupts it.
 *
 * When the caller is cancelled while [block] is still running, [onCancel] is called once (for example
 * `RunOptions.setTerminate(true)` or `Call.cancel()`) so the blocking call returns early. An exception
 * thrown by [block] after cancellation surfaces as [CancellationException]. A real failure of [block]
 * propagates unchanged and never triggers [onCancel]. No completion handler outlives the call.
 */
suspend fun <T> cancellableBlocking(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onCancel: () -> Unit,
    block: () -> T,
): T = coroutineScope {
    val blockDone = AtomicBoolean(false)
    val work = async(dispatcher) {
        try {
            block()
        } catch (e: Exception) {
            if (!isActive) throw CancellationException("cancelled", e) else throw e
        } finally {
            blockDone.set(true)
        }
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        // A real failure of `block` cancels this scope too; blockDone is then already set.
        if (!blockDone.get()) onCancel()
        throw e
    }
}
