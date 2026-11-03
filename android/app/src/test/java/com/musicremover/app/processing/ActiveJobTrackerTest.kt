package com.musicremover.app.processing

import android.net.Uri
import com.musicremover.app.data.VideoInfo
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveJobTrackerTest {
    private val handled: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val logged: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: ActiveJobTracker
    private val backend = FakeBackend()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> handled.add(e) })
        tracker = ActiveJobTracker(logError = { _, t -> logged.add(t) })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** (a) Cancelled while start is gated; start still returns an id: the id is kept and run never runs. */
    @Test
    fun cancelDuringStartKeepsIdAndSkipsRun() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val errors = AtomicInteger()
        tracker.launch(
            scope, backend,
            start = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    gate.await()
                    "id-2"
                }
            },
            run = { runs.incrementAndGet() },
            onError = { errors.incrementAndGet() },
        )
        withTimeout(5_000) { entered.await() }
        val slot = tracker.detach()!!
        gate.complete(Unit)
        val id = withTimeout(5_000) { ActiveJobTracker.cancelAndTakeId(slot) }
        assertEquals("id-2", id)
        assertEquals(0, runs.get())
        assertEquals(0, errors.get())
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (b) A start ended by cancelling the slot reaches neither run nor onError. */
    @Test
    fun startCancelledBySlotCancelIsSilent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val errors = AtomicInteger()
        tracker.launch(
            scope, backend,
            start = {
                entered.complete(Unit)
                awaitCancellation()
            },
            run = { runs.incrementAndGet() },
            onError = { errors.incrementAndGet() },
        )
        withTimeout(5_000) { entered.await() }
        val slot = tracker.detach()!!
        assertNull(withTimeout(5_000) { ActiveJobTracker.cancelAndTakeId(slot) })
        assertEquals(0, runs.get())
        assertEquals(0, errors.get())
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (b) A foreign CancellationException while the slot is active is a failure: onError, slot cleared. */
    @Test
    fun foreignCancellationWhileActiveGoesToOnError() = runBlocking {
        val error = CompletableDeferred<Exception>()
        val runs = AtomicInteger()
        val slot = tracker.launch(
            scope, backend,
            start = { throw CancellationException("foreign") },
            run = { runs.incrementAndGet() },
            onError = { error.complete(it) },
        )
        val e = withTimeout(5_000) { error.await() }
        assertEquals("foreign", e.message)
        withTimeout(5_000) { slot.job.join() }
        assertEquals(0, runs.get())
        assertNull(tracker.detach())
        assertNull(ActiveJobTracker.cancelAndTakeId(slot))
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (c) A slot that ran to the end clears itself. */
    @Test
    fun normalCompletionClearsSlot() = runBlocking {
        val ran = CompletableDeferred<String>()
        val slot = tracker.launch(
            scope, backend,
            start = { "id-1" },
            run = { ran.complete(it) },
            onError = { throw AssertionError("unexpected", it) },
        )
        assertEquals("id-1", withTimeout(5_000) { ran.await() })
        withTimeout(5_000) { slot.job.join() }
        assertNull(tracker.detach())
        assertNull(ActiveJobTracker.cancelAndTakeId(slot))
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (d) detach cancels at once; a launch after it is not touched by the cancel of the first slot. */
    @Test
    fun detachThenLaunchLeavesSecondJobRunning() = runBlocking {
        val firstRunning = CompletableDeferred<Unit>()
        val secondRunning = CompletableDeferred<Unit>()
        val first = tracker.launch(
            scope, backend,
            start = { "id-1" },
            run = {
                firstRunning.complete(Unit)
                awaitCancellation()
            },
            onError = { throw AssertionError("unexpected", it) },
        )
        withTimeout(5_000) { firstRunning.await() }
        val detached = tracker.detach()
        assertSame(first, detached)
        // MEDIUM-1: cancelled synchronously, before any suspension
        assertTrue(first.job.isCancelled)
        val second = tracker.launch(
            scope, backend,
            start = { "id-2" },
            run = {
                secondRunning.complete(Unit)
                awaitCancellation()
            },
            onError = { throw AssertionError("unexpected", it) },
        )
        withTimeout(5_000) { secondRunning.await() }
        assertEquals("id-1", withTimeout(5_000) { ActiveJobTracker.cancelAndTakeId(first) })
        assertTrue(second.job.isActive)
        assertSame(second, tracker.detach())
        assertEquals("id-2", withTimeout(5_000) { ActiveJobTracker.cancelAndTakeId(second) })
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (e) A failing start goes to onError; nothing escapes into the scope. */
    @Test
    fun startFailureGoesToOnError() = runBlocking {
        val error = CompletableDeferred<Exception>()
        val runs = AtomicInteger()
        val slot = tracker.launch(
            scope, backend,
            start = { throw IllegalArgumentException("x") },
            run = { runs.incrementAndGet() },
            onError = { error.complete(it) },
        )
        val e = withTimeout(5_000) { error.await() }
        assertTrue(e is IllegalArgumentException)
        assertEquals("x", e.message)
        withTimeout(5_000) { slot.job.join() }
        assertEquals(0, runs.get())
        assertNull(tracker.detach())
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** (f) An onError that throws is logged; the scope stays active. */
    @Test
    fun throwingOnErrorIsLogged() = runBlocking {
        val boom = IllegalStateException("onError boom")
        val slot = tracker.launch(
            scope, backend,
            start = { throw IllegalArgumentException("x") },
            run = {},
            onError = { throw boom },
        )
        withTimeout(5_000) { slot.job.join() }
        assertEquals(listOf<Throwable>(boom), logged.toList())
        assertFalse(slot.job.isCancelled)
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    /** resume runs the given id without a start call. */
    @Test
    fun resumeRunsGivenId() = runBlocking {
        val ran = CompletableDeferred<String>()
        val slot = tracker.resume(scope, backend, "local-1", run = { ran.complete(it) }, onError = { throw AssertionError(it) })
        assertEquals("local-1", withTimeout(5_000) { ran.await() })
        withTimeout(5_000) { slot.job.join() }
        assertNull(tracker.detach())
    }

    private class FakeBackend : ProcessingBackend {
        override val id = "fake"
        override suspend fun isReady() = true
        override suspend fun info(url: String): VideoInfo = throw UnsupportedOperationException()
        override fun supportedModels(): List<String> = emptyList()
        override suspend fun startUrl(url: String, options: ProcessOptions): String = throw UnsupportedOperationException()
        override suspend fun startFile(uri: Uri, fileName: String, options: ProcessOptions): String =
            throw UnsupportedOperationException()
        override fun events(jobId: String): Flow<JobEvent> = emptyFlow()
        override suspend fun cancel(jobId: String) {}
        override suspend fun isResultAvailable(jobId: String) = false
        override suspend fun fetchResult(jobId: String, filename: String): File? = null
        override suspend fun waveform(jobId: String): List<Float>? = null
        override fun remoteResultUrl(jobId: String): String? = null
    }
}
