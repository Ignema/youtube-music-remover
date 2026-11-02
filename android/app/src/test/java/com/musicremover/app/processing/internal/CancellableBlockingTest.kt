package com.musicremover.app.processing.internal

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CancellableBlockingTest {
    private val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val handled: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> handled.add(e) })
    }

    @After
    fun tearDown() {
        scope.cancel()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun normalCompletionReturnsValueAndNeverCallsOnCancel() = runBlocking {
        val cancels = AtomicInteger()
        val job = scope.launch {
            repeat(1_000) { i ->
                val v = cancellableBlocking(onCancel = { cancels.incrementAndGet() }) { i * 2 }
                assertEquals(i * 2, v)
            }
        }
        withTimeout(30_000) { job.join() }
        Thread.sleep(50) // let any late handler fire
        assertFalse(job.isCancelled)
        assertEquals(0, cancels.get())
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    @Test
    fun cancellationWhileBlockedCallsOnCancelOnceBeforeBlockReturns() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockReturned = AtomicBoolean(false)
        val cancelSawBlockRunning = AtomicBoolean(false)
        val cancels = AtomicInteger()
        val thrown = AtomicReference<Throwable?>(null)
        val job = scope.launch {
            try {
                cancellableBlocking(onCancel = {
                    cancelSawBlockRunning.set(!blockReturned.get())
                    cancels.incrementAndGet()
                    release.countDown()
                }) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    blockReturned.set(true)
                    42
                }
            } catch (e: Throwable) {
                thrown.set(e)
                throw e
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(1_000) { job.join() }
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L)
        assertEquals(1, cancels.get())
        assertTrue(cancelSawBlockRunning.get())
        assertTrue(thrown.get() is CancellationException)
        Thread.sleep(50)
        assertEquals(1, cancels.get())
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    @Test
    fun realFailurePropagatesAndNeverCallsOnCancel() = runBlocking {
        val cancels = AtomicInteger()
        val job = scope.launch {
            repeat(1_000) {
                try {
                    cancellableBlocking<Int>(onCancel = { cancels.incrementAndGet() }) { throw IOException("boom") }
                    throw AssertionError("expected IOException")
                } catch (e: IOException) {
                    assertEquals("boom", e.message)
                }
            }
        }
        withTimeout(30_000) { job.join() }
        Thread.sleep(50)
        assertFalse(job.isCancelled)
        assertEquals(0, cancels.get())
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(emptyList<Throwable>(), handled.toList())
    }

    @Test
    fun failureAfterCancellationSurfacesAsCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val job = scope.launch {
            try {
                cancellableBlocking<Int>(onCancel = { release.countDown() }) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    throw IOException("Canceled") // like OkHttp after Call.cancel()
                }
            } catch (e: Throwable) {
                thrown.set(e)
                throw e
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(1_000) { job.join() }
        val e = thrown.get()
        assertTrue("got $e", e is CancellationException)
        Thread.sleep(50)
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(emptyList<Throwable>(), handled.toList())
    }
}
