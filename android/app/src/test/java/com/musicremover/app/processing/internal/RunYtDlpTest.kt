package com.musicremover.app.processing.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [runYtDlp] with an injected lock, destroy and block; no youtubedl-android code runs. */
class RunYtDlpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** MEDIUM-2: cancelled while an update holds the write lock -> block never starts. */
    @Test
    fun cancelWhileWaitingForLockNeverInvokesBlock() = runBlocking {
        val lock = ReentrantReadWriteLock()
        val invoked = AtomicBoolean(false)
        val destroys = AtomicInteger()
        val thrown = AtomicReference<Throwable?>(null)
        // runBlocking stays on this thread, so the write lock is released by the thread that took it
        lock.writeLock().lock()
        val job = try {
            val j = scope.launch {
                try {
                    runYtDlp("p", lock, destroy = { destroys.incrementAndGet() }) { invoked.set(true) }
                } catch (e: Throwable) {
                    thrown.set(e)
                    throw e
                }
            }
            withTimeout(5_000) { while (lock.queueLength == 0) delay(5) }
            j.cancel()
            // The kill loop runs while the block still waits for the lock
            withTimeout(5_000) { while (destroys.get() == 0) delay(5) }
            j
        } finally {
            lock.writeLock().unlock()
        }
        withTimeout(5_000) { job.join() }
        assertFalse("block ran after the cancel", invoked.get())
        assertTrue(job.isCancelled)
        assertTrue("got ${thrown.get()}", thrown.get() is CancellationException)
        assertEquals(0, lock.readLockCount)
    }

    @Test
    fun interruptedWhileActiveIsAFailure() = runBlocking {
        val destroys = AtomicInteger()
        val thrown = AtomicReference<Throwable?>(null)
        val job = scope.launch {
            try {
                runYtDlp<Unit>("p", ReentrantReadWriteLock(), destroy = { destroys.incrementAndGet() }) {
                    throw InterruptedException("boom")
                }
            } catch (e: Throwable) {
                thrown.set(e)
            }
        }
        withTimeout(5_000) { job.join() }
        val e = thrown.get()
        assertTrue("got $e", e is InterruptedException)
        assertFalse(job.isCancelled)
        assertEquals(0, destroys.get())
    }

    @Test
    fun cancelDuringBlockDestroysUntilBlockReturns() = runBlocking {
        val lock = ReentrantReadWriteLock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockReturned = AtomicBoolean(false)
        val returnedBeforeCaller = AtomicBoolean(false)
        // Like destroyProcessById before the process is registered: the first calls do nothing
        val destroys = AtomicInteger()
        val job = scope.launch {
            try {
                runYtDlp("p", lock, destroy = { if (destroys.incrementAndGet() == 3) release.countDown() }) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    blockReturned.set(true)
                }
            } finally {
                returnedBeforeCaller.set(blockReturned.get())
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("runYtDlp returned before the block", returnedBeforeCaller.get())
        assertTrue("destroy called ${destroys.get()} times", destroys.get() >= 3)
        assertTrue(job.isCancelled)
        assertEquals(0, lock.readLockCount)
    }

    @Test
    fun normalCompletionReturnsValueWithoutDestroy() = runBlocking {
        val destroys = AtomicInteger()
        val v = withTimeout(5_000) {
            runYtDlp("p", ReentrantReadWriteLock(), destroy = { destroys.incrementAndGet() }) { 42 }
        }
        assertEquals(42, v)
        assertEquals(0, destroys.get())
    }
}
