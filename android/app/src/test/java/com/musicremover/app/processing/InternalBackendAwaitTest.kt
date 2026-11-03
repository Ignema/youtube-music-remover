package com.musicremover.app.processing

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [awaitOrCancel] runs onCancel exactly once when the await is cancelled, and never on success.
 *
 * Rule it relies on (see its KDoc): only the blocking pre-checks run inside `withContext(Dispatchers.IO)`;
 * the enqueue, `awaitOrCancel` and the `return` run in the caller's context, with no suspension after it.
 */
class InternalBackendAwaitTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun successNeverCallsOnCancel() = runBlocking {
        val cancels = AtomicInteger()
        val v = withTimeout(5_000) { awaitOrCancel({ 42 }, { cancels.incrementAndGet() }) }
        assertEquals(42, v)
        assertEquals(0, cancels.get())
    }

    @Test
    fun failureNeverCallsOnCancel() = runBlocking {
        val cancels = AtomicInteger()
        try {
            awaitOrCancel<Int>({ throw IllegalStateException("boom") }, { cancels.incrementAndGet() })
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(0, cancels.get())
    }

    @Test
    fun cancelDuringAwaitCallsOnCancelOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancels = AtomicInteger()
        val onCancelSawCancelledCaller = AtomicReference<Boolean?>(null)
        val thrown = AtomicReference<Throwable?>(null)
        val job = scope.launch {
            try {
                awaitOrCancel<Unit>(
                    await = {
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                    onCancel = {
                        // Runs in NonCancellable, so it can suspend although the caller is cancelled
                        kotlinx.coroutines.delay(10)
                        onCancelSawCancelledCaller.set(true)
                        cancels.incrementAndGet()
                    },
                )
            } catch (e: Throwable) {
                thrown.set(e)
                throw e
            }
        }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals(1, cancels.get())
        assertEquals(true, onCancelSawCancelledCaller.get())
        assertTrue(thrown.get() is CancellationException)
        assertTrue(job.isCancelled)
    }
}
