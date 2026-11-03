package com.musicremover.app.processing

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the "running job" of the ViewModel: the coroutine that starts a job and then follows its events.
 * Each [launch] gets its own [Slot], so a cancel taken with [detach] never hits a job started after it.
 * No exception ever leaves a tracker job (the ViewModel scope has no exception handler); errors go to
 * `onError`, and an exception thrown by `onError` goes to [logError].
 */
class ActiveJobTracker(
    private val logError: (String, Throwable) -> Unit = { m, t -> Log.e(TAG, m, t) },
) {
    /** [id] is the job id once `start` returned it; null before, or after the slot finished or failed. */
    class Slot(val backend: ProcessingBackend, val job: Job, internal val id: AtomicReference<String?>)

    private val current = AtomicReference<Slot?>(null)

    /**
     * Runs `start`, then `run(id)` with the returned id. `onError` gets any failure of either, and a
     * [CancellationException] that did not come from cancelling this slot. Cancelling the slot never
     * reaches `onError`. If the slot is cancelled after `start` returned, `run` is never called and the id
     * is kept for [cancelAndTakeId].
     */
    fun launch(
        scope: CoroutineScope,
        backend: ProcessingBackend,
        start: suspend () -> String,
        run: suspend (String) -> Unit,
        onError: suspend (Exception) -> Unit,
    ): Slot {
        val id = AtomicReference<String?>(null)
        lateinit var slot: Slot
        val job = scope.launch(start = CoroutineStart.LAZY) { body(slot, start, run, onError) }
        slot = Slot(backend, job, id)
        current.set(slot)
        job.start()
        return slot
    }

    /** [launch] for a job that already has an id (app restarted while it ran). */
    fun resume(
        scope: CoroutineScope,
        backend: ProcessingBackend,
        id: String,
        run: suspend (String) -> Unit,
        onError: suspend (Exception) -> Unit,
    ): Slot = launch(scope, backend, start = { id }, run = run, onError = onError)

    /**
     * Synchronous. Takes the current slot and cancels its job, so a later [launch] is never affected by
     * this cancel and the detached job stops before the caller moves on (e.g. advances the queue).
     */
    fun detach(): Slot? = current.getAndSet(null)?.also { it.job.cancel() }

    private suspend fun body(
        slot: Slot,
        start: suspend () -> String,
        run: suspend (String) -> Unit,
        onError: suspend (Exception) -> Unit,
    ) {
        try {
            val id = start()
            slot.id.set(id)
            // Cancelled while start returned: keep the id for cancelAndTakeId, never call run
            currentCoroutineContext().ensureActive()
            run(id)
        } catch (e: CancellationException) {
            // A cancellation that is not ours (the slot is still active) is a failure like any other
            if (currentCoroutineContext().isActive) {
                clear(slot)
                report(onError, e)
                return
            }
            throw e
        } catch (e: Exception) {
            clear(slot)
            report(onError, e)
            return
        }
        clear(slot)
    }

    private fun clear(slot: Slot) {
        slot.id.set(null)
        current.compareAndSet(slot, null)
    }

    private suspend fun report(onError: suspend (Exception) -> Unit, e: Exception) {
        try {
            onError(e)
        } catch (t: Exception) {
            if (t is CancellationException && !currentCoroutineContext().isActive) throw t
            logError("onError failed", t)
        }
    }

    companion object {
        private const val TAG = "ActiveJobTracker"

        /**
         * Cancels the slot's job and waits for it, then returns its final id: the started job, or null if
         * start never produced one or the backend already cancelled it.
         */
        suspend fun cancelAndTakeId(slot: Slot): String? {
            slot.job.cancelAndJoin()
            return slot.id.getAndSet(null)
        }
    }
}
