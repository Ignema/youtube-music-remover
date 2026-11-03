package com.musicremover.app.processing.internal

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFailureTest {
    private val needed = 120_000_000L

    @Test
    fun storageExceptionKeepsItsOwnBytes() {
        assertEquals(ModelFailure.NoSpace(77L), ModelFailure.of(ModelStorageException(77L), needed))
    }

    @Test
    fun diskFullWriteIsNoSpace() {
        val e = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(ModelFailure.NoSpace(needed), ModelFailure.of(e, needed))
    }

    @Test
    fun nestedDiskFullIsNoSpace() {
        val e = RuntimeException("wrapped", IOException("No space left on device"))
        assertEquals(ModelFailure.NoSpace(needed), ModelFailure.of(e, needed))
    }

    @Test
    fun corruptIsCorrupt() {
        assertEquals(ModelFailure.Corrupt, ModelFailure.of(ModelCorruptException("checksum"), needed))
        assertEquals(ModelFailure.Corrupt, ModelFailure.of(ModelCorruptException("size"), needed))
    }

    @Test
    fun renameFailedIsOther() {
        assertEquals(ModelFailure.Other, ModelFailure.of(IOException("rename failed"), needed))
    }

    @Test
    fun httpErrorIsNetwork() {
        assertEquals(ModelFailure.Network, ModelFailure.of(IOException("HTTP 404"), needed))
    }

    @Test
    fun emptyBodyAndSocketErrorsAreNetwork() {
        assertEquals(ModelFailure.Network, ModelFailure.of(IOException("empty body"), needed))
        assertEquals(ModelFailure.Network, ModelFailure.of(SocketTimeoutException("timeout"), needed))
        assertEquals(ModelFailure.Network, ModelFailure.of(IOException(), needed))
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(ModelFailure.Other, ModelFailure.of(IllegalStateException("x"), needed))
        assertEquals(ModelFailure.Other, ModelFailure.of(RuntimeException(), needed))
    }
}
