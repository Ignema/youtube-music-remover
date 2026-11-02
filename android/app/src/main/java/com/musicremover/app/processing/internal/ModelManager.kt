package com.musicremover.app.processing.internal

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class ModelState {
    data object NotDownloaded : ModelState()
    data class Downloading(val bytes: Long, val total: Long) : ModelState()
    data class Ready(val sizeBytes: Long) : ModelState()
    data class Failed(val message: String) : ModelState()
}

/** Not enough free storage for the model; [neededBytes] = model size + margin. */
class ModelStorageException(val neededBytes: Long) : IOException("not enough space: $neededBytes bytes needed")

/** The downloaded bytes do not match the catalog size or SHA-256; the partial file was deleted. */
class ModelCorruptException(message: String) : IOException(message)

/**
 * Downloads, verifies and deletes on-device models in filesDir/models. Process-wide, so Settings and the
 * worker share state and never download at the same time.
 */
class ModelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "models")
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        ModelCatalog.get(appContext).associate { it.name to diskState(it) }
    )
    val state: StateFlow<Map<String, ModelState>> = _state.asStateFlow()

    fun file(name: String): File = File(dir, name)

    /** File exists and has the catalog size. The SHA-256 is verified once, at download. */
    fun isDownloaded(name: String): Boolean {
        val spec = ModelCatalog.spec(appContext, name) ?: return false
        return isDownloaded(spec)
    }

    /**
     * Returns the verified model file, downloading it first if needed. Resumes from `<name>.part`.
     * Throws [ModelStorageException], [ModelCorruptException], or [IOException] for download failures.
     */
    // The storage check is a plain estimate against usableSpace; a later write failure surfaces as an IOException
    @SuppressLint("UsableSpace")
    suspend fun ensure(name: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = mutex.withLock {
        val spec = ModelCatalog.spec(appContext, name) ?: throw IllegalArgumentException("Unknown model: $name")
        val target = file(name)
        if (isDownloaded(spec)) {
            setState(name, ModelState.Ready(spec.size))
            return@withLock target
        }
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val needed = spec.size + SPACE_MARGIN
                if (dir.usableSpace < needed) throw ModelStorageException(needed)
                val part = File(dir, "$name.part")
                download(spec, part, onProgress)
                verify(spec, part)
                target.delete()
                if (!part.renameTo(target)) throw IOException("rename failed")
            }
            setState(name, ModelState.Ready(spec.size))
            target
        } catch (e: CancellationException) {
            setState(name, diskState(spec))
            throw e
        } catch (e: Exception) {
            setState(name, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    /** Deletes the model and its partial file. Returns false if a download or verification holds the lock. */
    fun delete(name: String): Boolean {
        if (!mutex.tryLock()) return false
        try {
            file(name).delete()
            File(dir, "$name.part").delete()
            setState(name, ModelState.NotDownloaded)
            return true
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun download(spec: ModelSpec, part: File, onProgress: (Long, Long) -> Unit) {
        val ctx = currentCoroutineContext()
        var retried416 = false
        while (true) {
            var len = if (part.exists()) part.length() else 0L
            if (len == spec.size) return
            if (len > spec.size) {
                part.delete()
                len = 0L
            }
            val request = Request.Builder().url(spec.url)
                .apply { if (len > 0) header("Range", "bytes=$len-") }
                .build()
            val call = client.newCall(request)
            val restart = cancellableBlocking(onCancel = { call.cancel() }) {
                call.execute().use { resp ->
                    val append = when {
                        len > 0 && resp.code == 206 -> true
                        resp.code == 200 -> false
                        len > 0 && resp.code == 416 && !retried416 -> return@cancellableBlocking true
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("empty body")
                    var written = if (append) len else 0L
                    var lastReported = written
                    reportProgress(spec, written, onProgress)
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                ctx.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (written - lastReported >= PROGRESS_STEP) {
                                    lastReported = written
                                    reportProgress(spec, written, onProgress)
                                }
                            }
                        }
                    }
                    reportProgress(spec, written, onProgress)
                    false
                }
            }
            if (!restart) return
            // 416: the server rejected our range; start over once from 0.
            retried416 = true
            part.delete()
        }
    }

    private suspend fun verify(spec: ModelSpec, part: File) {
        if (part.length() != spec.size) {
            part.delete()
            throw ModelCorruptException("size")
        }
        val ctx = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(part).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                ctx.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != spec.sha256) {
            part.delete()
            throw ModelCorruptException("checksum")
        }
    }

    private fun reportProgress(spec: ModelSpec, bytes: Long, onProgress: (Long, Long) -> Unit) {
        setState(spec.name, ModelState.Downloading(bytes, spec.size))
        onProgress(bytes, spec.size)
    }

    private fun isDownloaded(spec: ModelSpec): Boolean {
        val f = file(spec.name)
        return f.isFile && f.length() == spec.size
    }

    private fun diskState(spec: ModelSpec): ModelState =
        if (isDownloaded(spec)) ModelState.Ready(spec.size) else ModelState.NotDownloaded

    private fun setState(name: String, s: ModelState) = _state.update { it + (name to s) }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 256 * 1024L
        private const val SPACE_MARGIN = 50L * 1024 * 1024

        @Volatile
        private var instance: ModelManager? = null

        fun get(context: Context): ModelManager = instance ?: synchronized(this) {
            instance ?: ModelManager(context).also { instance = it }
        }
    }
}
